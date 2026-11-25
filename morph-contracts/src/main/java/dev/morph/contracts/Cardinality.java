package dev.morph.contracts;

/** How many files go in and come out of a single conversion. */
public enum Cardinality {
    /** One file becomes one file (PNG to JPEG). */
    ONE_TO_ONE,
    /** One file becomes one or more files (PDF to images). */
    ONE_TO_MANY,
    /** One or more files become a single file (images to PDF). */
    MANY_TO_ONE;

    public boolean acceptsManyInputs() {
        return this == MANY_TO_ONE;
    }

    public boolean mayProduceMany() {
        return this == ONE_TO_MANY;
    }
}
