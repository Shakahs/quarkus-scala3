package io.quarkiverse.scala.scala3.deployment;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.jboss.logging.Logger;

import io.quarkus.deployment.dev.CompilationProvider;
import sbt.internal.inc.PlainVirtualFile;
import sbt.internal.inc.ZincUtil;
import scala.Option;
import xsbti.PathBasedFile;
import xsbti.Position;
import xsbti.Problem;
import xsbti.Reporter;
import xsbti.Severity;
import xsbti.VirtualFile;
import xsbti.compile.AnalysisContents;
import xsbti.compile.AnalysisStore;
import xsbti.compile.ClasspathOptions;
import xsbti.compile.ClasspathOptionsUtil;
import xsbti.compile.CompileOptions;
import xsbti.compile.CompileOrder;
import xsbti.compile.CompileResult;
import xsbti.compile.CompilerCache;
import xsbti.compile.Compilers;
import xsbti.compile.IncOptions;
import xsbti.compile.IncrementalCompiler;
import xsbti.compile.Inputs;
import xsbti.compile.PerClasspathEntryLookup;
import xsbti.compile.PreviousResult;
import xsbti.compile.ScalaInstance;
import xsbti.compile.Setup;

/**
 * Quarkus's synchronous trigger for a stateful Zinc compiler.
 *
 * <p>
 * Quarkus owns source watching and invokes this provider once for each extension that changed.
 * Zinc receives the complete current Java/Scala source set, so the separate Java and Scala
 * notifications are harmless: the first invocation performs the incremental compile and the
 * second invocation is a no-op according to the persisted analysis.
 */
public final class Scala3CompilationProvider implements CompilationProvider {

    private static final Logger LOG = Logger.getLogger(Scala3CompilationProvider.class);
    private static final String SCALA_VERSION = "3.8.4";
    private static final Set<String> HANDLED_EXTENSIONS = Set.of(".scala", ".java");
    private static final String COMPILER_ARGS_ENV_VAR = "QUARKUS_SCALA3_COMPILER_ARGS";

    private final Map<String, ZincState> states = new HashMap<>();

    @Override
    public Set<String> handledExtensions() {
        return HANDLED_EXTENSIONS;
    }

    @Override
    public synchronized void compile(Set<File> changedFiles, Context context) {
        if (context == null) {
            throw new IllegalStateException("Quarkus supplied no compilation context");
        }

        List<File> sources = findSources(context);
        if (sources.isEmpty()) {
            return;
        }

        String stateKey = context.getOutputDirectory().getAbsolutePath();
        ZincState state = states.computeIfAbsent(stateKey, ignored -> createState(context));
        state.compile(sources, context);
    }

    @Override
    public synchronized void close() throws IOException {
        states.clear();
    }

    private ZincState createState(Context context) {
        try {
            return new ZincState(context);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to initialize the Scala 3 Zinc compiler", e);
        }
    }

    private static List<File> findSources(Context context) {
        File sourceDirectory = context.getSourceDirectory();
        if (context.getProjectDirectory() != null) {
            String sourceSet = context.getOutputDirectory().getName().equals("test-classes") ? "src/test" : "src/main";
            File projectSourceSet = new File(context.getProjectDirectory(), sourceSet);
            if (projectSourceSet.isDirectory()) {
                sourceDirectory = projectSourceSet;
            }
        }
        if (sourceDirectory == null || !sourceDirectory.isDirectory()) {
            return Collections.emptyList();
        }
        try (var stream = Files.walk(sourceDirectory.toPath())) {
            return stream.filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .filter(file -> file.getName().endsWith(".scala") || file.getName().endsWith(".java"))
                    .sorted(Comparator.comparing(File::getAbsolutePath))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to enumerate sources under " + sourceDirectory, e);
        }
    }

