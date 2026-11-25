package io.github.henryklayvin.morph.engine;

import io.github.henryklayvin.morph.contracts.ErrorCode;
import io.github.henryklayvin.morph.contracts.FormatDescriptor;
import io.github.henryklayvin.morph.contracts.FormatId;
import io.github.henryklayvin.morph.contracts.MorphException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Immutable catalogue of known formats. Conflicting declarations fail at start-up instead of at conversion time. */
public final class FormatRegistry {

    private final Map<FormatId, FormatDescriptor> byId;

    public FormatRegistry(Collection<FormatDescriptor> formats) {
        Map<FormatId, FormatDescriptor> index = new TreeMap<>();
        for (FormatDescriptor format : formats) {
            FormatDescriptor previous = index.putIfAbsent(format.id(), format);
            if (previous != null && !previous.equals(format)) {
                throw new IllegalStateException("Conflicting declarations of format " + format.id());
            }
        }
        this.byId = Map.copyOf(index);
    }

    public Optional<FormatDescriptor> find(FormatId id) {
        return Optional.ofNullable(byId.get(id));
    }

    public FormatDescriptor require(FormatId id) throws MorphException {
        FormatDescriptor format = byId.get(id);
        if (format == null) {
            throw new MorphException(ErrorCode.UNKNOWN_FORMAT, "Formato desconhecido: %s.".formatted(id));
        }
        return format;
    }

    /** Resolves what a person types: a format id or any of its extensions ({@code jpg}, {@code .JPG}). */
    public FormatDescriptor resolve(String userInput) throws MorphException {
        String text = userInput.strip().toLowerCase(Locale.ROOT);
        String name = text.startsWith(".") ? text.substring(1) : text;
        return all().stream()
                .filter(f -> f.id().value().equals(name) || f.extensions().contains(name))
                .findFirst()
                .orElseThrow(() -> new MorphException(ErrorCode.UNKNOWN_FORMAT,
                        "Formato desconhecido. Use 'morph formats' para ver os formatos disponíveis."));
    }

    public List<FormatDescriptor> all() {
        return byId.values().stream().sorted((a, b) -> a.id().compareTo(b.id())).toList();
    }
}
