package io.quarkiverse.scala.scala3.maven;

import java.util.List;

import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;

final class Scala3MavenCompilerConfigurator {
    private static final String SCALA3_GROUP_ID = "io.quarkiverse.scala";
    private static final String SCALA3_ARTIFACT_ID = "quarkus-scala3";
    private static final String COMPILER_ARTIFACT_ID = "quarkus-scala3-maven-compiler";

    private Scala3MavenCompilerConfigurator() {
    }

    static void configure(Model project, String projectGroupId) {
        if (SCALA3_GROUP_ID.equals(projectGroupId)) {
            return;
        }
        Dependency scala3 = project.getDependencies().stream()
                .filter(dependency -> SCALA3_GROUP_ID.equals(dependency.getGroupId()))
                .filter(dependency -> SCALA3_ARTIFACT_ID.equals(dependency.getArtifactId()))
                .findFirst()
                .orElse(null);
        Build build = project.getBuild();
        if (scala3 == null || build != null && build.getPlugins().stream()
                .anyMatch(plugin -> SCALA3_GROUP_ID.equals(plugin.getGroupId())
                        && COMPILER_ARTIFACT_ID.equals(plugin.getArtifactId()))) {
            return;
        }
        if (build == null) {
            build = new Build();
            project.setBuild(build);
        }
        Plugin plugin = new Plugin();
        plugin.setGroupId(SCALA3_GROUP_ID);
        plugin.setArtifactId(COMPILER_ARTIFACT_ID);
        plugin.setVersion(scala3.getVersion());
        plugin.addExecution(execution("scala3-compile", "compile", "compile"));
        plugin.addExecution(execution("scala3-test-compile", "test-compile", "testCompile"));
        build.addPlugin(plugin);
    }

    private static PluginExecution execution(String id, String phase, String goal) {
        PluginExecution execution = new PluginExecution();
        execution.setId(id);
        execution.setPhase(phase);
        execution.setGoals(List.of(goal));
        return execution;
    }
}
