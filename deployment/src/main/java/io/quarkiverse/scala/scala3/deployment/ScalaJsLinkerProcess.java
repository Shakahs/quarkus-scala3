package io.quarkiverse.scala.scala3.deployment;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.jboss.logging.Logger;

import scala.Tuple2;
import scala.collection.Seq;
import scala.collection.immutable.List$;
import scala.concurrent.Await;
import scala.concurrent.Awaitable;
import scala.concurrent.ExecutionContext;
import scala.concurrent.duration.Duration$;
import scala.jdk.javaapi.CollectionConverters;

/** Invokes the existing Scala.js linker directly, keeping its linker instances stateful. */
final class ScalaJsLinkerProcess {

    private static final String STANDARD_CONFIG = "org.scalajs.linker.interface.StandardConfig$";
    private static final String ES_MODULE = "org.scalajs.linker.interface.ModuleKind$ESModule$";
    private static final String SMALL_MODULES_FOR = "org.scalajs.linker.interface.ModuleSplitStyle$SmallModulesFor$";
    private static final String MODULE_INITIALIZER = "org.scalajs.linker.interface.ModuleInitializer$";
    private static final String OUTPUT_PATTERNS = "org.scalajs.linker.interface.OutputPatterns$";
    private static final String LINKER = "org.scalajs.linker.interface.Linker";
    private static final String LOGGER = "org.scalajs.logging.Logger";

    enum LinkMode {
        FAST(false),
        FULL(true);

        private final boolean optimizer;

        LinkMode(boolean optimizer) {
            this.optimizer = optimizer;
        }
    }

    private final EnumMap<LinkMode, Object> linkers = new EnumMap<>(LinkMode.class);
    private final EnumMap<LinkMode, Object> irFileCaches = new EnumMap<>(LinkMode.class);
    private final EnumMap<LinkMode, Object> irCacheRuns = new EnumMap<>(LinkMode.class);

    synchronized void link(Set<File> classpath, File outputDirectory, String initializer, File sourceMapBase,
            LinkMode mode, Logger log) {
        try {
            Files.createDirectories(outputDirectory.toPath());
            ExecutionContext executionContext = ExecutionContext.global();
            List<Path> entries = classpath.stream()
                    .filter(file -> file.isDirectory() || file.getName().endsWith(".jar"))
                    .map(File::toPath)
                    .distinct()
                    .collect(Collectors.toList());
            Seq<Path> scalaEntries = CollectionConverters.asScala(entries).toSeq();
            Object discovered = invokeStatic("org.scalajs.linker.PathIRContainer", "fromClasspath", scalaEntries,
                    executionContext);
            Tuple2<?, ?> classpathResult = await(discovered);

            Object irFileCache = irFileCaches.computeIfAbsent(mode,
                    ignored -> invokeStatic("org.scalajs.linker.StandardImpl", "irFileCache"));
            Object irCacheRun = irCacheRuns.computeIfAbsent(mode,
                    ignored -> invoke(irFileCache, "newCache"));
            Object cachedIRFiles = invoke(irCacheRun, "cached", classpathResult._1(), executionContext);
            Seq<?> scalaIrFiles = await(cachedIRFiles);

            Object config = invokeStatic(STANDARD_CONFIG, "apply");
            config = invoke(config, "withModuleKind", staticField(ES_MODULE, "MODULE$"));
            Object modulePackages = List$.MODULE$.from(CollectionConverters.asScala(List.of("my.app")));
            Object moduleSplit = invoke(staticField(SMALL_MODULES_FOR, "MODULE$"), "apply", modulePackages);
            config = invoke(config, "withModuleSplitStyle", moduleSplit);
            config = invoke(config, "withOptimizer", mode.optimizer);
            config = invoke(config, "withBatchMode", mode == LinkMode.FULL);
            config = invoke(config, "withSourceMap", true);
            config = invoke(config, "withRelativizeSourceMapBase", scala.Option.apply(sourceMapBase.toURI()));
            config = invoke(config, "withOutputPatterns",
                    invokeStatic(OUTPUT_PATTERNS, "fromJSFile", "scala-js.js"));

            Object effectiveConfig = config;
            Object linker = linkers.computeIfAbsent(mode,
                    ignored -> invokeStatic("org.scalajs.linker.StandardImpl", "linker", effectiveConfig));
            Object initializers = initializers(initializer);
            Object output = invokeStatic("org.scalajs.linker.PathOutputDirectory", "apply",
                    outputDirectory.toPath());
            Method linkMethod = linkerMethod(linker.getClass());
            Object future = linkMethod.invoke(linker, scalaIrFiles, initializers, output,
                    linkerLogger(log), executionContext);
            await(future);
        } catch (Exception e) {
            log.error("Scala.js linker invocation failed", e);
            throw new IllegalStateException("Unable to link Scala.js output", e);
        }
    }

