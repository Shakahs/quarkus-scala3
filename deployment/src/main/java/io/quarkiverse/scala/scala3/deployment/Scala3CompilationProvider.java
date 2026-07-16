package io.quarkiverse.scala.scala3.deployment;

import java.io.File;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.jboss.logging.Logger;

import io.quarkus.deployment.dev.CompilationProvider;
import sbt.internal.inc.CompileOutput;
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
import xsbti.compile.MultipleOutput;
import xsbti.compile.PerClasspathEntryLookup;
import xsbti.compile.PreviousResult;
import xsbti.compile.Setup;

/** Coordinates the JVM and Scala.js Zinc targets for one Quarkus project. */
public final class Scala3CompilationProvider implements CompilationProvider {

    private static final Logger LOG = Logger.getLogger(Scala3CompilationProvider.class);
    private static final String SCALA_VERSION = "3.8.4";
    private static final Set<String> HANDLED_EXTENSIONS = Set.of(".scala", ".java");
    private static final String COMPILER_ARGS_ENV_VAR = "QUARKUS_SCALA3_COMPILER_ARGS";
    private static final String SCALAJS_ENABLED_ENV_VAR = "QUARKUS_SCALA3_SCALAJS";
    private static final String SCALAJS_INITIALIZER_ENV_VAR = "QUARKUS_SCALA3_SCALAJS_INITIALIZER";

    private final Map<String, ProjectState> states = new HashMap<>();

    @Override
    public Set<String> handledExtensions() {
        return HANDLED_EXTENSIONS;
    }

    @Override
    public synchronized void compile(Set<File> changedFiles, Context context) {
        if (context == null) {
            throw new IllegalStateException("Quarkus supplied no compilation context");
        }

        ProjectSources sources = findSources(context);
        if (sources.jvmSources.isEmpty() && sources.scalaJsSources.isEmpty()) {
            return;
        }

        String stateKey = context.getOutputDirectory().getAbsolutePath();
        ProjectState state = states.computeIfAbsent(stateKey, ignored -> createState(context, sources.scalaJsEnabled));
        state.compile(sources, context);
    }

    @Override
    public synchronized void close() throws IOException {
        states.values().forEach(ProjectState::close);
        states.clear();
    }

    private ProjectState createState(Context context, boolean scalaJsEnabled) {
        try {
            return new ProjectState(context, scalaJsEnabled);
        } catch (Exception e) {
            LOG.error("Unable to initialize the Scala 3 Zinc compiler", e);
            throw new IllegalStateException("Unable to initialize the Scala 3 Zinc compiler", e);
        }
    }

    private static ProjectSources findSources(Context context) {
        boolean testSources = context.getOutputDirectory().getName().equals("test-classes");
        return discoverSources(context.getProjectDirectory(), context.getSourceDirectory(),
                context.getGeneratedSourcesDirectory(), testSources);
    }

