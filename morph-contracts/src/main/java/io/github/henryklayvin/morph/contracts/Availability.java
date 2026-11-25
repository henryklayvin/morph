package io.github.henryklayvin.morph.contracts;

import java.util.Objects;

/** Whether something can run on this machine, and why not. */
public record Availability(boolean available, String reason) {

    public Availability {
        Objects.requireNonNull(reason, "reason");
    }

    public static Availability ready() {
        return new Availability(true, "");
    }

    public static Availability unavailable(String reason) {
        return new Availability(false, reason);
    }
}