    private static List<String> compilerArgs() {
        String value = System.getenv(COMPILER_ARGS_ENV_VAR);
        if (value == null || value.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(arg -> !arg.isEmpty())
                .collect(Collectors.toList());
    }

    private static final class ZincState {
        private final File cacheFile;
        private final AnalysisStore analysisStore;
        private final IncrementalCompiler compiler;
        private final Compilers compilers;
        private final Setup setup;
        private final ZincLogger logger;
        private final sbt.internal.inc.ScalaInstance scalaInstance;

        private ZincState(Context context) throws Exception {
            File outputDirectory = context.getOutputDirectory();
            File analysisDirectory = new File(outputDirectory.getParentFile(), "analysis");
            if (!analysisDirectory.isDirectory() && !analysisDirectory.mkdirs()) {
                throw new IOException("Unable to create Zinc analysis directory " + analysisDirectory);
            }
            this.cacheFile = new File(analysisDirectory,
                    outputDirectory.getName().equals("test-classes") ? "test-compile" : "compile");
            this.analysisStore = AnalysisStore.getCachedStore(
                    sbt.internal.inc.FileAnalysisStore.binary(cacheFile));
            this.logger = new ZincLogger();

            List<File> scalaJars = scalaJars(context.getClasspath());
            File compilerJar = requiredJar(scalaJars, "scala3-compiler_3-");
            File bridgeJar = bridgeJar(context);

            this.scalaInstance = scalaInstance(scalaJars);
            ClasspathOptions classpathOptions = ClasspathOptionsUtil.auto();
            sbt.internal.inc.AnalyzingCompiler scalaCompiler = new sbt.internal.inc.AnalyzingCompiler(
                    this.scalaInstance,
                    ZincUtil.constantBridgeProvider(this.scalaInstance, bridgeJar),
                    classpathOptions,
                    new scala.Function1<scala.collection.immutable.Seq<String>, scala.runtime.BoxedUnit>() {
                        @Override
                        public scala.runtime.BoxedUnit apply(scala.collection.immutable.Seq<String> ignored) {
                            return scala.runtime.BoxedUnit.UNIT;
                        }
                    },
                    Option.empty());

            this.compilers = ZincUtil.compilers(
                    this.scalaInstance,
                    ClasspathOptionsUtil.boot(),
                    Option.apply(Path.of(System.getProperty("java.home"))),
                    scalaCompiler);
            this.compiler = ZincUtil.defaultIncrementalCompiler();
            this.setup = Setup.of(
                    new Lookup(context.getOutputDirectory(), analysisStore),
                    false,
                    cacheFile,
                    CompilerCache.fresh(),
                    IncOptions.of(),
                    new ZincReporter(logger),
                    Optional.empty(),
                    new xsbti.T2[0]);

            LOG.debugf("Initialized Zinc for Scala %s using %s and %s", SCALA_VERSION, compilerJar, bridgeJar);
        }

        private void compile(List<File> sources, Context context) {
            File outputDirectory = context.getOutputDirectory();
            if (!outputDirectory.isDirectory() && !outputDirectory.mkdirs()) {
                throw new IllegalStateException("Unable to create compiler output directory " + outputDirectory);
            }

            List<File> classpath = new ArrayList<>(context.getClasspath());
            if (!classpath.contains(outputDirectory)) {
                classpath.add(outputDirectory);
            }

            VirtualFile[] classpathFiles = classpath.stream()
                    .map(file -> new PlainVirtualFile(file.toPath()))
                    .toArray(VirtualFile[]::new);
            VirtualFile[] sourceFiles = sources.stream()
                    .map(file -> new PlainVirtualFile(file.toPath()))
                    .toArray(VirtualFile[]::new);

            List<String> scalacOptions = new ArrayList<>(compilerArgs());
            scalacOptions.add("-encoding");
            scalacOptions.add(context.getSourceEncoding().name());
            List<String> javacOptions = new ArrayList<>();
            if (context.getReleaseJavaVersion() != null && !context.getReleaseJavaVersion().isBlank()) {
                javacOptions.add("--release");
                javacOptions.add(context.getReleaseJavaVersion());
            }

            CompileOptions options = CompileOptions.of(
                    classpathFiles,
                    sourceFiles,
                    outputDirectory.toPath(),
                    scalacOptions.toArray(String[]::new),
                    javacOptions.toArray(String[]::new),
                    100,
                    position -> position,
                    CompileOrder.Mixed);

            try {
                Thread thread = Thread.currentThread();
                ClassLoader previousClassLoader = thread.getContextClassLoader();
                thread.setContextClassLoader(scalaInstance.loader());
                try {
                    CompileResult result = compiler.compile(
                            Inputs.of(compilers, options, setup, previousResult()),
                            logger);
                    analysisStore.set(AnalysisContents.create(result.analysis(), result.setup()));
                } finally {
                    thread.setContextClassLoader(previousClassLoader);
                }
            } catch (xsbti.CompileFailed e) {
                throw new IllegalStateException("Scala/Java incremental compilation failed", e);
            }
        }

        private PreviousResult previousResult() {
            return analysisStore.get()
                    .map(contents -> PreviousResult.of(contents.getAnalysis(), contents.getMiniSetup()))
                    .orElseGet(() -> PreviousResult.of(Optional.empty(), Optional.empty()));
        }

        private static List<File> scalaJars(Set<File> classpath) {
            return classpath.stream()
                    .filter(File::isFile)
                    .filter(file -> {
                        String name = file.getName();
                        return name.startsWith("scala3-") || name.startsWith("scala-library-" + SCALA_VERSION)
                                || name.startsWith("scala-asm-") || name.startsWith("tasty-core-");
                    })
                    .sorted(Comparator.comparing(File::getAbsolutePath))
                    .collect(Collectors.toList());
        }

        private static File requiredJar(Iterable<File> files, String prefix) {
            return toList(files).stream()
                    .filter(File::isFile)
                    .filter(file -> file.getName().startsWith(prefix))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Missing Scala compiler dependency " + prefix));
        }

        private static List<File> toList(Iterable<File> files) {
            List<File> result = new ArrayList<>();
            for (File file : files) {
                result.add(file);
            }
            return result;
        }

        private static sbt.internal.inc.ScalaInstance scalaInstance(List<File> scalaJars)
                throws MalformedURLException {
            URL[] urls = scalaJars.stream().map(file -> {
                try {
                    return file.toURI().toURL();
                } catch (MalformedURLException e) {
                    throw new IllegalStateException(e);
                }
            }).toArray(URL[]::new);
            ClassLoader libraryLoader = new URLClassLoader(urls, Scala3CompilationProvider.class.getClassLoader());
            ClassLoader compilerLoader = new URLClassLoader(urls, libraryLoader);
            Option<String> actualVersion = Option.apply(SCALA_VERSION);
            return new sbt.internal.inc.ScalaInstance(
                    SCALA_VERSION,
                    compilerLoader,
                    compilerLoader,
                    libraryLoader,
                    scalaJars.toArray(File[]::new),
                    scalaJars.toArray(File[]::new),
                    scalaJars.toArray(File[]::new),
                    actualVersion);
        }

        private static File bridgeJar(Context context) {
            Optional<File> fromClasspath = context.getClasspath().stream()
                    .filter(File::isFile)
                    .filter(file -> file.getName().startsWith("scala3-sbt-bridge-"))
                    .findFirst();
            if (fromClasspath.isPresent()) {
                return fromClasspath.get();
            }
            try {
                URL location = Class.forName("xsbt.CompilerBridge", false,
                        Scala3CompilationProvider.class.getClassLoader())
                        .getProtectionDomain().getCodeSource().getLocation();
                return new File(location.toURI());
            } catch (Exception e) {
                throw new IllegalStateException("Missing Scala 3 Zinc compiler bridge", e);
            }
        }
    }

    private static final class Lookup implements PerClasspathEntryLookup {
        private final File outputDirectory;
        private final AnalysisStore analysisStore;

        private Lookup(File outputDirectory, AnalysisStore analysisStore) {
            this.outputDirectory = outputDirectory;
            this.analysisStore = analysisStore;
        }

        @Override
        public Optional<xsbti.compile.CompileAnalysis> analysis(VirtualFile classpathEntry) {
            if (classpathEntry instanceof PathBasedFile) {
                PathBasedFile pathBasedFile = (PathBasedFile) classpathEntry;
                if (pathBasedFile.toPath().toFile().equals(outputDirectory)) {
                    return analysisStore.get().map(AnalysisContents::getAnalysis);
                }
            }
            return Optional.empty();
        }

        @Override
        public xsbti.compile.DefinesClass definesClass(VirtualFile classpathEntry) {
            return name -> sbt.internal.inc.Locate.definesClass(classpathEntry).apply(name);
        }
    }

    private static final class ZincLogger implements xsbti.Logger {
        @Override
        public void error(Supplier<String> message) {
            LOG.error(message.get());
        }

        @Override
        public void warn(Supplier<String> message) {
            LOG.warn(message.get());
        }

        @Override
        public void info(Supplier<String> message) {
            LOG.info(message.get());
        }

        @Override
        public void debug(Supplier<String> message) {
            LOG.debug(message.get());
        }

        @Override
        public void trace(Supplier<Throwable> throwable) {
            LOG.trace(throwable.get());
        }
    }

    private static final class ZincReporter implements Reporter {
        private final ZincLogger logger;
        private final List<Problem> problems = new ArrayList<>();

        private ZincReporter(ZincLogger logger) {
            this.logger = logger;
        }

        @Override
        public void reset() {
            problems.clear();
        }

        @Override
        public boolean hasErrors() {
            return problems.stream().anyMatch(problem -> problem.severity() == Severity.Error);
        }

        @Override
        public boolean hasWarnings() {
            return problems.stream().anyMatch(problem -> problem.severity() == Severity.Warn);
        }

        @Override
        public void printSummary() {
            if (hasErrors()) {
                logger.error(() -> "Zinc reported " + problems.size() + " compilation problem(s)");
            }
        }

        @Override
        public Problem[] problems() {
            return problems.toArray(Problem[]::new);
        }

        @Override
        public void log(Problem problem) {
            problems.add(problem);
            final String baseMessage = problem.message();
            String message = baseMessage;
            if (problem.position() != null) {
                message = problem.position().sourcePath().map(path -> path + ": " + baseMessage).orElse(baseMessage);
            }
            final String renderedMessage = message;
            if (problem.severity() == Severity.Error) {
                logger.error(() -> renderedMessage);
            } else if (problem.severity() == Severity.Warn) {
                logger.warn(() -> renderedMessage);
            } else {
                logger.info(() -> renderedMessage);
            }
        }

        @Override
        public void comment(Position position, String message) {
            logger.info(() -> message);
        }
    }
}
