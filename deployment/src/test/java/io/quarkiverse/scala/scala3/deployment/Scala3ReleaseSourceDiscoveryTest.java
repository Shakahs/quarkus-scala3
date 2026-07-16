package io.quarkiverse.scala.scala3.deployment;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Scala3ReleaseSourceDiscoveryTest {

    @Test
    void releaseDiscoverySupportsAllDocumentedScalaJsSourceModes(@TempDir Path project) throws Exception {
        Path javaSourceRoot = project.resolve("src/main/java");
        Path mavenScalaJsRoot = project.resolve("src/main/scalajs/MavenRoot.scala");
        Path scalaSourceRoot = project.resolve("src/main/scala/scalajs/ScalaRoot.scala");
        Path wholeProjectSource = javaSourceRoot.resolve("application/WholeProject.scala");
        writeScala(mavenScalaJsRoot, "maven-root");
        writeScala(scalaSourceRoot, "scala-root");
        writeScala(wholeProjectSource, "whole-project");

        List<String> missing = new ArrayList<>();
        List<File> discovered = Scala3CompilationProvider.scalaJsSources(javaSourceRoot.toFile());
        if (!discovered.contains(mavenScalaJsRoot.toFile())) {
            missing.add("src/main/scalajs");
        }
        if (!discovered.contains(scalaSourceRoot.toFile())) {
            missing.add("src/main/scala/scalajs");
        }
        if (!wholeProjectModeIncludes(javaSourceRoot, wholeProjectSource)) {
            missing.add("QUARKUS_SCALA3_SCALAJS_MODE=whole");
        }

        assertTrue(missing.isEmpty(), "Release Scala.js discovery omitted " + String.join(", ", missing));
    }

    private static boolean wholeProjectModeIncludes(Path sourceDirectory, Path expected) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder command = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                Scala3ReleaseSourceDiscoveryProbe.class.getName(), sourceDirectory.toString(), expected.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.PIPE);
        command.environment().put("QUARKUS_SCALA3_SCALAJS_MODE", "whole");
        Process process = command.start();
        process.getOutputStream().close();
        boolean finished = process.waitFor(15, TimeUnit.SECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        return finished && process.exitValue() == 0 && Boolean.parseBoolean(output);
    }

    private static void writeScala(Path path, String value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "package source.discovery\n\nobject Source { def value = \"" + value + "\" }\n",
                StandardCharsets.UTF_8);
    }
}
