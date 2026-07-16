package io.quarkiverse.scala.scala3.test;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkiverse.scala.scala3.deployment.Scala3CompilationProvider;
import io.quarkus.deployment.dev.CompilationProvider.Context;

/**
 * Exercises the source/output topology that a Maven multi-module build presents to the
 * compilation provider. The test deliberately creates the project in a fresh temporary
 * directory instead of sharing the extension test application's source tree.
 */
public class Scala3MultiModuleCompilationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    public void oneModuleProducesJvmAndJsOutputsFromMixedSources() throws Exception {
        Path module = createModule("application");
        writeSources(module, Map.of(
                "src/main/java/shared/application/AppJava.java", lines(
                        "package application.shared;", "", "public final class AppJava {", "    private AppJava() {}",
                        "    public static String value() { return \"app-java\"; }", "}"),
                "src/main/java/shared/application/AppShared.scala", lines(
                        "package application.shared", "", "object AppShared {", "  def value: String = \"app-scala\"",
                        "}"),
                "src/main/java/jvm/application/AppJvm.scala", lines(
                        "package application.jvm", "", "object AppJvm {",
                        "  def value: String = application.shared.AppJava.value() + \"/\" + application.shared.AppShared.value",
                        "}"),
                "src/main/java/scalajs/application/AppJs.scala", lines(
                        "package application.js", "", "import scala.scalajs.js.annotation.JSExportTopLevel", "",
                        "object AppJs {", "  @JSExportTopLevel(\"singleModuleValue\")",
                        "  def value(): String = application.shared.AppShared.value", "}")));

