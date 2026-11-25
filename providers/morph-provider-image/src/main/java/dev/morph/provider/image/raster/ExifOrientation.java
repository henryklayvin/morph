package dev.morph.provider.image.raster;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * EXIF orientation of JPEG files. Phones store photos sideways plus a flag, and Morph drops metadata, so the flag
 * has to be applied to the pixels or the result would come out rotated.
 *
 * <p>This is a deliberately small and bounded reader: it looks at one tag and treats anything odd as "not rotated".
 */
public final class ExifOrientation {

    public static final int NORMAL = 1;
    private static final int TAG_ORIENTATION = 0x0112;
    private static final int TYPE_SHORT = 3;
    private static final int MAX_SEGMENTS = 256;

    private ExifOrientation() {
    }

    /** Orientation in {@code [1, 8]}; {@link #NORMAL} when absent or malformed. */
    public static int ofJpeg(Path file) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readUnsignedShort() != 0xFFD8) {
                return NORMAL;
            }
            for (int i = 0; i < MAX_SEGMENTS; i++) {
                int marker = in.readUnsignedShort();
                if ((marker & 0xFF00) != 0xFF00 || marker == 0xFFDA || marker == 0xFFD9) {
                    return NORMAL; // image data reached, or not a marker
                }
                int length = in.readUnsignedShort() - 2;
                if (length < 0) {
                    return NORMAL;
                }
                if (marker == 0xFFE1) {
                    byte[] data = in.readNBytes(length);
                    if (startsWithExif(data)) {
                        return parse(ByteBuffer.wrap(data, 6, data.length - 6).slice());
                    }
                } else {
                    in.skipNBytes(length);
                }
            }
        } catch (IOException | BufferUnderflowException | IllegalArgumentException | IndexOutOfBoundsException e) {
            // Broken metadata must not prevent the conversion of a readable image.
        }
        return NORMAL;
    }

    /** Returns the image as it should be seen. Expects TYPE_INT_RGB or TYPE_INT_ARGB. */
    public static BufferedImage apply(BufferedImage image, int orientation) {
        if (orientation == NORMAL) {
            return image;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        boolean swap = orientation >= 5;
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, image.getType());
        int[] row = new int[w];
        int[] target = Pixels.data(out);
        int outWidth = out.getWidth();
        for (int y = 0; y < h; y++) {
            image.getRaster().getDataElements(0, y, w, 1, row);
            for (int x = 0; x < w; x++) {
                int dx = switch (orientation) {
                    case 2, 3 -> w - 1 - x;
                    case 4 -> x;
                    case 5, 8 -> y;
                    default -> h - 1 - y; // 6, 7
                };
                int dy = switch (orientation) {
                    case 2 -> y;
                    case 3, 4 -> h - 1 - y;
                    case 5, 6 -> x;
                    default -> w - 1 - x; // 7, 8
                };
                target[dy * outWidth + dx] = row[x];
            }
        }
        return out;
    }

    private static boolean startsWithExif(byte[] data) {
        return data.length > 14 && data[0] == 'E' && data[1] == 'x' && data[2] == 'i' && data[3] == 'f'
                && data[4] == 0 && data[5] == 0;
    }

    private static int parse(ByteBuffer tiff) {
        int byteOrder = tiff.getShort(0) & 0xFFFF;
        if (byteOrder == 0x4949) {
            tiff.order(ByteOrder.LITTLE_ENDIAN);
        } else if (byteOrder != 0x4D4D) {
            return NORMAL;
        }
        if (tiff.getShort(2) != 42) {
            return NORMAL;
        }
        int ifd = tiff.getInt(4);
        int entries = tiff.getShort(ifd) & 0xFFFF;
        for (int i = 0; i < entries; i++) {
            int entry = ifd + 2 + i * 12;
            if ((tiff.getShort(entry) & 0xFFFF) == TAG_ORIENTATION) {
                boolean valid = tiff.getShort(entry + 2) == TYPE_SHORT && tiff.getInt(entry + 4) == 1;
                int value = tiff.getShort(entry + 8) & 0xFFFF;
                return valid && value >= 1 && value <= 8 ? value : NORMAL;
            }
        }
        return NORMAL;
    }
}
