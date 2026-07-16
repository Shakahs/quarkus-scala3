package io.quarkiverse.scala.scala3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkiverse.scala.scala3.deployment.Scala3CompilationProvider;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.builditem.GeneratedFileSystemResourceBuildItem;
import io.quarkus.deployment.builditem.GeneratedResourceBuildItem;
import io.quarkus.deployment.dev.CompilationProvider.Context;
import io.quarkus.vertx.http.deployment.spi.GeneratedStaticResourceBuildItem;

public class Scala3CompilationProviderSourceRootsTest {

    @Test
    void compilesGeneratedJavaSources(@TempDir Path project) throws Exception {
        Path sourceDirectory = project.resolve("src/main/java");
        Path generatedSources = project.resolve("target/generated-sources/annotations");
        Path generatedSource = generatedSources.resolve("generated/GeneratedValue.java");
        writeJava(generatedSource, "generated", "GeneratedValue");

        Scala3CompilationProvider provider = new Scala3CompilationProvider();
        try {
            Path classes = project.resolve("target/classes");
            compile(provider, project, sourceDirectory, generatedSources, classes);
            assertTrue(Files.isRegularFile(classes.resolve("generated/GeneratedValue.class")),
                    "Zinc must compile Java sources supplied by Context.getGeneratedSourcesDirectory()");
        } finally {
            provider.close();
        }
    }

    @Test
    void preservesQuarkusConfiguredSourceDirectory(@TempDir Path project) throws Exception {
        Path conventionalSourceRoot = project.resolve("src/main/java");
        Files.createDirectories(conventionalSourceRoot);
        Path configuredSourceDirectory = project.resolve("src/custom/scala");
        Path configuredSource = configuredSourceDirectory.resolve("configured/ConfiguredSource.scala");
        Files.createDirectories(configuredSource.getParent());
        Files.writeString(configuredSource,
                "package configured\n\nobject ConfiguredSource { def value: String = \"configured\" }\n",
                StandardCharsets.UTF_8);

        Scala3CompilationProvider provider = new Scala3CompilationProvider();
        try {
            Path classes = project.resolve("target/classes");
            compile(provider, project, configuredSourceDirectory, project.resolve("target/generated-sources"), classes);
            assertTrue(Files.isRegularFile(classes.resolve("configured/ConfiguredSource.class")),
                    "Zinc must retain Context.getSourceDirectory() for non-default Maven source roots");
        } finally {
            provider.close();
        }
    }

    @Test
    void usesConfiguredSourceSetForScalaJsRoots(@TempDir Path project) throws Exception {
        Path configuredScalaRoot = project.resolve("src/custom/scala");
        Path configuredScalaJs = project.resolve("src/custom/scalajs/ConfiguredFrontend.scala");
        Path defaultScalaJs = project.resolve("src/main/scalajs/DefaultFrontend.scala");
        Files.createDirectories(configuredScalaRoot);
        Files.createDirectories(configuredScalaJs.getParent());
        Files.createDirectories(defaultScalaJs.getParent());
        Files.writeString(configuredScalaJs, "object ConfiguredFrontend\n", StandardCharsets.UTF_8);
        Files.writeString(defaultScalaJs, "object DefaultFrontend\n", StandardCharsets.UTF_8);

        assertEquals(List.of(configuredScalaJs.toFile()),
                invokeProvider("scalaJsSources", new Class<?>[] { File.class, File.class, boolean.class },
                        project.toFile(), configuredScalaRoot.toFile(), false),
                "an explicit source root must override the default src/main Scala.js source set");
    }

    @Test
    void publishesEveryScalaJsModuleSplitOutput(@TempDir Path project) throws Exception {
        Path linkOutput = project.resolve("target/scalajs");
        Files.createDirectories(linkOutput);
        Files.writeString(linkOutput.resolve("scala-js.js"), "export {};\n", StandardCharsets.UTF_8);
        Files.writeString(linkOutput.resolve("scala-js.js.map"), "{}\n", StandardCharsets.UTF_8);
        Files.writeString(linkOutput.resolve("scala-js-first.js"), "export const first = 1;\n", StandardCharsets.UTF_8);
        Files.writeString(linkOutput.resolve("scala-js-first.js.map"), "{}\n", StandardCharsets.UTF_8);

        Path releaseClasses = project.resolve("target/release-classes");
        invokeReleasePublisher(linkOutput, releaseClasses);
        Path devClasses = project.resolve("target/dev-classes");
        invokeDevPublisher(linkOutput, devClasses);

        String splitModule = "scala-js-first.js";
        assertTrue(Files.isRegularFile(releaseClasses.resolve("META-INF/resources/scala-js").resolve(splitModule))
                && Files.isRegularFile(devClasses.resolve("META-INF/resources/scala-js").resolve(splitModule)),
                "Both Scala.js publishing paths must retain module-split output " + splitModule);
    }

