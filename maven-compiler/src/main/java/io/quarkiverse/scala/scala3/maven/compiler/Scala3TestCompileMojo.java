package io.quarkiverse.scala.scala3.maven.compiler;

import java.io.File;
import java.util.List;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

@Mojo(name = "testCompile", defaultPhase = LifecyclePhase.TEST_COMPILE, requiresDependencyResolution = ResolutionScope.TEST, threadSafe = true)
public final class Scala3TestCompileMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project.build.testSourceDirectory}", readonly = true, required = true)
    File sourceDirectory;

    @Parameter(defaultValue = "${project.build.testOutputDirectory}", readonly = true, required = true)
    File outputDirectory;

    @Parameter(defaultValue = "${project.testClasspathElements}", readonly = true, required = true)
    List<String> classpathElements;

    @Parameter(defaultValue = "${maven.compiler.release}")
    String release;

    @Parameter(property = "maven.test.skip", defaultValue = "false")
    boolean skip;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().debug("Skipping Scala test compilation because maven.test.skip is enabled");
            return;
        }
        Scala3CompileMojo.compile(sourceDirectory, outputDirectory, classpathElements, release);
    }
}
