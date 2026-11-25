package dev.morph.contracts;

import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Content signature ("magic bytes"). All parts must match relative to the same base, and the base may start
 * anywhere in {@code [0, searchWindow]} (PDF, for instance, tolerates leading bytes before {@code %PDF-}).
 *
 * @param container the signature belongs to a container family (ZIP based documents, OLE2...), so it identifies a
 *                  format only together with the file extension
 */
public record Signature(List<Part> parts, int searchWindow, boolean container) {

    /** Number of leading bytes a detector must read so that every signature can be evaluated. */
    public static final int MAX_PROBE_BYTES = 2048;

    public Signature {
        parts = List.copyOf(parts);
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("A signature needs at least one part");
        }
        int span = parts.stream().mapToInt(p -> p.offset() + p.bytes().length).max().orElseThrow();
        if (searchWindow < 0 || searchWindow + span > MAX_PROBE_BYTES) {
            throw new IllegalArgumentException("Signature exceeds the probe window");
        }
    }

    public static Signature hex(String hex) {
        return new Signature(List.of(new Part(0, hex)), 0, false);
    }

    public static Signature containerHex(String hex) {
        return new Signature(List.of(new Part(0, hex)), 0, true);
    }

    /** Offset where the signature was found, or {@code -1}. */
    public int matchOffset(byte[] head) {
        for (int base = 0; base <= searchWindow; base++) {
            if (matchesAt(head, base)) {
                return base;
            }
        }
        return -1;
    }

    private boolean matchesAt(byte[] head, int base) {
        for (Part part : parts) {
            byte[] expected = part.bytes();
            int start = base + part.offset();
            if (start + expected.length > head.length) {
                return false;
            }
            for (int i = 0; i < expected.length; i++) {
                if (head[start + i] != expected[i]) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Bytes are kept as hex so that records stay immutable and comparable by value. */
    public record Part(int offset, String hex) {
        public Part {
            Objects.requireNonNull(hex, "hex");
            if (offset < 0 || hex.isEmpty()) {
                throw new IllegalArgumentException("Invalid signature part");
            }
            HexFormat.of().parseHex(hex);
        }

        byte[] bytes() {
            return HexFormat.of().parseHex(hex);
        }
    }
}
