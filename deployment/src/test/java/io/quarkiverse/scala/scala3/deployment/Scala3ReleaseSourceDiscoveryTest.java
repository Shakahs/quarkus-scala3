package io.quarkiverse.scala.scala3.deployment;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Scala3ReleaseSourceDiscoveryTest {

    @Test
    void releaseDiscoverySupportsAllDocumentedScalaJsSourceRoots(@TempDir Path project) throws Exception {
        Path javaSourceRoot = project.resolve("src/main/java");
        Path mavenScalaJsRoot = project.resolve("src/main/scalajs/MavenRoot.scala");
        Path scalaSourceRoot = project.resolve("src/main/scala/scalajs/ScalaRoot.scala");
        writeScala(mavenScalaJsRoot, "maven-root");
        writeScala(scalaSourceRoot, "scala-root");

        List<String> missing = new ArrayList<>();
        List<File> discovered = Scala3CompilationProvider.scalaJsSources(javaSourceRoot.toFile());
        if (!discovered.contains(mavenScalaJsRoot.toFile())) {
            missing.add("src/main/scalajs");
        }
        if (!discovered.contains(scalaSourceRoot.toFile())) {
            missing.add("src/main/scala/scalajs");
        }
        assertTrue(missing.isEmpty(), "Release Scala.js discovery omitted " + String.join(", ", missing));
    }

    private static void writeScala(Path path, String value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "package source.discovery\n\nobject Source { def value = \"" + value + "\" }\n",
                StandardCharsets.UTF_8);
    }
}
