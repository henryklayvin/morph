package io.github.henryklayvin.morph.engine;

import io.github.henryklayvin.morph.contracts.ConversionResult;
import io.github.henryklayvin.morph.contracts.ConversionStage;
import io.github.henryklayvin.morph.contracts.ErrorCode;
import io.github.henryklayvin.morph.contracts.MorphException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/** Handle of a submitted conversion. */
public final class ConversionJob {

    public enum State { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

    private final String id = UUID.randomUUID().toString();
    private final JobToken token = new JobToken();
    private final CompletableFuture<ConversionResult> completion = new CompletableFuture<>();
    private final JobListener listener;
    private volatile State state = State.QUEUED;
    private volatile ConversionStage stage;
    private volatile double progress;
    private volatile MorphException failure;

    ConversionJob(JobListener listener) {
        this.listener = listener;
    }

    public String id() {
        return id;
    }

    public State state() {
        return state;
    }

    public Optional<ConversionStage> stage() {
        return Optional.ofNullable(stage);
    }

    /** Progress of the execution stage, in {@code [0, 1]}. */
    public double progress() {
        return progress;
    }

    public Optional<MorphException> failure() {
        return Optional.ofNullable(failure);
    }

    public Optional<ConversionResult> result() {
        return state == State.SUCCEEDED ? Optional.of(completion.join()) : Optional.empty();
    }

    /** Requests cancellation; has no effect once the result is being delivered. */
    public void cancel() {
        token.cancel(ErrorCode.CANCELLED);
    }

    /** Blocks until the job ends. */
    public ConversionResult await() throws MorphException, InterruptedException {
        try {
            return completion.get();
        } catch (ExecutionException e) {
            throw (MorphException) e.getCause();
        }
    }

    JobToken token() {
        return token;
    }

    void running() {
        state = State.RUNNING;
    }

    void enter(ConversionStage next) {
        stage = next;
        listener.onStage(next);
    }

    void progress(double fraction) {
        double value = Math.clamp(fraction, 0.0, 1.0);
        // Never move backwards and ignore changes nobody can see.
        if (value - progress >= 0.01 || (value == 1.0 && progress < 1.0)) {
            progress = value;
            listener.onProgress(value);
        }
    }

    synchronized void succeed(ConversionResult result) {
        if (!completion.isDone()) {
            state = State.SUCCEEDED;
            completion.complete(result);
        }
    }

    synchronized void fail(MorphException e) {
        if (!completion.isDone()) {
            failure = e;
            state = e.code() == ErrorCode.CANCELLED ? State.CANCELLED : State.FAILED;
            completion.completeExceptionally(e);
        }
    }

    boolean isDone() {
        return completion.isDone();
    }
}
