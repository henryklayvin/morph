package dev.morph.contracts;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A verified and delivered conversion. */
public record ConversionResult(
        String operationId,
        String providerId,
        List<OutputFile> outputs,
        List<String> warnings,
        Duration elapsed) {

    public ConversionResult {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(providerId, "providerId");
        outputs = List.copyOf(outputs);
        warnings = List.copyOf(warnings);
        Objects.requireNonNull(elapsed, "elapsed");
    }

    public record OutputFile(Path path, FormatId format, long size, Map<String, String> properties) {
        public OutputFile {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(format, "format");
            properties = Map.copyOf(properties);
        }
    }
}
