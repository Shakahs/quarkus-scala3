package io.quarkiverse.scala.scala3.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class ReactorBootstrap {
    interface CommandRunner {
        int run(List<String> command) throws Exception;
    }

    private ReactorBootstrap() {
    }

    static void ensureBuilt(Path projectDirectory, List<String> goals, CommandRunner runner) throws Exception {
        if (!goals.contains("quarkus:dev") && !goals.contains("test"))
            return;
        Path root = root(projectDirectory);
        if (root == null || root.equals(projectDirectory))
            return;
        List<String> command = new ArrayList<>(
                List.of(root.resolve("mvnw").toString(), "-q", "install", "-Dmaven.test.skip=true",
                        "-Dscalafix.skip=true", "-Dquarkus.analytics.disabled=true",
                        "-Dquarkus.registry-client.enabled=false"));
        if (runner.run(List.copyOf(command)) != 0)
            throw new IOException("Maven reactor bootstrap failed for " + projectDirectory);
    }

    static boolean skipsScalafix(List<String> goals) {
        return goals.contains("test");
    }

    private static Path root(Path projectDirectory) throws IOException {
        for (Path candidate = projectDirectory.toAbsolutePath().normalize(); candidate != null; candidate = candidate
                .getParent()) {
            Path pom = candidate.resolve("pom.xml");
            if (Files.isRegularFile(candidate.resolve("mvnw")) && Files.isRegularFile(pom)
                    && Files.readString(pom).contains("<modules"))
                return candidate;
        }
        return null;
    }

}
