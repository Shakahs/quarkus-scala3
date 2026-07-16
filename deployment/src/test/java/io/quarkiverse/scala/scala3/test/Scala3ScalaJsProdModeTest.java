package io.quarkiverse.scala.scala3.test;

import static io.restassured.RestAssured.get;
import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.maven.dependency.Dependency;
import io.quarkus.test.QuarkusProdModeTest;
import io.restassured.response.Response;

public class Scala3ScalaJsProdModeTest {

    @RegisterExtension
    static final QuarkusProdModeTest prodModeTest = new QuarkusProdModeTest()
            .addCustomResourceEntry(Path.of("java/scalajs/ScalaJsGreeting.scala"),
                    "scalajs/ScalaJsGreeting.scala")
            .addCustomResourceEntry(Path.of("scalajs/ScalaJsMain.scala"),
                    "scalajs/ScalaJsMain.scala")
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addAsResource(new StringAsset("quarkus.http.port=8084\n"),
                            "application.properties"))
            .setForcedDependencies(List.of(Dependency.of("io.quarkus", "quarkus-rest", "3.37.3")))
            .setRun(true);

    @Test
    public void fullLinkPackagesAndServesScalaJsResource() {
        get("/scala-js/scala-js.js")
                .then()
                .statusCode(200)
                .body(containsString("scalaJsGreeting"))
                .body(containsString("Scala.js first"));
    }

    @Test
    public void fullLinkOutputExecutesWithNode() {
        Response response = get("/scala-js/scala-js.js");
        assertEquals(200, response.statusCode());
        assertNodeOutput(response.asString(), "scala-js-release-node-ok");
    }

    @Test
    public void fullLinkAutomaticallyInitializesScalaMain() {
        Response response = get("/scala-js/scala-js.js");
        assertEquals(200, response.statusCode());
        assertNodeOutput(response.asString(), "scala-js-main-initialized");
    }

    private static void assertNodeOutput(String javascript, String expectedOutput) {
        Path script = null;
        try {
            script = Files.createTempFile("quarkus-scala3-release-", ".mjs");
            Files.writeString(script, javascript + "\nconsole.log('scala-js-release-node-ok');\n",
                    StandardCharsets.UTF_8);
            Process process = new ProcessBuilder("node", script.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(finished, "Node did not finish executing the released Scala.js: " + output);
            assertEquals(0, process.exitValue(), "Node rejected the released Scala.js: " + output);
            assertTrue(output.contains(expectedOutput),
                    "Node output did not contain the Scala.js entry point marker: " + output);
        } catch (IOException e) {
            throw new AssertionError("Unable to execute the released Scala.js with Node", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while executing the released Scala.js with Node", e);
        } finally {
            if (script != null) {
                try {
                    Files.deleteIfExists(script);
                } catch (IOException ignored) {
                    // Temporary test cleanup is best effort.
                }
            }
        }
    }
}
