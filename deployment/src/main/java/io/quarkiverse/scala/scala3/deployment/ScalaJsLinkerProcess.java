package io.quarkiverse.scala.scala3.deployment;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jboss.logging.Logger;

/**
 * Delegates Scala.js linking to the existing sbt-scalajs linker, following the
 * same bridge-project mechanism used by the vendored scala-maven-plugin.
 */
final class ScalaJsLinkerProcess {

    private static final String SBT_EXECUTABLE_ENV_VAR = "QUARKUS_SCALA3_SCALAJS_SBT";
    private static final String SBT_VERSION = "2.0.2";
    private static final String SCALA_JS_VERSION = "1.22.0";

    void link(File classesDirectory, Set<File> classpath, File bridgeDirectory, File outputDirectory,
            String initializer, File sourceMapBase, Logger log) {
        try {
            Files.createDirectories(bridgeDirectory.toPath());
            Files.createDirectories(outputDirectory.toPath());
            List<File> linkerClasspath = new ArrayList<>(classpath);
            addIfMissing(linkerClasspath, classesDirectory);
            writeBridge(bridgeDirectory, linkerClasspath, outputDirectory, initializer, sourceMapBase);

            String executable = System.getenv().getOrDefault(SBT_EXECUTABLE_ENV_VAR, "sbt");
            List<String> command = List.of(
                    executable,
                    "--sbt-boot", new File(bridgeDirectory, ".sbt-boot").getAbsolutePath(),
                    "--sbt-dir", new File(bridgeDirectory, ".sbt").getAbsolutePath(),
                    "--sbt-cache", new File(bridgeDirectory, ".sbt-cache").getAbsolutePath(),
                    "--ivy", new File(bridgeDirectory, ".ivy2").getAbsolutePath(),
                    "--batch",
                    "--server",
                    "fastLinkJS");

            log.infof("Linking Scala.js with sbt-scalajs into %s", outputDirectory);
            ProcessBuilder processBuilder = new ProcessBuilder(command)
                    .directory(bridgeDirectory)
                    .inheritIO();
            Map<String, String> environment = processBuilder.environment();
            environment.put("COURSIER_CACHE",
                    new File(System.getProperty("java.io.tmpdir"), "quarkus-scala3-coursier").getAbsolutePath());
            int exitCode = processBuilder.start().waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("sbt-scalajs fastLinkJS exited with code " + exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Scala.js linking was interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to invoke the sbt-scalajs linker", e);
        }
    }

    private static void writeBridge(File bridgeDirectory, List<File> classpath, File outputDirectory,
            String initializer, File sourceMapBase) throws IOException {
        File projectDirectory = new File(bridgeDirectory, "project");
        Files.createDirectories(projectDirectory.toPath());
        Files.writeString(new File(projectDirectory, "build.properties").toPath(),
                "sbt.version=" + SBT_VERSION + "\n", StandardCharsets.UTF_8);
        Files.writeString(new File(projectDirectory, "plugins.sbt").toPath(),
                "addSbtPlugin(\"org.scala-js\" % \"sbt-scalajs\" % \"" + SCALA_JS_VERSION + "\")\n",
                StandardCharsets.UTF_8);

        StringBuilder build = new StringBuilder();
        build.append("import sbt._\nimport Keys._\n");
        build.append("import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport._\n");
        build.append(
                "import org.scalajs.linker.interface.{ModuleInitializer, ModuleKind, ModuleSplitStyle, OutputPatterns}\n\n");
        build.append("enablePlugins(org.scalajs.sbtplugin.ScalaJSPlugin)\n\n");
        build.append("Compile / fullClasspath := {\n");
        build.append("  val converter = fileConverter.value\n  Seq(\n");
        for (int i = 0; i < classpath.size(); i++) {
            if (i > 0) {
                build.append(",\n");
            }
            build.append("    Attributed.blank(converter.toVirtualFile(file(\"")
                    .append(escape(classpath.get(i).getAbsolutePath()))
                    .append("\").toPath))");
        }
        build.append("\n  )\n}\n\n");
        build.append("scalaJSLinkerConfig ~= {\n");
        build.append("  _.withModuleKind(ModuleKind.ESModule)\n");
        build.append("    .withModuleSplitStyle(ModuleSplitStyle.SmallModulesFor(List(\"my.app\")))\n");
        build.append("  .withSourceMap(true)\n");
        if (sourceMapBase != null) {
            build.append("  .withRelativizeSourceMapBase(Some(file(\"")
                    .append(escape(sourceMapBase.getAbsolutePath()))
                    .append("\").toURI))\n");
        }
        build.append("  .withOutputPatterns(OutputPatterns.fromJSFile(\"scala-js.js\"))}\n");
        build.append("Compile / fastLinkJS / scalaJSLinkerOutputDirectory := file(\"")
                .append(escape(outputDirectory.getAbsolutePath())).append("\")\n");
        if (initializer != null && !initializer.isBlank()) {
            int separator = initializer.lastIndexOf('#');
            if (separator < 1 || separator == initializer.length() - 1) {
                throw new IllegalArgumentException(
                        "Scala.js initializer must use fully.qualified.Class#method syntax: " + initializer);
            }
            build.append("Compile / scalaJSModuleInitializers := Seq(ModuleInitializer.mainMethod(\"")
                    .append(escape(initializer.substring(0, separator))).append("\", \"")
                    .append(escape(initializer.substring(separator + 1))).append("\"))\n");
        }
        Files.writeString(new File(bridgeDirectory, "build.sbt").toPath(), build.toString(), StandardCharsets.UTF_8);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void addIfMissing(List<File> files, File candidate) {
        if (candidate != null && !files.contains(candidate)) {
            files.add(candidate);
        }
    }
}
