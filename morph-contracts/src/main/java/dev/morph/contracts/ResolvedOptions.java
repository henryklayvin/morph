package dev.morph.contracts;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Settings after validation against the capability's specs, with defaults filled in. */
public final class ResolvedOptions {

    private final Map<OptionSpec<?>, Object> values;

    private ResolvedOptions(Map<OptionSpec<?>, Object> values) {
        this.values = Collections.unmodifiableMap(values);
    }

    /** Unknown keys are rejected: silently ignoring a misspelled setting would produce the wrong file. */
    public static ResolvedOptions resolve(List<OptionSpec<?>> specs, ConversionOptions raw) throws MorphException {
        Map<String, OptionSpec<?>> byKey = specs.stream().collect(Collectors.toMap(OptionSpec::key, s -> s));
        for (String key : raw.values().keySet()) {
            if (!byKey.containsKey(key)) {
                String accepted = specs.isEmpty()
                        ? "esta conversão não possui opções"
                        : "opções aceitas: " + specs.stream().map(OptionSpec::key).sorted()
                                .collect(Collectors.joining(", "));
                throw new MorphException(ErrorCode.INVALID_OPTION,
                        "Opção desconhecida para esta conversão (%s).".formatted(accepted));
            }
        }
        Map<OptionSpec<?>, Object> resolved = new LinkedHashMap<>();
        for (OptionSpec<?> spec : specs) {
            String value = raw.values().get(spec.key());
            resolved.put(spec, value == null ? spec.defaultValue() : spec.parse(value));
        }
        return new ResolvedOptions(resolved);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(OptionSpec<T> spec) {
        Object value = values.get(spec);
        if (value == null) {
            throw new IllegalArgumentException("Option not declared by this capability: " + spec.key());
        }
        return (T) value;
    }

    /** Effective settings by key, for reporting. */
    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        values.forEach((spec, value) -> map.put(spec.key(), value));
        return Collections.unmodifiableMap(map);
    }
}
