package io.github.henryklayvin.morph.engine;

import io.github.henryklayvin.morph.contracts.ConversionStage;

/** Observes a job. Called from worker threads; implementations must be fast and must not throw. */
public interface JobListener {

    JobListener NONE = new JobListener() { };

    default void onStage(ConversionStage stage) {
    }

    default void onProgress(double fraction) {
    }
}
