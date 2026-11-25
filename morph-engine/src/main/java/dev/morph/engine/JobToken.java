package dev.morph.engine;

import dev.morph.contracts.CancellationToken;
import dev.morph.contracts.ErrorCode;
import dev.morph.contracts.MorphException;
import java.util.ArrayList;
import java.util.List;

/**
 * Cancellation state of one job. Once {@link #commit()} succeeds the result is being delivered and cancellation no
 * longer has any effect, so a job never ends up both delivered and reported as cancelled.
 */
final class JobToken implements CancellationToken {

    private final List<Runnable> actions = new ArrayList<>();
    private ErrorCode reason;
    private boolean committed;

    /** @return whether this call is the one that cancelled the job */
    boolean cancel(ErrorCode why) {
        List<Runnable> toRun;
        synchronized (this) {
            if (committed || reason != null) {
                return false;
            }
            reason = why;
            toRun = List.copyOf(actions);
            actions.clear();
        }
        toRun.forEach(JobToken::runQuietly);
        return true;
    }

    synchronized void commit() throws MorphException {
        throwIfCancelled();
        committed = true;
    }

    @Override
    public synchronized boolean isCancelled() {
        return reason != null;
    }

    @Override
    public synchronized void throwIfCancelled() throws MorphException {
        if (reason != null) {
            throw toException();
        }
    }

    synchronized MorphException toException() {
        return reason == ErrorCode.TIMED_OUT
                ? new MorphException(ErrorCode.TIMED_OUT, "A conversão excedeu o tempo limite.")
                : new MorphException(ErrorCode.CANCELLED, "A conversão foi cancelada.");
    }

    @Override
    public Registration onCancel(Runnable action) {
        synchronized (this) {
            if (reason == null) {
                actions.add(action);
                return () -> {
                    synchronized (JobToken.this) {
                        actions.remove(action);
                    }
                };
            }
        }
        runQuietly(action);
        return () -> { };
    }

    private static void runQuietly(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // One failing cleanup action must not prevent the others from running.
        }
    }
}
