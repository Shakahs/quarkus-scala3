package io.quarkiverse.scala.scala3.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReactorBootstrapTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void bootstrapsSiblingModulesBeforeLeafDevDependencyResolution() throws Exception {
        Path root = temporaryDirectory.resolve("root"), app = root.resolve("csr-web");
        Files.createDirectories(app);
        Files.writeString(root.resolve("mvnw"), "#!/bin/sh\n");
        Files.writeString(root.resolve("pom.xml"), "<project><modules/></project>");
        Files.writeString(app.resolve("pom.xml"),
                "<project><parent><artifactId>parent</artifactId></parent><artifactId>csr-web</artifactId></project>");
        List<String> actual = new ArrayList<>();
        ReactorBootstrap.ensureBuilt(app, List.of("quarkus:dev"), command -> {
            actual.addAll(command);
            return 0;
        });
        assertEquals(List.of(root.resolve("mvnw").toString(), "-q", "install",
                "-Dmaven.test.skip=true", "-Dscalafix.skip=true", "-Dquarkus.analytics.disabled=true",
                "-Dquarkus.registry-client.enabled=false"),
                actual);
    }

    @Test
    void bootstrapsSiblingModulesBeforeLeafTestCompilation() throws Exception {
        Path root = temporaryDirectory.resolve("test-root"), app = root.resolve("csr-web");
        Files.createDirectories(app);
        Files.writeString(root.resolve("mvnw"), "#!/bin/sh\n");
        Files.writeString(root.resolve("pom.xml"), "<project><modules/></project>");
        Files.writeString(app.resolve("pom.xml"), "<project><artifactId>csr-web</artifactId></project>");
        List<String> actual = new ArrayList<>();
        ReactorBootstrap.ensureBuilt(app, List.of("test"), command -> {
            actual.addAll(command);
            return 0;
        });
        assertEquals(List.of(root.resolve("mvnw").toString(), "-q", "install",
                "-Dmaven.test.skip=true", "-Dscalafix.skip=true", "-Dquarkus.analytics.disabled=true",
                "-Dquarkus.registry-client.enabled=false"), actual);
    }

    @Test
    void skipsScalafixForLeafTestGoalsOnly() {
        assertEquals(true, ReactorBootstrap.skipsScalafix(List.of("test")));
        assertEquals(false, ReactorBootstrap.skipsScalafix(List.of("quarkus:dev")));
    }
}
