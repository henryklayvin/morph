package dev.morph.cli;

import dev.morph.contracts.ErrorCode;
import java.util.List;

/** Process exit codes. Part of the public interface: scripts rely on them, so existing values never change. */
final class ExitCodes {

    static final int OK = 0;
    static final int INTERNAL = 1;
    static final int USAGE = 2;
    static final int INPUT = 3;
    static final int UNSUPPORTED = 4;
    static final int LIMIT = 5;
    static final int OUTPUT = 6;
    static final int CONVERSION = 7;
    /** Batch in which some conversions worked and others did not. */
    static final int PARTIAL = 8;
    static final int CANCELLED = 130;

    private ExitCodes() {
    }

    static int of(ErrorCode code) {
        return switch (code.category()) {
            case USAGE -> USAGE;
            case INPUT -> INPUT;
            case UNSUPPORTED -> UNSUPPORTED;
            case LIMIT -> LIMIT;
            case OUTPUT -> OUTPUT;
            case CONVERSION -> CONVERSION;
            case CANCELLED -> CANCELLED;
            case INTERNAL -> INTERNAL;
        };
    }

    /** @param failures one exit code per failed conversion */
    static int ofBatch(int total, List<Integer> failures) {
        if (failures.isEmpty()) {
            return OK;
        }
        if (failures.size() < total) {
            return PARTIAL;
        }
        return failures.stream().distinct().count() == 1 ? failures.getFirst() : CONVERSION;
    }
}
