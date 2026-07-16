package io.quarkiverse.scala.scala3.deployment;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.jboss.logging.Logger;

import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.GeneratedFileSystemResourceBuildItem;
import io.quarkus.deployment.builditem.GeneratedResourceBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.pkg.builditem.OutputTargetBuildItem;
import io.quarkus.jackson.spi.ClassPathJacksonModuleBuildItem;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.vertx.http.deployment.spi.GeneratedStaticResourceBuildItem;

class Scala3Processor {

    private static final Logger LOG = Logger.getLogger(Scala3Processor.class);
    private static final String FEATURE = "scala3";
    private static final String SCALA_JACKSON_MODULE = "com.fasterxml.jackson.module.scala.DefaultScalaModule";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /*
     * Register the Scala Jackson module if that has been added to the classpath
     * Producing the BuildItem is entirely safe since if quarkus-jackson is not on
     * the classpath the BuildItem will just be ignored
     */
    @BuildStep
    void registerScalaJacksonModule(BuildProducer<ClassPathJacksonModuleBuildItem> classPathJacksonModules) {
        try {
            Class.forName(SCALA_JACKSON_MODULE, false, Thread.currentThread().getContextClassLoader());
            classPathJacksonModules.produce(new ClassPathJacksonModuleBuildItem(SCALA_JACKSON_MODULE));
        } catch (Exception ignored) {
        }
    }

    @BuildStep
    void linkScalaJsRelease(ApplicationArchivesBuildItem applicationArchives, OutputTargetBuildItem outputTarget,
            LaunchModeBuildItem launchMode, BuildProducer<GeneratedResourceBuildItem> generatedResources,
            BuildProducer<GeneratedFileSystemResourceBuildItem> generatedFileSystemResources,
            BuildProducer<GeneratedStaticResourceBuildItem> generatedStaticResources) {
        if (launchMode.getLaunchMode() == LaunchMode.DEVELOPMENT) {
            LOG.debugf("Skipping Scala.js release link in %s mode", launchMode.getLaunchMode());
            return;
        }

        Properties buildProperties = outputTarget.getBuildSystemProperties();
        File sourceDirectory = sourceDirectory(buildProperties, outputTarget.getOutputDirectory());
        File projectDirectory = projectDirectory(buildProperties, outputTarget.getOutputDirectory());
        File sourceSetDirectory = Scala3CompilationProvider.sourceSetDirectory(projectDirectory, sourceDirectory, false);
        List<File> sources = Scala3CompilationProvider.scalaJsSources(projectDirectory, sourceDirectory, false);
        LOG.infof("Scala.js release link in %s mode: sourceDirectory=%s, sources=%d",
                launchMode.getLaunchMode(), sourceDirectory, sources.size());
        if (sources.isEmpty()) {
            return;
        }

        Set<File> classpath = new LinkedHashSet<>();
        for (var archive : applicationArchives.getAllApplicationArchives()) {
            archive.getResolvedDependency().getResolvedPaths().forEach(path -> classpath.add(path.toFile()));
        }

        File classesDirectory = outputTarget.getOutputDirectory()
                .resolveSibling("scalajs-classes").toFile();
        File linkOutput = outputTarget.getOutputDirectory()
                .resolveSibling("scalajs").toFile();
        String release = buildProperties.getProperty("maven.compiler.release", "17");
        Scala3CompilationProvider.compileScalaJsForRelease(sources, sourceSetDirectory, classesDirectory,
                outputTarget.getOutputDirectory().toFile(), classpath, release);

        Set<File> linkerClasspath = Scala3CompilationProvider
                .compilerClasspath(Scala3CompilationProvider.scalaJsMavenClasspath(classpath, sources));
        linkerClasspath.add(outputTarget.getOutputDirectory().toFile());
        linkerClasspath.add(classesDirectory);
        ScalaJsLinkerProcess linker = new ScalaJsLinkerProcess();
        try {
            linker.link(linkerClasspath, linkOutput, Scala3CompilationProvider.scalaJsApplicationPackages(sources),
                    Scala3CompilationProvider.scalaJsMainClasses(sources),
                    System.getenv("QUARKUS_SCALA3_SCALAJS_INITIALIZER"),
                    sourceSetDirectory, ScalaJsLinkerProcess.LinkMode.FULL,
                    LOG);
            publish(linkOutput, outputTarget.getOutputDirectory().toFile(), generatedResources,
                    generatedFileSystemResources, generatedStaticResources);
        } finally {
            linker.close();
        }
    }

    private static File sourceDirectory(Properties properties, Path outputDirectory) {
        List<File> candidates = new ArrayList<>();
        String customSources = properties.getProperty("customSourcesDir");
        if (customSources != null) {
            candidates.add(new File(customSources));
        }
        String configured = properties.getProperty("project.build.sourceDirectory");
        if (configured != null) {
            candidates.add(new File(configured));
        }
        Path projectDirectory = outputDirectory.getParent() == null ? outputDirectory
                : outputDirectory.getParent().getParent();
        candidates.add(projectDirectory.resolve("src/main/java").toFile());
        Path testRoot = outputDirectory.toAbsolutePath().normalize();
        while (testRoot != null) {
            candidates.add(testRoot.resolve("custom-sources").toFile());
            testRoot = testRoot.getParent();
        }
        return candidates.stream().filter(File::isDirectory)
                .filter(candidate -> !Scala3CompilationProvider.scalaJsSources(candidate).isEmpty())
                .findFirst()
                .orElseGet(() -> candidates.stream().filter(File::isDirectory).findFirst()
                        .orElseGet(() -> candidates.get(0)));
    }

    private static File projectDirectory(Properties properties, Path outputDirectory) {
        String configured = properties.getProperty("project.basedir");
        if (configured != null && !configured.isBlank()) {
            return new File(configured);
        }
        Path parent = outputDirectory.getParent();
        return (parent == null ? outputDirectory : parent.getParent()).toFile();
    }

    private static void publish(File linkOutput, File classesDirectory,
            BuildProducer<GeneratedResourceBuildItem> generatedResources,
            BuildProducer<GeneratedFileSystemResourceBuildItem> generatedFileSystemResources,
            BuildProducer<GeneratedStaticResourceBuildItem> generatedStaticResources) {
        Scala3CompilationProvider.publishWebResources(linkOutput, classesDirectory);
        Path outputRoot = linkOutput.toPath();
        for (Path file : Scala3CompilationProvider.linkedOutputFiles(linkOutput)) {
            String name = outputRoot.relativize(file).toString().replace(File.separatorChar, '/');
            try {
                byte[] data = Files.readAllBytes(file);
                generatedResources.produce(new GeneratedResourceBuildItem("META-INF/resources/scala-js/" + name, data));
                generatedFileSystemResources
                        .produce(new GeneratedFileSystemResourceBuildItem("META-INF/resources/scala-js/" + name, data));
                generatedStaticResources.produce(new GeneratedStaticResourceBuildItem("/scala-js/" + name, data));
            } catch (IOException e) {
                throw new IllegalStateException("Unable to publish Scala.js release resource " + file, e);
            }
        }
    }
}
