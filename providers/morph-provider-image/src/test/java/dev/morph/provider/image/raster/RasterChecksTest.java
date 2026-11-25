package dev.morph.provider.image.raster;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.morph.contracts.CancellationToken;
import dev.morph.contracts.ErrorCode;
import dev.morph.contracts.MorphException;
import dev.morph.contracts.ResourceLimits;
import dev.morph.contracts.StandardFormats;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A verification that cannot fail proves nothing, so these tests feed it wrong outputs. */
class RasterChecksTest {

    private static final ResourceLimits LIMITS = ResourceLimits.defaults();
    private static final CancellationToken NEVER = new CancellationToken() {
        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public void throwIfCancelled() {
        }

        @Override
        public Registration onCancel(Runnable action) {
            return () -> { };
        }
    };

    @TempDir
    Path temp;

    @Test
    void exactDetectsASingleChangedPixel() throws Exception {
        BufferedImage image = gradient();
        Path file = temp.resolve("a.png");
        RasterWriter.writePng(image, file);
        var check = RasterChecks.exact(image, StandardFormats.PNG, LIMITS, NEVER);
        assertDoesNotThrow(() -> check.verify(file));

        BufferedImage tampered = gradient();
        tampered.setRGB(10, 10, tampered.getRGB(10, 10) ^ 1);
        RasterWriter.writePng(tampered, file);
        assertRejected(() -> check.verify(file));
    }

    @Test
    void similarDetectsBlackFramesSwappedChannelsAndRotation() throws Exception {
        BufferedImage image = gradient();
        var check = RasterChecks.similar(image, StandardFormats.PNG, LIMITS, NEVER);
        Path file = temp.resolve("b.png");

        RasterWriter.writePng(new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), file);
        assertRejected(() -> check.verify(file));

        BufferedImage swapped = gradient();
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                int p = swapped.getRGB(x, y);
                swapped.setRGB(x, y, (p & 0xFF00FF00) | (p & 0xFF) << 16 | (p >> 16 & 0xFF));
            }
        }
        RasterWriter.writePng(swapped, file);
        assertRejected(() -> check.verify(file));

        RasterWriter.writePng(ExifOrientation.apply(image, 3), file);
        assertRejected(() -> check.verify(file));

        RasterWriter.writePng(new BufferedImage(64, 63, BufferedImage.TYPE_INT_RGB), file);
        assertRejected(() -> check.verify(file));
    }

    @Test
    void invisibleColoursOfTransparentPixelsAreIrrelevant() throws Exception {
        BufferedImage a = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        BufferedImage b = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        a.setRGB(1, 1, 0x00FF0000);
        b.setRGB(1, 1, 0x0000FF00);
        Path file = temp.resolve("c.png");
        RasterWriter.writePng(b, file);
        assertDoesNotThrow(() -> RasterChecks.exact(a, StandardFormats.PNG, LIMITS, NEVER).verify(file));
    }

    /** Every orientation, applied to a picture stored with its inverse, must give back the upright picture. */
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    void orientationsAreInvertible(int orientation) {
        int inverse = switch (orientation) {
            case 6 -> 8;
            case 8 -> 6;
            default -> orientation;
        };
        BufferedImage original = new BufferedImage(5, 3, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < 15; i++) {
            original.setRGB(i % 5, i / 5, 0xFF000000 | i * 1000);
        }
        BufferedImage back = ExifOrientation.apply(ExifOrientation.apply(original, orientation), inverse);
        assertEquals(5, back.getWidth());
        for (int i = 0; i < 15; i++) {
            assertEquals(original.getRGB(i % 5, i / 5), back.getRGB(i % 5, i / 5));
        }
    }

    private static void assertRejected(org.junit.jupiter.api.function.Executable executable) {
        assertEquals(ErrorCode.OUTPUT_VERIFICATION_FAILED, assertThrows(MorphException.class, executable).code());
    }

    private static BufferedImage gradient() {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                image.setRGB(x, y, 0xFF000000 | (200 + y / 2) << 16 | x * 3 << 8 | y * 2);
            }
        }
        return image;
    }
}
