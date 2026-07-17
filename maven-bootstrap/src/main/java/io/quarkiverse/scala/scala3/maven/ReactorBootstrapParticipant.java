package io.quarkiverse.scala.scala3.maven;

import java.nio.file.Path;

import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;

@Named
@Singleton
public final class ReactorBootstrapParticipant extends AbstractMavenLifecycleParticipant {

    @Override
    public void afterProjectsRead(MavenSession session) throws MavenExecutionException {
        session.getProjects().forEach(
                project -> Scala3MavenCompilerConfigurator.configure(project.getModel(), project.getGroupId()));
        if (ReactorBootstrap.skipsScalafix(session.getRequest().getGoals())) {
            session.getUserProperties().setProperty("scalafix.skip", "true");
        }
        if (session.getProjects().size() != 1) {
            return;
        }
        Path baseDirectory = session.getProjects().get(0).getBasedir().toPath();
        try {
            ReactorBootstrap.ensureBuilt(baseDirectory, session.getRequest().getGoals(), command -> new ProcessBuilder(command)
                    .directory(baseDirectory.getParent().toFile())
                    .inheritIO().start().waitFor());
        } catch (Exception e) {
            throw new MavenExecutionException("Unable to bootstrap the Maven reactor for Quarkus dev mode", e);
        }
    }
}