    @Test
    void derivesModuleSplitPackagesFromScalaJsSources(@TempDir Path project) throws Exception {
        Path source = project.resolve("src/main/scalajs/application/frontend/Main.scala");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package application.frontend\n\nobject Main\n", StandardCharsets.UTF_8);

        List<String> packages = invokeProvider("scalaJsApplicationPackages", new Class<?>[] { List.class },
                List.of(source.toFile()));
        assertEquals(List.of("application"), packages,
                "the application package must be inferred from its Scala.js source rather than hard-coded");

        Class<?> linker = Class.forName("io.quarkiverse.scala.scala3.deployment.ScalaJsLinkerProcess");
        Method moduleSplitStyle = linker.getDeclaredMethod("moduleSplitStyle", List.class);
        moduleSplitStyle.setAccessible(true);
        Object splitStyle = moduleSplitStyle.invoke(null, packages);
        assertEquals("List(application)", splitStyle.getClass().getMethod("packages").invoke(splitStyle).toString(),
                "the linker must apply SmallModulesFor to the inferred application package");
    }

    @Test
    void derivesScalaJsCrossPublishedMavenArtifactsWithoutRepositoryLookup(@TempDir Path repository)
            throws Exception {
        Path jvmArtifact = repository.resolve("com/example/widget_3/1.0.0/widget_3-1.0.0.jar");
        Files.createDirectories(jvmArtifact.getParent());
        writeJar(jvmArtifact, "com/example/widget/Widget.class");
        Path unusedJvmArtifact = repository.resolve("com/example/unused_3/1.0.0/unused_3-1.0.0.jar");
        Files.createDirectories(unusedJvmArtifact.getParent());
        writeJar(unusedJvmArtifact, "com/example/unused/Unused.class");
        Path javaArtifact = repository.resolve("com/example/java-widget/1.0.0/java-widget-1.0.0.jar");
        Files.createDirectories(javaArtifact.getParent());
        writeJar(javaArtifact, "com/example/javawidget/JavaWidget.class");
        Path source = repository.resolve("src/main/scalajs/Frontend.scala");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "import com.example.widget.Widget\n\nobject Frontend\n", StandardCharsets.UTF_8);

        Set<File> scalaJsClasspath = invokeProvider("scalaJsMavenClasspath", new Class<?>[] { Set.class, List.class },
                Set.of(jvmArtifact.toFile(), unusedJvmArtifact.toFile(), javaArtifact.toFile()), List.of(source.toFile()));

        assertTrue(scalaJsClasspath.contains(
                repository.resolve("com/example/widget_sjs1_3/1.0.0/widget_sjs1_3-1.0.0.jar").toFile()),
                "Scala.js must derive the cross-published artifact path locally, without querying Maven");
        assertTrue(scalaJsClasspath.contains(javaArtifact.toFile()),
                "ordinary Java artifacts must remain on the Scala.js classpath");
        assertTrue(scalaJsClasspath.contains(unusedJvmArtifact.toFile()),
                "an unused JVM-only Scala dependency must not be replaced preemptively");
        assertTrue(!scalaJsClasspath.contains(jvmArtifact.toFile()),
                "the JVM Scala artifact must be replaced so a missing Scala.js variant fails compilation clearly");
    }

    private static void compile(Scala3CompilationProvider provider, Path project, Path sourceDirectory,
            Path generatedSources, Path classes) {
        Set<File> classpath = new HashSet<>();
        classpath.addAll(Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(File::new)
                .collect(Collectors.toSet()));
        provider.compile(Set.of(), new Context(
                "source-roots",
                classpath,
                classpath,
                project.toFile(),
                sourceDirectory.toFile(),
                classes.toFile(),
                "UTF-8",
                Map.of(),
                "17",
                "17",
                "17",
                List.of(),
                List.of(),
                generatedSources.toFile(),
                Set.of(),
                List.of(),
                "false"));
    }

    private static void writeJava(Path path, String packageName, String type) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "package " + packageName + ";\n\npublic final class " + type + " {}\n",
                StandardCharsets.UTF_8);
    }

    private static void writeJar(Path path, String... entries) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
            for (String entry : entries) {
                output.putNextEntry(new ZipEntry(entry));
                output.closeEntry();
            }
        }
    }

    private static void invokeReleasePublisher(Path linkOutput, Path classes) throws Exception {
        Class<?> processor = Class.forName("io.quarkiverse.scala.scala3.deployment.Scala3Processor");
        Method publish = processor.getDeclaredMethod("publish", File.class, File.class,
                BuildProducer.class, BuildProducer.class, BuildProducer.class);
        publish.setAccessible(true);
        BuildProducer<GeneratedResourceBuildItem> resources = ignored -> {
        };
        BuildProducer<GeneratedFileSystemResourceBuildItem> fileSystemResources = ignored -> {
        };
        BuildProducer<GeneratedStaticResourceBuildItem> staticResources = ignored -> {
        };
        publish.invoke(null, linkOutput.toFile(), classes.toFile(), resources, fileSystemResources, staticResources);
    }

    @SuppressWarnings("unchecked")
    private static <T> T invokeProvider(String methodName, Class<?>[] parameterTypes, Object... arguments) throws Exception {
        Method method = Scala3CompilationProvider.class.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return (T) method.invoke(null, arguments);
    }

    private static void invokeDevPublisher(Path linkOutput, Path classes) throws Exception {
        Class<?> projectState = Class.forName(Scala3CompilationProvider.class.getName() + "$ProjectState");
        Method publish = projectState.getDeclaredMethod("publishWebResource", File.class, File.class);
        publish.setAccessible(true);
        publish.invoke(null, linkOutput.toFile(), classes.toFile());
    }
}
