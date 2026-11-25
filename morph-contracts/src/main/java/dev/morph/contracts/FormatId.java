package dev.morph.contracts;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Stable, lower-case identifier of a format. Shared verbatim with the Web UI and the CLI. */
public record FormatId(String value) implements Comparable<FormatId> {

    private static final Pattern VALID = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");

    public FormatId {
        Objects.requireNonNull(value, "value");
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid format id");
        }
    }

    /** Case-insensitive factory used for user input such as {@code --to WEBP}. */
    public static FormatId of(String value) {
        return new FormatId(Objects.requireNonNull(value, "value").strip().toLowerCase(Locale.ROOT));
    }

    @Override
    public int compareTo(FormatId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
