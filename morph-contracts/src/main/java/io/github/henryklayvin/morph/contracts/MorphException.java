package io.github.henryklayvin.morph.contracts;

import java.util.Objects;

/**
 * The only failure type that crosses module boundaries.
 *
 * <p>Privacy rule: the message is shown to the user and may reach HTTP responses, so it must never contain file
 * paths or file content. Front ends that know the path (the CLI) add it themselves.
 */
public class MorphException extends Exception {

    private final ErrorCode code;

    public MorphException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public MorphException(ErrorCode code, String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public ErrorCode code() {
        return code;
    }
}
