package dev.morph.contracts;

import java.util.List;
import java.util.Objects;

/**
 * One row of the compatibility matrix: a provider's promise to convert {@code source} into {@code target}.
 *
 * @param priority higher wins when several available providers offer the same operation
 */
public record Capability(
        FormatId source,
        FormatId target,
        Cardinality cardinality,
        int priority,
        List<OptionSpec<?>> options,
        List<Loss> losses,
        List<String> limitations) {

    public Capability {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(cardinality, "cardinality");
        options = List.copyOf(options);
        losses = List.copyOf(losses);
        limitations = List.copyOf(limitations);
        if (source.equals(target)) {
            throw new IllegalArgumentException("Source and target must differ");
        }
        if (options.stream().map(OptionSpec::key).distinct().count() != options.size()) {
            throw new IllegalArgumentException("Duplicate option key");
        }
    }

    /** Stable operation identifier, the same one the public Web build must use for its browser conversions. */
    public String operationId() {
        return source + "-to-" + target;
    }
}
