package io.github.henryklayvin.morph.local;

import io.github.henryklayvin.morph.contracts.ErrorCode;
import io.github.henryklayvin.morph.contracts.MorphException;

/** HTTP-level failure with the same error shape used everywhere else. */
final class ApiException extends RuntimeException {

    private final int status;
    private final String code;

    ApiException(int status, String code, String message) {
        super(message, null, false, false);
        this.status = status;
        this.code = code;
    }

    static ApiException of(MorphException e) {
        int status = switch (e.code().category()) {
            case USAGE -> 400;
            case INPUT, UNSUPPORTED, CONVERSION -> 422;
            case LIMIT -> 413;
            case CANCELLED -> 409;
            case OUTPUT, INTERNAL -> 500;
        };
        return new ApiException(status, e.code().name(), e.getMessage());
    }

    static ApiException badRequest(String message) {
        return new ApiException(400, ErrorCode.INVALID_REQUEST.name(), message);
    }

    static ApiException notFound() {
        return new ApiException(404, "NOT_FOUND", "Recurso não encontrado.");
    }

    int status() {
        return status;
    }

    String code() {
        return code;
    }
}
