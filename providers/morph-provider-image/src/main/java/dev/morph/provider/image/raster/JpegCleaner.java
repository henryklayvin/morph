package dev.morph.provider.image.raster;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Rewrites a JPEG without its metadata and without recompressing it. Used where the original stream is embedded in
 * another file (PDF): a plain copy would carry EXIF, location, thumbnails and anything appended after the picture.
 */
public final class JpegCleaner {

    private static final long MAX_BYTES = 64L << 20;

    private JpegCleaner() {
    }

    /**
     * The cleaned stream, or empty when the picture is better decoded and encoded again: embedded colour profile,
     * CMYK, unusual precision or coding, or a structure that is not entirely understood.
     */
    public static Optional<byte[]> clean(Path file) throws IOException {
        if (Files.size(file) > MAX_BYTES) {
            return Optional.empty();
        }
        byte[] in = Files.readAllBytes(file);
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        try {
            return copy(in, out) ? Optional.of(out.toByteArray()) : Optional.empty();
        } catch (IndexOutOfBoundsException e) {
            return Optional.empty(); // truncated
        }
    }

    private static boolean copy(byte[] in, ByteArrayOutputStream out) {
        if (u16(in, 0) != 0xFFD8) {
            return false;
        }
        out.write(in, 0, 2);
        boolean frameSeen = false;
        int pos = 2;
        while (true) {
            if ((in[pos] & 0xFF) != 0xFF) {
                return false;
            }
            while ((in[pos + 1] & 0xFF) == 0xFF) {
                pos++; // fill bytes
            }
            int marker = in[pos + 1] & 0xFF;
            if (marker == 0xD9) {
                out.write(0xFF);
                out.write(0xD9);
                return frameSeen; // whatever follows the end of the picture is dropped
            }
            int length = u16(in, pos + 2);
            if (length < 2 || pos + 2 + length > in.length) {
                return false;
            }
            int body = pos + 4;
            int next = pos + 2 + length;
            switch (marker) {
                case 0xC0, 0xC1, 0xC2 -> {
                    int precision = in[body] & 0xFF;
                    int components = in[body + 5] & 0xFF;
                    if (frameSeen || precision != 8 || (components != 1 && components != 3)) {
                        return false;
                    }
                    frameSeen = true;
                    out.write(in, pos, next - pos);
                }
                case 0xC4, 0xDB, 0xDD -> out.write(in, pos, next - pos); // tables
                case 0xE0 -> {
                    if (startsWith(in, body, next, "JFIF\0") && length >= 16) {
                        out.write(in, pos, 2);
                        out.write(0);
                        out.write(16);
                        out.write(in, body, 12);
                        out.write(0); // thumbnail removed
                        out.write(0);
                    }
                }
                case 0xEE -> {
                    if (startsWith(in, body, next, "Adobe")) {
                        out.write(in, pos, next - pos); // colour transform, needed to decode
                    }
                }
                case 0xE2 -> {
                    if (startsWith(in, body, next, "ICC_PROFILE\0")) {
                        return false;
                    }
                }
                case 0xDA -> {
                    if (!frameSeen) {
                        return false;
                    }
                    int end = endOfScan(in, next);
                    out.write(in, pos, end - pos);
                    next = end;
                }
                default -> {
                    boolean droppable = (marker >= 0xE1 && marker <= 0xEF) || marker == 0xFE;
                    if (!droppable) {
                        return false;
                    }
                }
            }
            pos = next;
        }
    }

    /** Position of the first real marker after entropy-coded data. */
    private static int endOfScan(byte[] in, int from) {
        int pos = from;
        while (true) {
            if ((in[pos] & 0xFF) == 0xFF) {
                int marker = in[pos + 1] & 0xFF;
                boolean partOfScan = marker == 0x00 || (marker >= 0xD0 && marker <= 0xD7);
                if (!partOfScan && marker != 0xFF) {
                    return pos;
                }
            }
            pos++;
        }
    }

    private static boolean startsWith(byte[] in, int from, int limit, String text) {
        byte[] expected = text.getBytes(StandardCharsets.ISO_8859_1);
        if (from + expected.length > limit) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (in[from + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static int u16(byte[] in, int pos) {
        return (in[pos] & 0xFF) << 8 | (in[pos + 1] & 0xFF);
    }
}
