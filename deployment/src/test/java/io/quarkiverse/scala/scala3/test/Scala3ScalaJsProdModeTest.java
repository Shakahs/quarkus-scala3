package io.quarkiverse.scala.scala3.test;

import static io.restassured.RestAssured.get;
import static org.hamcrest.CoreMatchers.containsString;

import java.util.List;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.maven.dependency.Dependency;
import io.quarkus.test.QuarkusProdModeTest;

public class Scala3ScalaJsProdModeTest {

    @RegisterExtension
    static final QuarkusProdModeTest prodModeTest = new QuarkusProdModeTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addAsResource(new StringAsset("quarkus.http.port=8084\n"),
                            "application.properties")
                    .addAsResource(new StringAsset("export const scalaJsGreeting = 'Scala.js release';\n"),
                            "META-INF/resources/scala-js/scala-js.js"))
            .setForcedDependencies(List.of(Dependency.of("io.quarkus", "quarkus-rest", "3.37.3")))
            .setRun(true);

    @Test
    public void fastJarPackagesAndServesScalaJsResource() {
        get("/scala-js/scala-js.js")
                .then()
                .statusCode(200)
                .body(containsString("scalaJsGreeting"))
                .body(containsString("Scala.js release"));
    }
}
