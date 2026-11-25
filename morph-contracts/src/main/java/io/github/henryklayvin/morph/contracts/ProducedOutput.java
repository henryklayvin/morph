package io.github.henryklayvin.morph.contracts;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * A file written by a provider inside the isolated work directory.
 *
 * @param properties informational facts for the user, such as width, height or pages
 * @param check      mandatory independent verification of the written file
 */
public record ProducedOutput(Path file, Map<String, String> properties, OutputCheck check) {

    public ProducedOutput {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(check, "check");
        properties = Map.copyOf(properties);
    }

    /** Re-opens the file and compares it with what the conversion promised; throws when it does not match. */
    @FunctionalInterface
    public interface OutputCheck {
        void verify(Path file) throws Exception;
    }
}
