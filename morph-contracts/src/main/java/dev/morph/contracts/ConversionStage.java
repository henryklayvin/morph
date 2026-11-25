package dev.morph.contracts;

/** The fixed pipeline every conversion goes through, in order. */
public enum ConversionStage {
    INSPECTION,
    COMPATIBILITY,
    PLANNING,
    VALIDATION,
    EXECUTION,
    VERIFICATION,
    DELIVERY,
    CLEANUP
}