        Scala3CompilationProvider provider = new Scala3CompilationProvider();
        try {
            Path jvmOutput = module.resolve("target/classes");
            compile(provider, module, jvmOutput, Set.of());

            Path jsOutput = module.resolve("target/scalajs-classes");
            Path linkedOutput = module.resolve("target/scalajs/scala-js.js");
            assertTrue(Files.isRegularFile(jvmOutput.resolve("application/shared/AppJava.class")),
                    "the JVM output must contain the Java source from the mixed package");
            assertTrue(Files.isRegularFile(jvmOutput.resolve("application/shared/AppShared.class")),
                    "the JVM output must contain the Scala source from the mixed package");
            assertTrue(Files.isRegularFile(jvmOutput.resolve("application/jvm/AppJvm.class")),
                    "the JVM output must contain the JVM-only Scala source");
            assertTrue(Files.isRegularFile(jsOutput.resolve("application/shared/AppShared.sjsir")),
                    "the Scala.js output must contain the shared Scala source");
            assertTrue(Files.isRegularFile(jsOutput.resolve("application/js/AppJs.sjsir")),
                    "the Scala.js output must contain the Scala.js source");
            assertTrue(Files.isRegularFile(linkedOutput), "the module must publish a linked Scala.js output");
        } finally {
            provider.close();
        }
    }

    @Test
    public void jvmOnlySourcesDoNotProduceScalaJsOutput() throws Exception {
        Path module = createModule("jvm-only");
        writeSources(module, Map.of(
                "src/main/java/application/JvmOnly.scala", lines(
                        "package application", "", "object JvmOnly {", "  def value: String = \"jvm-only\"", "}")));

        Scala3CompilationProvider provider = new Scala3CompilationProvider();
        try {
            Path jvmOutput = module.resolve("target/classes");
            compile(provider, module, jvmOutput, Set.of());

            assertTrue(Files.isRegularFile(jvmOutput.resolve("application/JvmOnly.class")),
                    "JVM-only sources must compile to the JVM output");
            assertTrue(!Files.exists(module.resolve("target/scalajs/scala-js.js")),
                    "JVM-only sources must not trigger Scala.js linking");
        } finally {
            provider.close();
        }
    }

    @Test
    public void scalaJsOnlySourcesDoNotProduceJvmClasses() throws Exception {
        Path module = createModule("scalajs-only");
        writeSources(module, Map.of(
                "src/main/scalajs/application/ScalaJsOnly.scala", lines(
                        "package application", "", "import scala.scalajs.js.annotation.JSExportTopLevel", "",
                        "object ScalaJsOnly {", "  @JSExportTopLevel(\"scalaJsOnly\")",
                        "  def value(): String = \"scala-js-only\"", "}")));

        Scala3CompilationProvider provider = new Scala3CompilationProvider();
        try {
            Path jvmOutput = module.resolve("target/classes");
            compile(provider, module, jvmOutput, Set.of());

            assertTrue(Files.isRegularFile(module.resolve("target/scalajs-classes/application/ScalaJsOnly.sjsir")),
                    "Scala.js-only sources must compile to Scala.js IR");
            assertTrue(Files.isRegularFile(module.resolve("target/scalajs/scala-js.js")),
                    "Scala.js-only sources must be linked");
            assertTrue(!Files.exists(jvmOutput.resolve("application/ScalaJsOnly.class")),
                    "Scala.js-only sources must not produce JVM classes");
        } finally {
            provider.close();
        }
    }

    @Test
    public void siblingModulesWithMixedSourcesFeedTheApplicationJvmAndJsOutputs() throws Exception {
        Path moduleA = createModule("module-a");
        writeSources(moduleA, mixedModuleSources("modulea", "moduleA"));
        Path moduleB = createModule("module-b");
        writeSources(moduleB, mixedModuleSources("moduleb", "moduleB"));
        Path application = createModule("application");
        writeSources(application, Map.of(
                "src/main/java/shared/application/AppJava.java", lines(
                        "package application.shared;", "", "public final class AppJava {", "    private AppJava() {}",
                        "    public static String value() { return \"app-java\"; }", "}"),
                "src/main/java/shared/application/AppShared.scala", lines(
                        "package application.shared", "", "object AppShared {", "  def value: String = \"app-scala\"",
                        "}"),
                "src/main/java/jvm/application/AppJvm.scala", lines(
                        "package application.jvm", "", "object AppJvm {",
                        "  def value: String = application.shared.AppJava.value() + \"/\" +",
                        "    application.shared.AppShared.value + \"/\" +",
                        "    modulea.jvm.ModuleAJvm.value + \"/\" + moduleb.jvm.ModuleBJvm.value", "}"),
                "src/main/java/scalajs/application/AppJs.scala", lines(
                        "package application.js", "", "import scala.scalajs.js.annotation.JSExportTopLevel", "",
                        "object AppJs {", "  @JSExportTopLevel(\"multiModuleValue\")",
                        "  def value(): String = application.shared.AppShared.value + \"/\" +",
                        "    modulea.shared.ModuleAShared.value + \"/\" +",
                        "    moduleb.shared.ModuleBShared.value", "}")));
        writeMultiModulePom(temporaryDirectory, List.of("module-a", "module-b", "application"));

        Scala3CompilationProvider provider = new Scala3CompilationProvider();
        try {
            Path moduleAJvm = moduleA.resolve("target/classes");
            compile(provider, moduleA, moduleAJvm, Set.of());
            Path moduleAJs = moduleA.resolve("target/scalajs-classes");

            Path moduleBJvm = moduleB.resolve("target/classes");
            compile(provider, moduleB, moduleBJvm, Set.of(moduleAJvm.toFile(), moduleAJs.toFile()));
            Path moduleBJs = moduleB.resolve("target/scalajs-classes");

            Set<File> applicationDependencies = Set.of(
                    moduleAJvm.toFile(), moduleAJs.toFile(), moduleBJvm.toFile(), moduleBJs.toFile());
            Path applicationJvm = application.resolve("target/classes");
            compile(provider, application, applicationJvm, applicationDependencies);
            Path applicationJs = application.resolve("target/scalajs-classes");
            Path linkedOutput = application.resolve("target/scalajs/scala-js.js");

            assertMixedModuleOutputs(moduleA, "modulea", "moduleA");
            assertMixedModuleOutputs(moduleB, "moduleb", "moduleB");
            assertTrue(Files.isRegularFile(applicationJvm.resolve("application/shared/AppJava.class")));
            assertTrue(Files.isRegularFile(applicationJvm.resolve("application/shared/AppShared.class")));
            assertTrue(Files.isRegularFile(applicationJvm.resolve("application/jvm/AppJvm.class")));
            assertTrue(Files.isRegularFile(applicationJs.resolve("application/shared/AppShared.sjsir")));
            assertTrue(Files.isRegularFile(applicationJs.resolve("application/js/AppJs.sjsir")));
            assertTrue(Files.isRegularFile(linkedOutput));
        } finally {
            provider.close();
        }
    }

    private static Map<String, String> mixedModuleSources(String packageName, String label) {
        return Map.of(
                "src/main/java/shared/" + packageName + "/Module" + label.substring(label.length() - 1)
                        + "Java.java",
                "package " + packageName + ".shared;\n\n"
                        + "public final class Module" + label.substring(label.length() - 1) + "Java {\n"
                        + "  private Module" + label.substring(label.length() - 1) + "Java() {}\n"
                        + "  public static String value() { return \"" + label + "-java\"; }\n"
                        + "}\n",
                "src/main/java/shared/" + packageName + "/Module" + label.substring(label.length() - 1)
                        + "Shared.scala",
                "package " + packageName + ".shared\n\n"
                        + "object Module" + label.substring(label.length() - 1) + "Shared {\n"
                        + "  def value: String = \"" + label + "-scala\"\n}\n",
                "src/main/java/jvm/" + packageName + "/Module" + label.substring(label.length() - 1)
                        + "Jvm.scala",
                "package " + packageName + ".jvm\n\n"
                        + "object Module" + label.substring(label.length() - 1) + "Jvm {\n"
                        + "  def value: String = " + packageName + ".shared.Module"
                        + label.substring(label.length() - 1) + "Java.value()\n}\n",
                "src/main/java/scalajs/" + packageName + "/Module" + label.substring(label.length() - 1)
                        + "Js.scala",
                "package " + packageName + ".js\n\n"
                        + "object Module" + label.substring(label.length() - 1) + "Js {\n"
                        + "  def value: String = " + packageName + ".shared.Module"
                        + label.substring(label.length() - 1) + "Shared.value\n}\n");
    }

    private void assertMixedModuleOutputs(Path module, String packageName, String label) {
        String suffix = label.substring(label.length() - 1);
        assertTrue(Files.isRegularFile(module.resolve("target/classes/" + packageName + "/shared/Module" + suffix
                + "Java.class")));
        assertTrue(Files.isRegularFile(module.resolve("target/classes/" + packageName + "/shared/Module" + suffix
                + "Shared.class")));
        assertTrue(Files.isRegularFile(module.resolve("target/classes/" + packageName + "/jvm/Module" + suffix
                + "Jvm.class")));
        assertTrue(Files.isRegularFile(module.resolve("target/scalajs-classes/" + packageName + "/shared/Module"
                + suffix + "Shared.sjsir")));
        assertTrue(Files.isRegularFile(module.resolve("target/scalajs-classes/" + packageName + "/js/Module" + suffix
                + "Js.sjsir")));
    }

    private Path createModule(String name) throws IOException {
        Path module = temporaryDirectory.resolve(name);
        Files.createDirectories(module.resolve("src/main/java"));
        Files.createDirectories(module.resolve("target"));
        Files.writeString(module.resolve("pom.xml"), "<project><artifactId>" + name + "</artifactId></project>",
                StandardCharsets.UTF_8);
        return module;
    }

    private static void writeSources(Path module, Map<String, String> sources) throws IOException {
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = module.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue(), StandardCharsets.UTF_8);
        }
    }

    private static String lines(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    private static void writeMultiModulePom(Path root, List<String> modules) throws IOException {
        Files.writeString(root.resolve("pom.xml"), "<project><modules><module>"
                + String.join("</module><module>", modules) + "</module></modules></project>", StandardCharsets.UTF_8);
    }

    private static void compile(Scala3CompilationProvider provider, Path module, Path output,
            Set<File> moduleDependencies) {
        Set<File> classpath = new HashSet<>();
        classpath.addAll(Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(File::new)
                .collect(Collectors.toSet()));
        classpath.addAll(moduleDependencies);
        provider.compile(Set.of(), new Context(
                module.getFileName().toString(),
                classpath,
                classpath,
                module.toFile(),
                module.resolve("src/main/java").toFile(),
                output.toFile(),
                "UTF-8",
                Map.of(),
                "17",
                "17",
                "17",
                List.of(),
                List.of(),
                module.resolve("target/generated-sources").toFile(),
                Set.of(),
                List.of(),
                "false"));
    }
}
