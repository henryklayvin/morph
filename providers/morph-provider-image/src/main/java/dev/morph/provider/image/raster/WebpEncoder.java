package dev.morph.provider.image.raster;

import dev.matrixlab.webp4j.WebPCodec;
import dev.morph.contracts.Availability;
import dev.morph.contracts.ErrorCode;
import dev.morph.contracts.MorphException;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/** The only door to libwebp. Nothing else in Morph may reference webp4j. */
public final class WebpEncoder {

    private static final int MAX_SIDE = 16_383;

    private WebpEncoder() {
    }

    public static Availability availability() {
        return Holder.AVAILABILITY;
    }

    static byte[] encode(BufferedImage image, int quality, boolean lossless) throws IOException, MorphException {
        if (!availability().available()) {
            throw new MorphException(ErrorCode.PROVIDER_UNAVAILABLE, availability().reason());
        }
        if (image.getWidth() > MAX_SIDE || image.getHeight() > MAX_SIDE) {
            throw new MorphException(ErrorCode.RESOURCE_LIMIT_EXCEEDED,
                    "WebP aceita no máximo %d pixels por lado.".formatted(MAX_SIDE));
        }
        return WebPCodec.encodeImage(image, quality, lossless);
    }

    /** Loads the native library at most once, and only when WebP is actually needed. */
    private static final class Holder {
        static final Availability AVAILABILITY = probe();

        private static Availability probe() {
            removeLeftovers();
            try {
                if (WebPCodec.isAvailable()) {
                    return Availability.ready();
                }
            } catch (RuntimeException | LinkageError e) {
                // Unsupported platform, blocked native access or a missing system library.
            }
            return Availability.unavailable("O codificador WebP nativo não pôde ser carregado em %s/%s."
                    .formatted(System.getProperty("os.name"), System.getProperty("os.arch")));
        }

        /**
         * webp4j extracts its library to the temporary directory and deletes it on exit, which Windows refuses
         * while the DLL is loaded. Without this, every run would leave a megabyte behind. DLLs in use by another
         * process cannot be deleted, so they are safe; the age check covers the moment between extraction and load.
         */
        private static void removeLeftovers() {
            if (!System.getProperty("os.name").startsWith("Windows")) {
                return;
            }
            Instant threshold = Instant.now().minus(Duration.ofMinutes(1));
            Path directory = Path.of(System.getProperty("java.io.tmpdir"));
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "webp4j-*-webp4j-windows-*.dll")) {
                for (Path file : files) {
                    if (Files.getLastModifiedTime(file).toInstant().isBefore(threshold)) {
                        deleteQuietly(file);
                    }
                }
            } catch (IOException e) {
                // Housekeeping only; never a reason to fail a conversion.
            }
        }

        private static void deleteQuietly(Path file) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                // Still loaded by another process.
            }
        }
    }
}
