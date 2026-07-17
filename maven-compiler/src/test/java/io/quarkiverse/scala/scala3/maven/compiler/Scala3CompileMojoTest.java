package io.quarkiverse.scala.scala3.maven.compiler;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Scala3CompileMojoTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void writesScalaClassesToMavensConfiguredOutputDirectory() throws Exception {
        Path sourceDirectory = temporaryDirectory.resolve("src/main/java");
        Path source = sourceDirectory.resolve("example/Greeting.scala");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package example\nclass Greeting\n");
        Path outputDirectory = temporaryDirectory.resolve("target/classes");

        Scala3CompileMojo.compile(sourceDirectory.toFile(), outputDirectory.toFile(), List.of(), "21");

        assertTrue(Files.isRegularFile(outputDirectory.resolve("example/Greeting.class")));
    }

    @Test
    void recreatesScalaClassesWhenThePreviousMavenOutputWasRemoved() throws Exception {
        Path sourceDirectory = temporaryDirectory.resolve("src/main/java");
        Path source = sourceDirectory.resolve("example/Greeting.scala");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package example\nclass Greeting\n");
        Path outputDirectory = temporaryDirectory.resolve("target/classes");
        Path compiledClass = outputDirectory.resolve("example/Greeting.class");

        Scala3CompileMojo.compile(sourceDirectory.toFile(), outputDirectory.toFile(), List.of(), "21");
        Files.delete(compiledClass);

        Scala3CompileMojo.compile(sourceDirectory.toFile(), outputDirectory.toFile(), List.of(), "21");

        assertTrue(Files.isRegularFile(compiledClass));
    }
}
