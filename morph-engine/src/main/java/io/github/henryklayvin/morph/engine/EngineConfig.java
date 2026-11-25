package io.github.henryklayvin.morph.engine;

import io.github.henryklayvin.morph.contracts.ResourceLimits;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * @param workers         simultaneous conversions; work is CPU-bound, hence platform threads and a small number
 * @param cancelGrace     how long a job may ignore a cancellation before being reported as finished anyway
 */
public record EngineConfig(Path temporaryRoot, int workers, Duration cancelGrace, ResourceLimits limits) {

    public EngineConfig {
        Objects.requireNonNull(temporaryRoot, "temporaryRoot");
        Objects.requireNonNull(cancelGrace, "cancelGrace");
        Objects.requireNonNull(limits, "limits");
        if (workers < 1) {
            throw new IllegalArgumentException("At least one worker is required");
        }
    }

    public static EngineConfig withWorkers(int workers) {
        ResourceLimits base = ResourceLimits.defaults();
        // All workers together may use 60% of the heap; the rest is headroom for the JVM and the front end.
        long perJob = Math.max(64L << 20, Runtime.getRuntime().maxMemory() * 6 / 10 / workers);
        ResourceLimits limits = new ResourceLimits(base.maxInputBytes(), base.maxOutputBytes(), base.maxPixels(),
                perJob, base.maxPages(), base.timeout());
        return new EngineConfig(Path.of(System.getProperty("java.io.tmpdir")), workers, Duration.ofSeconds(5),
                limits);
    }

    public static int defaultWorkers() {
        return Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 1, 4);
    }
}