    synchronized void close() {
        irCacheRuns.values().forEach(cache -> invoke(cache, "free"));
        irCacheRuns.clear();
        irFileCaches.clear();
        linkers.clear();
    }

    private static Object initializers(String initializer) {
        if (initializer == null || initializer.isBlank()) {
            return List$.MODULE$.empty();
        }
        int separator = initializer.lastIndexOf('#');
        if (separator < 1 || separator == initializer.length() - 1) {
            throw new IllegalArgumentException(
                    "Scala.js initializer must use fully.qualified.Class#method syntax: " + initializer);
        }
        Object companion = staticField(MODULE_INITIALIZER, "MODULE$");
        Object moduleInitializer = invoke(companion, "mainMethod", initializer.substring(0, separator),
                initializer.substring(separator + 1));
        return List$.MODULE$.from(CollectionConverters.asScala(List.of(moduleInitializer)));
    }

    private static Method linkerMethod(Class<?> linkerClass) {
        for (Method method : linkerClass.getMethods()) {
            if (method.getName().equals("link") && method.getParameterCount() == 5
                    && method.getParameterTypes()[2].getName().endsWith("OutputDirectory")) {
                return method;
            }
        }
        throw new IllegalStateException("Scala.js linker does not expose the OutputDirectory link API");
    }

    private static Object linkerLogger(Logger log) {
        Class<?> loggerClass = load(LOGGER);
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals("log")) {
                Object level = args[0];
                String message = String.valueOf(applyFunction(args[1]));
                String levelName = String.valueOf(level);
                if (levelName.contains("Error")) {
                    log.error(message);
                } else if (levelName.contains("Warn")) {
                    log.warn(message);
                } else if (levelName.contains("Info")) {
                    log.info(message);
                } else {
                    log.debug(message);
                }
                return null;
            }
            if (method.getName().equals("trace")) {
                log.trace((Throwable) applyFunction(args[0]));
                return null;
            }
            if (method.getName().equals("timeFuture") || method.getName().equals("time")) {
                if (args.length > 1 && args[1] != null) {
                    return applyFunction(args[1]);
                }
                return null;
            }
            if (method.getName().equals("toString")) {
                return "Quarkus Scala.js linker logger";
            }
            return null;
        };
        return Proxy.newProxyInstance(loggerClass.getClassLoader(), new Class<?>[] { loggerClass }, handler);
    }

    private static Object applyFunction(Object function) {
        try {
            return scala.Function0.class.getMethod("apply").invoke(function);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to invoke Scala.js linker callback", e);
        }
    }

    private static Object staticField(String className, String fieldName) {
        try {
            return load(className).getField(fieldName).get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to access Scala.js linker value " + className + "." + fieldName,
                    e);
        }
    }

    private static Object invokeStatic(String className, String methodName, Object... args) {
        Class<?> type = load(className);
        for (Method method : type.getMethods()) {
            if (method.getName().equals(methodName) && method.getParameterCount() == args.length
                    && compatible(method.getParameterTypes(), args)) {
                try {
                    Object receiver = Modifier.isStatic(method.getModifiers()) ? null : staticField(className, "MODULE$");
                    return method.invoke(receiver, args);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Unable to invoke " + type.getName() + "." + methodName, e);
                }
            }
        }
        throw new IllegalStateException("Unable to find " + type.getName() + "." + methodName);
    }

    private static Object invoke(Object receiver, String methodName, Object... args) {
        return invoke(receiver, receiver.getClass(), methodName, args);
    }

    private static Object invoke(Object receiver, Class<?> type, String methodName, Object... args) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(methodName) && method.getParameterCount() == args.length
                    && compatible(method.getParameterTypes(), args)) {
                try {
                    return method.invoke(receiver, args);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Unable to invoke " + type.getName() + "." + methodName, e);
                }
            }
        }
        throw new IllegalStateException("Unable to find " + type.getName() + "." + methodName);
    }

    private static boolean compatible(Class<?>[] parameterTypes, Object[] args) {
        for (int i = 0; i < parameterTypes.length; i++) {
            if (args[i] == null) {
                continue;
            }
            Class<?> parameter = box(parameterTypes[i]);
            if (!parameter.isAssignableFrom(args[i].getClass())) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        return type;
    }

    private static Class<?> load(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Missing Scala.js linker class " + className, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T await(Object future) throws Exception {
        return (T) Await.result((Awaitable<T>) future,
                Duration$.MODULE$.create(10, TimeUnit.MINUTES));
    }
}
