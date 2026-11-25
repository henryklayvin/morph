package io.github.henryklayvin.morph.provider.image.raster;

import io.github.henryklayvin.morph.contracts.CancellationToken;
import io.github.henryklayvin.morph.contracts.ErrorCode;
import io.github.henryklayvin.morph.contracts.FormatDescriptor;
import io.github.henryklayvin.morph.contracts.MorphException;
import io.github.henryklayvin.morph.contracts.ResourceLimits;
import io.github.henryklayvin.morph.contracts.StandardFormats;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.function.DoubleConsumer;
import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.event.IIOReadProgressListener;
import javax.imageio.stream.ImageInputStream;

/**
 * Guarded decoding. Dimensions are read from the header and checked against the limits before a single pixel is
 * allocated, because a few kilobytes of PNG can declare a picture that needs gigabytes of memory.
 *
 * <p>Only JVM-hosted decoders (JDK and TwelveMonkeys) ever see untrusted bytes; libwebp is used for encoding only.
 */
public final class RasterReader {

    static {
        // ImageIO's default is to spool images into the system temporary directory, outside Morph's control.
        ImageIO.setUseCache(false);
    }

    private RasterReader() {
    }

    /** Displayed size, that is, after applying the orientation flag. */
    public record Size(int width, int height) {
    }

    public static Size probe(Path file, FormatDescriptor format, ResourceLimits limits)
            throws IOException, MorphException {
        return withReader(file, format, reader -> {
            Size stored = checkedSize(reader, limits);
            return orientation(file, format) >= 5 ? new Size(stored.height(), stored.width()) : stored;
        });
    }

    /** Decodes to canonical layout with the orientation already applied. */
    public static BufferedImage read(Path file, FormatDescriptor format, ResourceLimits limits,
            CancellationToken cancellation, DoubleConsumer progress) throws IOException, MorphException {
        BufferedImage decoded = withReader(file, format, reader -> {
            checkedSize(reader, limits);
            checkMemory(reader, limits);
            reader.addIIOReadProgressListener(new Progress(cancellation, progress));
            BufferedImage image = reader.read(0);
            cancellation.throwIfCancelled();
            if (image == null) {
                throw corrupt(null);
            }
            return image;
        });
        return ExifOrientation.apply(Pixels.normalize(decoded), orientation(file, format));
    }

    private static int orientation(Path file, FormatDescriptor format) {
        return format.equals(StandardFormats.JPEG) ? ExifOrientation.ofJpeg(file) : ExifOrientation.NORMAL;
    }

    private static Size checkedSize(ImageReader reader, ResourceLimits limits) throws IOException, MorphException {
        int width = reader.getWidth(0);
        int height = reader.getHeight(0);
        if (width <= 0 || height <= 0) {
            throw corrupt(null);
        }
        if ((long) width * height > limits.maxPixels()) {
            throw new MorphException(ErrorCode.RESOURCE_LIMIT_EXCEEDED,
                    "A imagem declara %d x %d pixels e excede o limite de %d megapixels."
                            .formatted(width, height, limits.maxPixels() / 1_000_000));
        }
        return new Size(width, height);
    }

    private static void checkMemory(ImageReader reader, ResourceLimits limits) throws IOException, MorphException {
        long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
        long bytesPerPixel = 4; // canonical copy
        ImageTypeSpecifier raw = reader.getRawImageType(0);
        bytesPerPixel += raw == null ? 4 : Math.max(1, (raw.getColorModel().getPixelSize() + 7) / 8);
        if (pixels * bytesPerPixel > limits.maxWorkingMemoryBytes()) {
            throw new MorphException(ErrorCode.RESOURCE_LIMIT_EXCEEDED,
                    "A imagem precisaria de cerca de %d MiB de memória e excede o limite de %d MiB."
                            .formatted(pixels * bytesPerPixel >> 20, limits.maxWorkingMemoryBytes() >> 20));
        }
    }

    @FunctionalInterface
    private interface ReaderAction<T> {
        T apply(ImageReader reader) throws IOException, MorphException;
    }

    private static <T> T withReader(Path file, FormatDescriptor format, ReaderAction<T> action)
            throws IOException, MorphException {
        ImageReader reader = reader(format);
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            if (in == null) {
                throw new IOException("Unreadable input");
            }
            reader.setInput(in, true, true);
            return action.apply(reader);
        } catch (IIOException e) {
            throw corrupt(e);
        } catch (RuntimeException e) {
            // Decoders signal many kinds of malformed data with unchecked exceptions.
            throw corrupt(e);
        } finally {
            reader.dispose();
        }
    }

    private static ImageReader reader(FormatDescriptor format) throws MorphException {
        // PNG is served by the JDK; JPEG and WebP by TwelveMonkeys (CMYK, odd JPEGs, and WebP are beyond the JDK).
        boolean wantTwelveMonkeys = !format.equals(StandardFormats.PNG);
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format.id().value());
        while (readers.hasNext()) {
            ImageReader reader = readers.next();
            if (reader.getClass().getName().startsWith("com.twelvemonkeys.") == wantTwelveMonkeys) {
                return reader;
            }
            reader.dispose();
        }
        throw new MorphException(ErrorCode.PROVIDER_UNAVAILABLE,
                "O leitor de %s não está disponível.".formatted(format.displayName()));
    }

    private static MorphException corrupt(Throwable cause) {
        return new MorphException(ErrorCode.CORRUPT_INPUT,
                "A imagem está corrompida ou usa um recurso não suportado.", cause);
    }

    /** Aborts from inside the callback: readers are single-threaded and reject aborts from other threads. */
    private record Progress(CancellationToken cancellation, DoubleConsumer progress)
            implements IIOReadProgressListener {
        @Override
        public void imageProgress(ImageReader source, float percentageDone) {
            if (cancellation.isCancelled()) {
                source.abort();
            }
            progress.accept(percentageDone / 100.0);
        }

        @Override
        public void sequenceStarted(ImageReader source, int minIndex) {
        }

        @Override
        public void sequenceComplete(ImageReader source) {
        }

        @Override
        public void imageStarted(ImageReader source, int imageIndex) {
        }

        @Override
        public void imageComplete(ImageReader source) {
        }

        @Override
        public void thumbnailStarted(ImageReader source, int imageIndex, int thumbnailIndex) {
        }

        @Override
        public void thumbnailProgress(ImageReader source, float percentageDone) {
        }

        @Override
        public void thumbnailComplete(ImageReader source) {
        }

        @Override
        public void readAborted(ImageReader source) {
        }
    }
}
