package io.github.henryklayvin.morph.contracts;

import java.nio.file.Path;
import java.util.Objects;

/** An input after inspection: its format was confirmed from the content, not from the name. */
public record SourceFile(Path path, FormatDescriptor format, long size) {
    public SourceFile {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(format, "format");
    }
}
