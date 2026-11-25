package io.github.henryklayvin.morph.contracts;

import java.util.Map;

/** Raw, not yet validated settings exactly as the user provided them. */
public record ConversionOptions(Map<String, String> values) {

    public static final ConversionOptions NONE = new ConversionOptions(Map.of());

    public ConversionOptions {
        values = Map.copyOf(values);
    }
}
