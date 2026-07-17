package io.quarkiverse.scala.scala3.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.junit.jupiter.api.Test;

class Scala3MavenCompilerConfiguratorTest {
    @Test
    void addsCompileAndTestCompileGoalsForScala3Projects() {
        Model project = new Model();
        Dependency dependency = new Dependency();
        dependency.setGroupId("io.quarkiverse.scala");
        dependency.setArtifactId("quarkus-scala3");
        dependency.setVersion("999-SNAPSHOT");
        project.getDependencies().add(dependency);

        Scala3MavenCompilerConfigurator.configure(project, "com.example");

        var plugin = project.getBuild().getPlugins().stream().findFirst().orElseThrow();
        assertEquals("quarkus-scala3-maven-compiler", plugin.getArtifactId());
        assertEquals("999-SNAPSHOT", plugin.getVersion());
        assertTrue(plugin.getExecutions().stream()
                .anyMatch(execution -> "compile".equals(execution.getPhase()) && execution.getGoals().contains("compile")));
        assertTrue(plugin.getExecutions().stream().anyMatch(
                execution -> "test-compile".equals(execution.getPhase()) && execution.getGoals().contains("testCompile")));
    }

    @Test
    void doesNotConfigureTheExtensionReactor() {
        Model project = new Model();
        project.setGroupId("io.quarkiverse.scala");
        Dependency dependency = new Dependency();
        dependency.setGroupId("io.quarkiverse.scala");
        dependency.setArtifactId("quarkus-scala3");
        project.getDependencies().add(dependency);

        Scala3MavenCompilerConfigurator.configure(project, "io.quarkiverse.scala");

        assertTrue(project.getBuild() == null);
    }
}