    private static boolean isUnder(Path file, Path directory) {
        return file.toAbsolutePath().normalize().startsWith(directory);
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

    static List<File> scalaJsSources(File sourceDirectory) {
        return discoverSources(null, sourceDirectory, null, false).scalaJsSources;
    }

    static List<File> scalaJsSources(File projectDirectory, File sourceDirectory, boolean testSources) {
        return discoverSources(projectDirectory, sourceDirectory, null, testSources).scalaJsSources;
    }

    static List<String> scalaJsMainClasses(List<File> sources) {
        List<String> mainClasses = new ArrayList<>();
        for (File source : sources) {
            if (!source.getName().endsWith(".scala")) {
                continue;
            }
            try {
                String sourceText = Files.readString(source.toPath());
                String packageName = scalaPackage(sourceText);
                Matcher mainMatcher = Pattern.compile("(?s)@main(?:\\s*\\([^)]*\\))?\\s*def\\s+([A-Za-z_]\\w*)")
                        .matcher(sourceText);
                while (mainMatcher.find()) {
                    String className = mainMatcher.group(1);
                    mainClasses.add(packageName.isEmpty() ? className : packageName + "." + className);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Unable to inspect Scala.js source " + source + " for @main", e);
            }
        }
        return mainClasses;
    }

    static List<String> scalaJsApplicationPackages(List<File> sources) {
        Set<String> packages = new LinkedHashSet<>();
        for (File source : sources) {
            if (!source.getName().endsWith(".scala")) {
                continue;
            }
            try {
                String packageName = scalaPackage(Files.readString(source.toPath()));
                if (!packageName.isEmpty()) {
                    int separator = packageName.indexOf('.');
                    packages.add(separator < 0 ? packageName : packageName.substring(0, separator));
                }
            } catch (IOException e) {
                throw new IllegalStateException("Unable to inspect Scala.js source " + source + " for its package", e);
            }
        }
        return List.copyOf(packages);
    }

    private static String scalaPackage(String sourceText) {
        Matcher packageMatcher = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)").matcher(sourceText);
        return packageMatcher.find() ? packageMatcher.group(1) : "";
    }

    static File sourceSetDirectory(File projectDirectory, File sourceDirectory, boolean testSources) {
        if (projectDirectory != null) {
            File sourceSet = new File(projectDirectory, testSources ? "src/test" : "src/main");
            if (sourceSet.isDirectory()) {
                return sourceSet;
            }
        }
        if (sourceDirectory != null) {
            File parent = sourceDirectory.getParentFile();
            if (parent != null && (sourceDirectory.getName().equals("java") || sourceDirectory.getName().equals("scala"))
                    && (parent.getName().equals("main") || parent.getName().equals("test"))) {
                return parent;
            }
        }
        return sourceDirectory;
    }

    private static ProjectSources discoverSources(File projectDirectory, File configuredSourceDirectory,
            File generatedSourcesDirectory, boolean testSources) {
        File sourceSet = sourceSetDirectory(projectDirectory, configuredSourceDirectory, testSources);
        File primarySourceDirectory = defaultSourceDirectory(projectDirectory, configuredSourceDirectory, testSources)
                ? sourceSet
                : configuredSourceDirectory;
        LinkedHashSet<File> roots = new LinkedHashSet<>();
        addDirectory(roots, primarySourceDirectory);
        addDirectory(roots, generatedSourcesDirectory);

        List<Path> scalaJsRoots = sourceRoots(sourceSet, "scalajs");
        List<Path> sharedRoots = sourceRoots(sourceSet, "shared");
        scalaJsRoots.forEach(path -> addDirectory(roots, path.toFile()));
        sharedRoots.forEach(path -> addDirectory(roots, path.toFile()));

        List<File> allSources = roots.stream()
                .flatMap(root -> sourcesUnder(root).stream())
                .distinct()
                .sorted(Comparator.comparing(File::getAbsolutePath))
                .collect(Collectors.toList());
        if (allSources.isEmpty()) {
            return ProjectSources.empty();
        }

        List<File> jvmSources = allSources.stream()
                .filter(file -> scalaJsRoots.stream().noneMatch(root -> isUnder(file.toPath(), root)))
                .collect(Collectors.toList());
        List<File> scalaJsSources = allSources.stream()
                .filter(file -> file.getName().endsWith(".scala"))
                .filter(file -> scalaJsRoots.stream().anyMatch(root -> isUnder(file.toPath(), root))
                        || sharedRoots.stream().anyMatch(root -> isUnder(file.toPath(), root)))
                .collect(Collectors.toList());
        boolean explicitlyEnabled = Boolean.parseBoolean(System.getenv(SCALAJS_ENABLED_ENV_VAR));
        return new ProjectSources(jvmSources, scalaJsSources, explicitlyEnabled || !scalaJsSources.isEmpty());
    }

    private static boolean defaultSourceDirectory(File projectDirectory, File sourceDirectory, boolean testSources) {
        if (projectDirectory == null || sourceDirectory == null) {
            return false;
        }
        Path configured = sourceDirectory.toPath().toAbsolutePath().normalize();
        Path javaRoot = new File(projectDirectory, testSources ? "src/test/java" : "src/main/java").toPath()
                .toAbsolutePath().normalize();
        Path scalaRoot = new File(projectDirectory, testSources ? "src/test/scala" : "src/main/scala").toPath()
                .toAbsolutePath().normalize();
        return configured.equals(javaRoot) || configured.equals(scalaRoot);
    }

    private static List<Path> sourceRoots(File sourceSet, String name) {
        if (sourceSet == null) {
            return Collections.emptyList();
        }
        Path root = sourceSet.toPath().toAbsolutePath().normalize();
        return List.of(root.resolve(name), root.resolve("java").resolve(name), root.resolve("scala").resolve(name));
    }

    private static void addDirectory(Set<File> roots, File directory) {
        if (directory != null && directory.isDirectory()) {
            roots.add(directory);
        }
    }

    private static List<File> sourcesUnder(File directory) {
        try (var stream = Files.walk(directory.toPath())) {
            return stream.filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .filter(file -> file.getName().endsWith(".scala") || file.getName().endsWith(".java"))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to enumerate sources under " + directory, e);
        }
    }

    static void compileScalaJsForRelease(List<File> sources, File sourceRoot, File classesDirectory,
            File outputDirectory, Set<File> classpath, String release) {
        if (sources.isEmpty()) {
            return;
        }
        try {
            Set<File> compilerClasspath = compilerClasspath(scalaJsMavenClasspath(classpath));
            Map<File, AnalysisStore> analyses = new HashMap<>();
            File cacheFile = new File(outputDirectory.getParentFile(), "analysis/scalajs-release");
            Target target = new Target(new CompilerEnvironment(compilerClasspath, true), compilerClasspath, true,
                    classesDirectory, cacheFile, analyses, sourceRoot,
                    ProjectState.outputs(sourceRoot, outputDirectory, classesDirectory));
            target.compile(sources, outputDirectory, Charset.defaultCharset(), release);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to compile Scala.js sources for the Quarkus release build", e);
        }
    }

    static List<Path> linkedOutputFiles(File linkerOutput) {
        if (linkerOutput == null || !linkerOutput.isDirectory()) {
            return Collections.emptyList();
        }
        try (var stream = Files.walk(linkerOutput.toPath())) {
            return stream.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to enumerate Scala.js linker output " + linkerOutput, e);
        }
    }

    static void publishWebResources(File linkerOutput, File classesDirectory) {
        Path resourceDirectory = classesDirectory.toPath().resolve("META-INF/resources/scala-js");
        try {
            if (Files.exists(resourceDirectory)) {
                try (var stream = Files.walk(resourceDirectory)) {
                    for (Path path : stream.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                        Files.delete(path);
                    }
                }
            }
            Files.createDirectories(resourceDirectory);
            Path outputRoot = linkerOutput.toPath();
            for (Path source : linkedOutputFiles(linkerOutput)) {
                Path destination = resourceDirectory.resolve(outputRoot.relativize(source));
                Files.createDirectories(destination.getParent());
                Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to publish Scala.js web resources", e);
        }
    }

    private static final class ProjectSources {
        private final List<File> jvmSources;
        private final List<File> scalaJsSources;
        private final boolean scalaJsEnabled;

        private ProjectSources(List<File> jvmSources, List<File> scalaJsSources, boolean scalaJsEnabled) {
            this.jvmSources = jvmSources;
            this.scalaJsSources = scalaJsSources;
            this.scalaJsEnabled = scalaJsEnabled;
        }

        private static ProjectSources empty() {
            return new ProjectSources(Collections.emptyList(), Collections.emptyList(), false);
        }
    }

    private static final class ProjectState {
        private final Set<File> jvmCompilerClasspath;
        private final Set<File> scalaJsCompilerClasspath;
        private final Map<File, AnalysisStore> analyses = new HashMap<>();
        private final MultipleOutput outputs;
        private final Target jvmTarget;
        private final Target scalaJsTarget;
        private final ScalaJsLinkerProcess linker = new ScalaJsLinkerProcess();

        private ProjectState(Context context, boolean scalaJsEnabled) throws Exception {
            this.jvmCompilerClasspath = compilerClasspath(context);
            this.scalaJsCompilerClasspath = compilerClasspath(scalaJsMavenClasspath(context.getClasspath()));
            this.outputs = outputs(context.getSourceDirectory(), context.getOutputDirectory(),
                    targetDirectory(context, "scalajs-classes"));
            this.jvmTarget = new Target(new CompilerEnvironment(jvmCompilerClasspath, false), jvmCompilerClasspath, false,
                    context.getOutputDirectory(), analysisFile(context.getOutputDirectory(), "jvm"), analyses,
                    context.getSourceDirectory(), outputs);
            this.scalaJsTarget = new Target(new CompilerEnvironment(scalaJsCompilerClasspath, true), scalaJsCompilerClasspath,
                    true,
                    targetDirectory(context, "scalajs-classes"), analysisFile(context.getOutputDirectory(), "scalajs"),
                    analyses, context.getSourceDirectory(), outputs);
        }

        private static MultipleOutput outputs(File sourceRoot, File jvmOutput, File scalaJsOutput) {
            File scalaJsSourceRoot = new File(sourceRoot, "scalajs");
            return (MultipleOutput) CompileOutput.apply(new xsbti.compile.OutputGroup[] {
                    CompileOutput.outputGroup(sourceRoot.toPath(), jvmOutput.toPath()),
                    CompileOutput.outputGroup(scalaJsSourceRoot.toPath(),
                            scalaJsOutput.toPath())
            });
        }

        private void compile(ProjectSources sources, Context context) {
            File scalaJsOutput = targetDirectory(context, "scalajs-classes");
            File linkerOutput = targetDirectory(context,
                    context.getOutputDirectory().getName().equals("test-classes") ? "scalajs-test" : "scalajs");
            String initializer = System.getenv(SCALAJS_INITIALIZER_ENV_VAR);
            jvmTarget.compile(sources.jvmSources, context);
            scalaJsTarget.compile(sources.scalaJsSources, context);
            if (!sources.scalaJsSources.isEmpty()) {
                Set<File> linkerClasspath = new LinkedHashSet<>(scalaJsCompilerClasspath);
                linkerClasspath.add(context.getOutputDirectory());
                linkerClasspath.add(scalaJsOutput);
                linker.link(linkerClasspath, linkerOutput, scalaJsApplicationPackages(sources.scalaJsSources),
                        scalaJsMainClasses(sources.scalaJsSources), initializer,
                        context.getProjectDirectory(),
                        ScalaJsLinkerProcess.LinkMode.FAST, LOG);
                publishWebResource(linkerOutput, context.getOutputDirectory());
            }
        }

        private void close() {
            linker.close();
        }

        private static void publishWebResource(File linkerOutput, File classesDirectory) {
            Scala3CompilationProvider.publishWebResources(linkerOutput, classesDirectory);
        }
    }

    private static final class CompilerEnvironment {
        private final sbt.internal.inc.ScalaInstance scalaInstance;
        private final Compilers compilers;
        private final IncrementalCompiler compiler;

        private CompilerEnvironment(Set<File> compilerClasspath, boolean scalaJs) throws Exception {
            List<File> scalaJars = scalaJars(compilerClasspath, scalaJs);
            File compilerJar = requiredJar(scalaJars, "scala3-compiler_3-");
            File bridgeJar = bridgeJar(compilerClasspath);
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
            LOG.debugf("Initialized shared Zinc environment for Scala %s using %s and %s", SCALA_VERSION,
                    compilerJar, bridgeJar);
        }
    }

    private static final class Target {
        private final CompilerEnvironment environment;
        private final File outputDirectory;
        private final AnalysisStore analysisStore;
        private final IncrementalCompiler compiler;
        private final Compilers compilers;
        private final Setup setup;
        private final Set<File> compilerClasspath;
        private final boolean scalaJs;
        private final MultipleOutput outputs;

        private Target(CompilerEnvironment environment, Set<File> compilerClasspath, boolean scalaJs,
                File outputDirectory, File cacheFile, Map<File, AnalysisStore> analyses, File sourceRoot,
                MultipleOutput outputs)
                throws IOException {
            this.environment = environment;
            this.compilerClasspath = compilerClasspath;
            this.outputDirectory = outputDirectory;
            this.scalaJs = scalaJs;
            this.outputs = outputs;
            File analysisDirectory = cacheFile.getParentFile();
            if (!analysisDirectory.isDirectory() && !analysisDirectory.mkdirs()) {
                throw new IOException("Unable to create Zinc analysis directory " + analysisDirectory);
            }
            this.analysisStore = AnalysisStore.getCachedStore(
                    sbt.internal.inc.FileAnalysisStore.binary(cacheFile));
            this.compiler = environment.compiler;
            this.compilers = environment.compilers;
            File effectiveSourceRoot = sourceRoot == null ? outputDirectory : sourceRoot;
            this.setup = Setup.of(
                    new Lookup(analyses),
                    false,
                    cacheFile,
                    CompilerCache.fresh(),
                    IncOptions.of(),
                    new ZincReporter(new ZincLogger()),
                    Optional.empty(),
                    new xsbti.T2[0]);
            this.sourceRoot = effectiveSourceRoot;
        }

        private final File sourceRoot;

        private void compile(List<File> sources, Context context) {
            if (sources.isEmpty()) {
                return;
            }
            if (!outputDirectory.isDirectory() && !outputDirectory.mkdirs()) {
                throw new IllegalStateException("Unable to create compiler output directory " + outputDirectory);
            }

            compile(sources, context.getOutputDirectory(), context.getSourceEncoding(), context.getReleaseJavaVersion());
        }

        private void compile(List<File> sources, File dependentOutput, Charset sourceEncoding, String release) {
            if (sources.isEmpty()) {
                return;
            }
            if (!outputDirectory.isDirectory() && !outputDirectory.mkdirs()) {
                throw new IllegalStateException("Unable to create compiler output directory " + outputDirectory);
            }

            List<File> classpath = new ArrayList<>(compilerClasspath);
            classpath.removeIf(file -> isIncompatibleScalaLibrary(file, scalaJs));
            if (scalaJs && dependentOutput != null && !classpath.contains(dependentOutput)) {
                classpath.add(dependentOutput);
            }
            if (!classpath.contains(outputDirectory)) {
                classpath.add(outputDirectory);
            }

            VirtualFile[] classpathFiles = classpath.stream()
                    .map(file -> new PlainVirtualFile(file.toPath()))
                    .toArray(VirtualFile[]::new);
            VirtualFile[] sourceFiles = sources.stream()
                    .map(file -> new PlainVirtualFile(file.toPath()))
                    .toArray(VirtualFile[]::new);

            List<String> scalacOptions = new ArrayList<>();
            if (scalaJs) {
                scalacOptions.add("-scalajs");
            }
            scalacOptions.addAll(compilerArgs());
            scalacOptions.add("-encoding");
            scalacOptions.add(sourceEncoding.name());
            List<String> javacOptions = new ArrayList<>();
            if (release != null && !release.isBlank()) {
                javacOptions.add("--release");
                javacOptions.add(release);
            }

            CompileOptions options = CompileOptions.of(
                    classpathFiles,
                    sourceFiles,
                    outputDirectory.toPath(),
                    scalacOptions.toArray(String[]::new),
                    javacOptions.toArray(String[]::new),
                    100,
                    position -> position,
                    CompileOrder.Mixed,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(outputs));

            try {
                Thread thread = Thread.currentThread();
                ClassLoader previousClassLoader = thread.getContextClassLoader();
                thread.setContextClassLoader(environment.scalaInstance.loader());
                try {
                    CompileResult result = compiler.compile(
                            Inputs.of(compilers, options, setup, previousResult()),
                            new ZincLogger());
                    analysisStore.set(AnalysisContents.create(result.analysis(), result.setup()));
                } finally {
                    thread.setContextClassLoader(previousClassLoader);
                }
            } catch (xsbti.CompileFailed e) {
                throw new IllegalStateException(
                        (scalaJs ? "Scala.js" : "Scala") + "/Java incremental compilation failed", e);
            }
        }

        private PreviousResult previousResult() {
            return analysisStore.get()
                    .map(contents -> PreviousResult.of(contents.getAnalysis(), contents.getMiniSetup()))
                    .orElseGet(() -> PreviousResult.of(Optional.empty(), Optional.empty()));
        }
    }

    private static File analysisFile(File outputDirectory, String name) {
        return new File(new File(outputDirectory.getParentFile(), "analysis"), name);
    }

    private static File targetDirectory(Context context, String name) {
        return new File(context.getOutputDirectory().getParentFile(), name);
    }

    private static Set<File> compilerClasspath(Context context) {
        return compilerClasspath(new LinkedHashSet<>(context.getClasspath()));
    }

    static Set<File> compilerClasspath(Set<File> classpath) {
        classpath = new LinkedHashSet<>(classpath);
        addCodeSource(classpath, dotty.tools.dotc.Compiler.class);
        addCodeSource(classpath, dotty.tools.xsbt.CompilerBridge.class);
        addCodeSource(classpath, sbt.internal.inc.ZincUtil.class);
        addCodeSource(classpath, scala.Option.class);
        addCodeSource(classpath, scala.tools.asm.ClassVisitor.class);
        addCodeSource(classpath, dotty.tools.tasty.TastyReader.class);
        addCodeSource(classpath, scala.scalajs.LinkingInfo.class);
        addCodeSource(classpath, scala.scalajs.js.Any.class);
        addResource(classpath, "java/lang/Object.sjsir");
        addResource(classpath, "scala/Product.sjsir");
        return classpath;
    }

    /**
     * Converts Maven Scala cross-published artifacts to their Scala.js sibling paths.
     * The derived path is intentionally not checked: Zinc and the Scala.js linker report a
     * missing or incompatible Scala.js artifact at the point it is actually required.
     */
    static Set<File> scalaJsMavenClasspath(Set<File> classpath) {
        Set<File> scalaJsClasspath = new LinkedHashSet<>();
        for (File artifact : classpath) {
            scalaJsClasspath.add(scalaJsMavenArtifact(artifact));
        }
        return scalaJsClasspath;
    }

    private static File scalaJsMavenArtifact(File artifact) {
        if (!artifact.isFile() || !artifact.getName().endsWith(".jar")) {
            return artifact;
        }
        File versionDirectory = artifact.getParentFile();
        File artifactDirectory = versionDirectory == null ? null : versionDirectory.getParentFile();
        if (artifactDirectory == null) {
            return artifact;
        }
        String artifactId = artifactDirectory.getName();
        if (artifactId.startsWith("scalajs-") || artifactId.matches(".*_sjs\\d+_.*")) {
            return artifact;
        }
        Matcher crossVersion = Pattern.compile("^(.+)_(\\d+(?:\\.\\d+)*)$").matcher(artifactId);
        if (!crossVersion.matches()) {
            return artifact;
        }
        String scalaJsArtifactId = crossVersion.group(1) + "_sjs1_" + crossVersion.group(2);
        String fileName = artifact.getName();
        if (!fileName.startsWith(artifactId + "-")) {
            return artifact;
        }
        File repositoryPath = artifactDirectory.getParentFile();
        File scalaJsVersionDirectory = new File(new File(repositoryPath, scalaJsArtifactId), versionDirectory.getName());
        return new File(scalaJsVersionDirectory, scalaJsArtifactId + fileName.substring(artifactId.length()));
    }

    private static void addCodeSource(Set<File> classpath, Class<?> type) {
        try {
            URL location = type.getProtectionDomain().getCodeSource().getLocation();
            classpath.add(new File(location.toURI()));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to locate dependency for " + type.getName(), e);
        }
    }

    private static void addResource(Set<File> classpath, String resource) {
        try {
            URL location = Scala3CompilationProvider.class.getClassLoader().getResource(resource);
            if (location == null) {
                return;
            }
            if ("jar".equals(location.getProtocol())) {
                classpath.add(new File(((JarURLConnection) location.openConnection()).getJarFileURL().toURI()));
            } else if ("file".equals(location.getProtocol())) {
                classpath.add(new File(location.toURI()).toPath().resolve(resource).getParent().getParent()
                        .getParent().getParent().toFile());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to locate dependency resource " + resource, e);
        }
    }

    private static boolean isIncompatibleScalaLibrary(File file, boolean scalaJs) {
        String name = file.getName();
        if (name.startsWith("scala-library-2.")) {
            return true;
        }
        if (name.startsWith("scala3-library_sjs1_3-")) {
            return !scalaJs;
        }
        return name.startsWith("scala3-library_3-") && scalaJs;
    }

    private static List<File> scalaJars(Set<File> classpath, boolean scalaJs) {
        return classpath.stream()
                .filter(File::isFile)
                .filter(file -> {
                    String name = file.getName();
                    boolean targetLibrary = scalaJs ? name.startsWith("scala3-library_sjs1_3-")
                            : name.startsWith("scala3-library_3-");
                    return name.startsWith("scala3-compiler_3-") || targetLibrary
                            || name.startsWith("scala-library-" + SCALA_VERSION)
                            || name.startsWith("scala-asm-") || name.startsWith("tasty-core-");
                })
                .sorted(Comparator.comparing(File::getAbsolutePath))
                .collect(Collectors.toList());
    }

    private static File requiredJar(Iterable<File> files, String prefix) {
        List<File> candidates = new ArrayList<>();
        for (File file : files) {
            if (file.isFile() && file.getName().startsWith(prefix)) {
                candidates.add(file);
            }
        }
        return candidates.stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing Scala compiler dependency " + prefix));
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

    private static File bridgeJar(Set<File> compilerClasspath) {
        Optional<File> fromClasspath = compilerClasspath.stream()
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

    private static final class Lookup implements PerClasspathEntryLookup {
        private final Map<File, AnalysisStore> analyses;

        private Lookup(Map<File, AnalysisStore> analyses) {
            this.analyses = analyses;
        }

        @Override
        public Optional<xsbti.compile.CompileAnalysis> analysis(VirtualFile classpathEntry) {
            if (classpathEntry instanceof PathBasedFile) {
                File file = ((PathBasedFile) classpathEntry).toPath().toFile();
                for (Map.Entry<File, AnalysisStore> entry : analyses.entrySet()) {
                    if (entry.getKey().equals(file)) {
                        return entry.getValue().get().map(AnalysisContents::getAnalysis);
                    }
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
            String message = problem.message();
            if (problem.position() != null) {
                String originalMessage = message;
                message = problem.position().sourcePath().map(path -> path + ": " + originalMessage)
                        .orElse(originalMessage);
            }
            String renderedMessage = message;
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
