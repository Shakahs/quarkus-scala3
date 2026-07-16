package io.quarkiverse.scala.scala3.deployment;

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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.builditem.GeneratedFileSystemResourceBuildItem;
import io.quarkus.deployment.builditem.GeneratedResourceBuildItem;
import io.quarkus.deployment.dev.CompilationProvider.Context;
import io.quarkus.vertx.http.deployment.spi.GeneratedStaticResourceBuildItem;

class Scala3CompilationProviderSourceRootsTest {

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

    private static void invokeReleasePublisher(Path linkOutput, Path classes) throws Exception {
        Method publish = Scala3Processor.class.getDeclaredMethod("publish", File.class, File.class,
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

    private static void invokeDevPublisher(Path linkOutput, Path classes) throws Exception {
        Class<?> projectState = Class.forName(Scala3CompilationProvider.class.getName() + "$ProjectState");
        Method publish = projectState.getDeclaredMethod("publishWebResource", File.class, File.class);
        publish.setAccessible(true);
        publish.invoke(null, linkOutput.toFile(), classes.toFile());
    }
}
