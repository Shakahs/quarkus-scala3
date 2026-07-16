package io.quarkiverse.scala.scala3.test;

import static io.restassured.RestAssured.get;
import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusDevModeTest;

public class Scala3ScalaJsWebBundlerDevModeTest {

    @RegisterExtension
    static final QuarkusDevModeTest devModeTest = new QuarkusDevModeTest()
            .setCodeGenSources("java/scalajs")
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addAsResource("web", "web")
                    .addAsResource(new StringAsset(
                            "quarkus.http.test-port=8085\n" +
                                    "quarkus.web-bundler.browser-live-reload=false\n" +
                                    "quarkus.web-bundler.bundle-redirect=true\n"),
                            "application.properties"));

    @Test
    public void scalaJsAndMvnpmWebBundleAreServedTogether() {
        devModeTest.modifyFile("java/scalajs/web/ScalaJsWebGreeting.scala",
                source -> source.replace("Scala.js web", "Scala.js web initial"));

        assertNodeOutput(servedBundle(), "mvnpm-lodash-loaded");

        get("/scala-js/scala-js.js")
                .then()
                .statusCode(200)
                .body(containsString("scalaJsWebGreeting"))
                .body(containsString("Scala.js web initial"));
    }

    @Test
    public void frontendResourceHotReloadRebuildsUsableBundle() {
        devModeTest.modifyFile("java/scalajs/web/ScalaJsWebGreeting.scala",
                source -> source.replace("Scala.js web", "Scala.js web hot reload"));

        assertNodeOutput(servedBundle(), "mvnpm-lodash-loaded");

        devModeTest.modifyResourceFile("web/app.js",
                source -> source.replace("mvnpm lodash loaded", "mvnpm lodash hot reloaded"));

        assertNodeOutput(servedBundle(), "mvnpm-lodash-hot-reloaded");

        get("/scala-js/scala-js.js")
                .then()
                .statusCode(200)
                .body(containsString("scalaJsWebGreeting"))
                .body(containsString("Scala.js web hot reload"));
    }

    private static String servedBundle() {
        return given()
                .redirects().follow(true)
                .when().get("/static/bundle/app.js")
                .then()
                .statusCode(200)
                .extract().asString();
    }

    private static void assertNodeOutput(String bundle, String expectedMarker) {
        Path script = null;
        try {
            script = Files.createTempFile("quarkus-scala3-web-bundle-", ".js");
            Files.writeString(script, bundle + "\n", StandardCharsets.UTF_8);
            Process process = new ProcessBuilder("node", script.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(finished, "Node did not finish executing the retrieved web bundle: " + output);
            assertEquals(0, process.exitValue(), "Node rejected the retrieved web bundle: " + output);
            assertTrue(output.contains(expectedMarker),
                    "Node output did not contain the mvnpm-derived marker: " + output);
            assertTrue(output.contains("/scala-js/scala-js.js"),
                    "Node output did not contain the Scala.js resource URL: " + output);
        } catch (IOException e) {
            throw new AssertionError("Unable to execute the retrieved web bundle with Node", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while executing the retrieved web bundle with Node", e);
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
