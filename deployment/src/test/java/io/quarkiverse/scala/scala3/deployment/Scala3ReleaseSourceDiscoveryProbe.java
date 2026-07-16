package io.quarkiverse.scala.scala3.deployment;

import java.io.File;

/** Test-process entry point used to exercise environment-based release source discovery. */
public final class Scala3ReleaseSourceDiscoveryProbe {

    private Scala3ReleaseSourceDiscoveryProbe() {
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected source directory and source file arguments");
        }
        File sourceDirectory = new File(args[0]);
        File expected = new File(args[1]);
        boolean found = Scala3CompilationProvider.scalaJsSources(sourceDirectory).stream()
                .anyMatch(source -> source.equals(expected));
        System.out.println(found);
    }
}
