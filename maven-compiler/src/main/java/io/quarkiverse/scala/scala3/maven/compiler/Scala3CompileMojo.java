package io.quarkiverse.scala.scala3.maven.compiler;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import io.quarkiverse.scala.scala3.deployment.Scala3CompilationProvider;

@Mojo(name = "compile", defaultPhase = LifecyclePhase.COMPILE, requiresDependencyResolution = ResolutionScope.COMPILE, threadSafe = true)
public final class Scala3CompileMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project.build.sourceDirectory}", readonly = true, required = true)
    File sourceDirectory;

    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true, required = true)
    File outputDirectory;

    @Parameter(defaultValue = "${project.compileClasspathElements}", readonly = true, required = true)
    List<String> classpathElements;

    @Parameter(defaultValue = "${maven.compiler.release}")
    String release;

    @Override
    public void execute() throws MojoExecutionException {
        compile(sourceDirectory, outputDirectory, classpathElements, release);
    }

    static void compile(File sourceDirectory, File outputDirectory, List<String> classpathElements, String release)
            throws MojoExecutionException {
        File sourceRoot = sourceRoot(sourceDirectory);
        List<File> sources = scalaSources(sourceRoot);
        if (sources.isEmpty()) {
            return;
        }
        Set<File> classpath = classpathElements.stream().map(File::new).collect(Collectors.toSet());
        classpath.add(outputDirectory);
        try {
            Scala3CompilationProvider.compileJvmForMaven(sources, sourceDirectory, outputDirectory, classpath, release);
        } catch (RuntimeException e) {
            throw new MojoExecutionException("Scala compilation failed", e);
        }
    }

    static File sourceRoot(File sourceDirectory) {
        if (sourceDirectory != null
                && ("java".equals(sourceDirectory.getName()) || "scala".equals(sourceDirectory.getName()))) {
            return sourceDirectory.getParentFile();
        }
        return sourceDirectory;
    }

    static List<File> scalaSources(File sourceRoot) throws MojoExecutionException {
        if (sourceRoot == null || !sourceRoot.isDirectory()) {
            return List.of();
        }
        try (var paths = Files.walk(sourceRoot.toPath())) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".scala"))
                    .filter(path -> !path.toString().contains(File.separator + "scalajs" + File.separator))
                    .map(path -> path.toFile())
                    .sorted()
                    .collect(Collectors.toList());
        } catch (Exception e) {
            throw new MojoExecutionException("Unable to enumerate Scala sources", e);
        }
    }
}
