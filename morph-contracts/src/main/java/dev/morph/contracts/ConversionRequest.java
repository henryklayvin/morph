package dev.morph.contracts;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** What the user asked for, identical for every front end. */
public record ConversionRequest(
        List<InputFile> inputs,
        FormatId target,
        ConversionOptions options,
        OutputSpec output,
        ResourceLimits limits) {

    public ConversionRequest {
        inputs = List.copyOf(inputs);
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(limits, "limits");
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("At least one input is required");
        }
    }

    /**
     * @param displayName original name, used only as a hint for detection and for naming outputs. It differs from
     *                    the path when the bytes were received by the local Web server.
     */
    public record InputFile(Path path, String displayName) {
        public InputFile {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(displayName, "displayName");
        }

        public static InputFile of(Path path) {
            Path name = path.getFileName();
            return new InputFile(path, name == null ? "" : name.toString());
        }
    }

    /**
     * @param baseName  file name without extension; must be a plain name, never a path
     * @param extension extension without the dot, or {@code null} for the format's canonical one
     */
    public record OutputSpec(Path directory, String baseName, String extension, ConflictPolicy onConflict) {
        public OutputSpec {
            Objects.requireNonNull(directory, "directory");
            Objects.requireNonNull(baseName, "baseName");
            Objects.requireNonNull(onConflict, "onConflict");
        }
    }

    public enum ConflictPolicy {
        /** Never touch an existing file. The default. */
        FAIL,
        /** Keep the existing file and pick a free name such as {@code photo (1).webp}. */
        RENAME,
        /** Atomically replace the existing file. */
        OVERWRITE
    }
}
