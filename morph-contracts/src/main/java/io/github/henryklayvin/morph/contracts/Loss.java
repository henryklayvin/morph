package io.github.henryklayvin.morph.contracts;

import java.util.Objects;

/** Information that a conversion may lose. Declared up front so that users can decide before converting. */
public record Loss(Kind kind, String description) {

    public enum Kind { QUALITY, TRANSPARENCY, METADATA, COLOR_PROFILE, BIT_DEPTH, VECTOR_AND_TEXT }

    public Loss {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(description, "description");
    }
}
