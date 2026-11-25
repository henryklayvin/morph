package io.github.henryklayvin.morph.contracts;

import java.time.Duration;
import java.util.Objects;

/**
 * Ceilings applied to a conversion. They protect the user's machine from malformed or hostile files (decompression
 * bombs) and are checked before large allocations, never after.
 */
public record ResourceLimits(
        long maxInputBytes,
        long maxOutputBytes,
        long maxPixels,
        long maxWorkingMemoryBytes,
        int maxPages,
        Duration timeout) {

    public ResourceLimits {
        Objects.requireNonNull(timeout, "timeout");
        if (maxInputBytes <= 0 || maxOutputBytes <= 0 || maxPixels <= 0 || maxWorkingMemoryBytes <= 0
                || maxPages <= 0 || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Limits must be positive");
        }
    }

    /** Defaults sized for desktops; working memory follows the heap the JVM was actually given. */
    public static ResourceLimits defaults() {
        return new ResourceLimits(
                512L << 20,
                2L << 30,
                120_000_000L,
                Math.max(64L << 20, Runtime.getRuntime().maxMemory() / 2),
                500,
                Duration.ofMinutes(5));
    }

    public ResourceLimits withTimeout(Duration newTimeout) {
        return new ResourceLimits(maxInputBytes, maxOutputBytes, maxPixels, maxWorkingMemoryBytes, maxPages,
                newTimeout);
    }
}
