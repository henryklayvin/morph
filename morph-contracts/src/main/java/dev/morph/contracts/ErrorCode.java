package dev.morph.contracts;

/** Stable machine-readable failure reasons, shared by the CLI (JSON and exit codes) and the local HTTP API. */
public enum ErrorCode {

    INVALID_REQUEST(Category.USAGE),
    INVALID_OPTION(Category.USAGE),
    UNKNOWN_FORMAT(Category.USAGE),

    INPUT_NOT_FOUND(Category.INPUT),
    INPUT_NOT_READABLE(Category.INPUT),
    INPUT_EMPTY(Category.INPUT),
    UNRECOGNIZED_FORMAT(Category.INPUT),
    CORRUPT_INPUT(Category.INPUT),
    PROTECTED_INPUT(Category.INPUT),

    UNSUPPORTED_CONVERSION(Category.UNSUPPORTED),
    PROVIDER_UNAVAILABLE(Category.UNSUPPORTED),

    RESOURCE_LIMIT_EXCEEDED(Category.LIMIT),
    TIMED_OUT(Category.LIMIT),

    OUTPUT_EXISTS(Category.OUTPUT),
    OUTPUT_WRITE_FAILED(Category.OUTPUT),

    CONVERSION_FAILED(Category.CONVERSION),
    OUTPUT_VERIFICATION_FAILED(Category.CONVERSION),

    CANCELLED(Category.CANCELLED),

    INTERNAL_ERROR(Category.INTERNAL);

    public enum Category { USAGE, INPUT, UNSUPPORTED, LIMIT, OUTPUT, CONVERSION, CANCELLED, INTERNAL }

    private final Category category;

    ErrorCode(Category category) {
        this.category = category;
    }

    public Category category() {
        return category;
    }
}
