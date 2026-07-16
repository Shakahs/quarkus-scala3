package io.quarkiverse.scala.scala3.test;

import static io.restassured.RestAssured.get;
import static org.hamcrest.CoreMatchers.is;

import java.util.List;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.maven.dependency.Dependency;
import io.quarkus.test.QuarkusProdModeTest;

public class Scala3ProdModeTest {

    @RegisterExtension
    static final QuarkusProdModeTest prodModeTest = new QuarkusProdModeTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addAsResource(new StringAsset("quarkus.http.port=8081\n"),
                            "application.properties")
                    .addClass(DevGreetingResource.class)
                    .addClass(DevGreeter.class)
                    .addClass(DevJavaGreeting.class)
                    .addClass(GreetingService.class))
            .setForcedDependencies(List.of(Dependency.of("io.quarkus", "quarkus-rest", "3.37.3")))
            .setRun(true);

    @Test
    public void fastJarRunsCompiledScalaApplication() {
        get("/dev-greeting")
                .then()
                .statusCode(200)
                .body(is("Hello, Scala 3!"));
    }
}
