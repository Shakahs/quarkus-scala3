package io.quarkiverse.scala.scala3.test;

import static io.restassured.RestAssured.get;
import static org.hamcrest.CoreMatchers.containsString;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusDevModeTest;

public class Scala3ScalaJsReloadDevModeTest {

    @RegisterExtension
    static final QuarkusDevModeTest devModeTest = new QuarkusDevModeTest()
            .setCodeGenSources("java/scalajs")
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addAsResource(new StringAsset("quarkus.http.test-port=8083\n"),
                            "application.properties"));

    @Test
    public void scalaJsSourceChangeRelinksTheFrontend() {
        devModeTest.modifyFile("java/scalajs/ScalaJsGreeting.scala",
                source -> source.replace("Scala.js first", "Scala.js second"));

        get("/scala-js/scala-js.js")
                .then()
                .statusCode(200)
                .body(containsString("scalaJsGreeting"))
                .body(containsString("Scala.js second"));
    }
}
