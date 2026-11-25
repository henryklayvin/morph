package dev.morph.contracts;

/** Cooperative cancellation, also triggered by timeouts. */
public interface CancellationToken {

    boolean isCancelled();

    /** Throws {@link ErrorCode#CANCELLED} or {@link ErrorCode#TIMED_OUT}. */
    void throwIfCancelled() throws MorphException;

    /**
     * Runs {@code action} as soon as cancellation is requested (immediately if it already was), typically to
     * destroy a child process. Close the registration when the guarded section ends.
     */
    Registration onCancel(Runnable action);

    interface Registration extends AutoCloseable {
        @Override
        void close();
    }
}
