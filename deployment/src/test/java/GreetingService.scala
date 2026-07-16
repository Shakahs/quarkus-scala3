package io.quarkiverse.scala.scala3.test

import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class GreetingService extends DevGreeter {
  override def greet(name: String): String = s"Hello, $name${DevJavaGreeting.punctuation()}"
}
