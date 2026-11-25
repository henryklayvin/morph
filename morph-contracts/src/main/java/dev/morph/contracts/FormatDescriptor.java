package dev.morph.contracts;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Describes a format independently of who can convert it.
 *
 * @param extensions lower-case, without the dot; the first one is canonical
 * @param signatures any of them identifies the content; container families (ZIP based documents) may share one
 */
public record FormatDescriptor(
        FormatId id,
        String displayName,
        FormatCategory category,
        List<String> extensions,
        String mediaType,
        List<Signature> signatures) {

    public FormatDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(mediaType, "mediaType");
        extensions = List.copyOf(extensions);
        signatures = List.copyOf(signatures);
        if (extensions.isEmpty()) {
            throw new IllegalArgumentException("A format needs at least one extension");
        }
        for (String extension : extensions) {
            if (!extension.equals(extension.toLowerCase(Locale.ROOT)) || extension.startsWith(".")) {
                throw new IllegalArgumentException("Extensions must be lower-case and without the dot");
            }
        }
    }

    public String canonicalExtension() {
        return extensions.getFirst();
    }

    /** Whether the content alone cannot tell this format apart from others of the same container family. */
    public boolean containerBased() {
        return signatures.stream().anyMatch(Signature::container);
    }

    /** Smallest offset at which one of the signatures was found, or {@code -1}. */
    public int matchOffset(byte[] head) {
        return signatures.stream().mapToInt(s -> s.matchOffset(head)).filter(offset -> offset >= 0)
                .min().orElse(-1);
    }
}
