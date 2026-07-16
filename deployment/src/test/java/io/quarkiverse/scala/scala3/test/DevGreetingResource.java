package io.quarkiverse.scala.scala3.test;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("/dev-greeting")
public class DevGreetingResource {

    @Inject
    DevGreeter greeter;

    @GET
    public String greeting() {
        return greeter.greet("Scala 3");
    }
}
