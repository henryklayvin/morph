package io.github.henryklayvin.morph.provider.image.raster;

import io.github.henryklayvin.morph.contracts.CancellationToken;
import io.github.henryklayvin.morph.contracts.ErrorCode;
import io.github.henryklayvin.morph.contracts.FormatDescriptor;
import io.github.henryklayvin.morph.contracts.MorphException;
import io.github.henryklayvin.morph.contracts.ProducedOutput.OutputCheck;
import io.github.henryklayvin.morph.contracts.ResourceLimits;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Verification of written images. The expected facts are computed before encoding and compared with a full decode
 * of the file on disk, so a check does not keep the source picture in memory.
 */
public final class RasterChecks {

    private RasterChecks() {
    }

    /** For lossless outputs: every visible pixel must survive unchanged. */
    public static OutputCheck exact(BufferedImage expected, FormatDescriptor format, ResourceLimits limits,
            CancellationToken cancellation) {
        int width = expected.getWidth();
        int height = expected.getHeight();
        byte[] digest = digest(expected);
        return file -> {
            BufferedImage actual = RasterReader.read(file, format, limits, cancellation, p -> { });
            requireSize(actual, width, height);
            if (!Arrays.equals(digest, digest(actual))) {
                throw mismatch("os pixels diferem do original");
            }
        };
    }

    /**
     * For lossy outputs: the picture must still look like the source at a coarse scale. Catches what "the file
     * exists" does not, such as swapped channels, black frames or a missing rotation.
     */
    public static OutputCheck similar(BufferedImage expected, FormatDescriptor format, ResourceLimits limits,
            CancellationToken cancellation) {
        int width = expected.getWidth();
        int height = expected.getHeight();
        Fingerprint fingerprint = Fingerprint.of(expected);
        return file -> {
            BufferedImage actual = RasterReader.read(file, format, limits, cancellation, p -> { });
            requireSize(actual, width, height);
            if (!fingerprint.resembles(Fingerprint.of(actual))) {
                throw mismatch("a imagem não se parece com o original");
            }
        };
    }

    public static MorphException mismatch(String why) {
        return new MorphException(ErrorCode.OUTPUT_VERIFICATION_FAILED,
                "O resultado foi descartado porque não passou na verificação: %s.".formatted(why));
    }

    private static void requireSize(BufferedImage actual, int width, int height) throws MorphException {
        if (actual.getWidth() != width || actual.getHeight() != height) {
            throw mismatch("dimensões diferentes das esperadas");
        }
    }

    private static byte[] digest(BufferedImage image) {
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        int width = image.getWidth();
        boolean alpha = image.getColorModel().hasAlpha();
        int[] pixels = Pixels.data(image);
        ByteBuffer row = ByteBuffer.allocate(width * 4);
        for (int y = 0; y < image.getHeight(); y++) {
            row.clear();
            for (int x = 0; x < width; x++) {
                int pixel = alpha ? pixels[y * width + x] : pixels[y * width + x] | 0xFF000000;
                // The colour of a fully transparent pixel is invisible and encoders may discard it.
                row.putInt(pixel >>> 24 == 0 ? 0 : pixel);
            }
            sha256.update(row.array());
        }
        return sha256.digest();
    }
}
