package io.github.henryklayvin.morph.cli;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Where the CLI writes to. Results go to the standard output and everything else to the standard error, so that
 * output can be piped. Text follows the console's encoding; JSON is always UTF-8.
 */
final class Terminal {

    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private final OutputStream rawOut;
    private final PrintStream out;
    private final PrintStream err;
    private final boolean interactive;

    Terminal(OutputStream out, OutputStream err, Charset charset, boolean interactive) {
        this.rawOut = out;
        this.out = new PrintStream(out, true, charset);
        this.err = new PrintStream(err, true, charset);
        this.interactive = interactive;
    }

    static Terminal system() {
        // A console has an encoding of its own; pipes, files and terminals such as Git Bash expect UTF-8.
        boolean console = System.console() != null;
        Charset charset = console ? System.out.charset() : StandardCharsets.UTF_8;
        return new Terminal(System.out, System.err, charset, console);
    }

    PrintStream out() {
        return out;
    }

    PrintStream err() {
        return err;
    }

    /** Whether somebody is watching, so that progress is worth drawing. */
    boolean interactive() {
        return interactive;
    }

    void json(Object value) {
        try {
            rawOut.write(JSON.writeValueAsBytes(value));
            rawOut.write('\n');
            rawOut.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes / 1024.0;
        String unit = "KiB";
        if (value >= 1024) {
            value /= 1024;
            unit = "MiB";
        }
        if (value >= 1024) {
            value /= 1024;
            unit = "GiB";
        }
        return "%.1f %s".formatted(value, unit);
    }
}
