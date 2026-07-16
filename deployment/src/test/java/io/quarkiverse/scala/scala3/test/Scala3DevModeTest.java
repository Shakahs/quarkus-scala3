package io.quarkiverse.scala.scala3.test;

import static io.restassured.RestAssured.get;
import static org.hamcrest.CoreMatchers.is;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusDevModeTest;

public class Scala3DevModeTest {

    @RegisterExtension
    static final QuarkusDevModeTest devModeTest = new QuarkusDevModeTest()
            .setCodeGenSources("java/GreetingService.scala")
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addAsResource(new StringAsset("quarkus.http.test-port=8080\n"),
                            "application.properties")
                    .addClass(DevGreetingResource.class)
                    .addClass(DevGreeter.class)
                    .addClass(DevJavaGreeting.class)
                    .addClass(GreetingService.class));

    @Test
    public void initialMixedCompilationWorks() {
        get("/dev-greeting")
                .then()
                .statusCode(200)
                .body(is("Hello, Scala 3!"));
    }

    @Test
    public void javaChangeUsesIncrementalMixedCompiler() {
        devModeTest.modifySourceFile(
                "DevJavaGreeting.java",
                source -> source.replace("return \"!\";", "return \"!!\";"));

        get("/dev-greeting")
                .then()
                .statusCode(200)
                .body(is("Hello, Scala 3!!"));
    }

    @Test
    public void scalaChangeUsesIncrementalCompiler() {
        devModeTest.modifySourceFile(
                "GreetingService.scala",
                source -> source.replace("s\"Hello, $name", "s\"Hi, $name"));

        get("/dev-greeting")
                .then()
                .statusCode(200)
                .body(is("Hi, Scala 3!"));
    }

    @Test
    public void javaAndScalaChangesWorkAcrossMultipleReloads() {
        devModeTest.modifySourceFile(
                "DevJavaGreeting.java",
                source -> source.replace("return \"!\";", "return \"!!\";"));

        get("/dev-greeting")
                .then()
                .statusCode(200)
                .body(is("Hello, Scala 3!!"));

        devModeTest.modifySourceFile(
                "GreetingService.scala",
                source -> source.replace("s\"Hello, $name", "s\"Hi, $name"));

        get("/dev-greeting")
                .then()
                .statusCode(200)
                .body(is("Hi, Scala 3!!"));
    }

}
