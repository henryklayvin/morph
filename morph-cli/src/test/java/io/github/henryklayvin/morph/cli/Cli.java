package io.github.henryklayvin.morph.cli;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Runs the CLI inside the test JVM and captures what it prints. */
record Cli(int exit, String out, String err) {

    static Cli run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = Main.run(new Terminal(out, err, StandardCharsets.UTF_8, false), args);
        return new Cli(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }
}
