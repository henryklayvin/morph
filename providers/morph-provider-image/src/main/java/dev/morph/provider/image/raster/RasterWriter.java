package dev.morph.provider.image.raster;

import dev.morph.contracts.ErrorCode;
import dev.morph.contracts.FormatDescriptor;
import dev.morph.contracts.MorphException;
import dev.morph.contracts.StandardFormats;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/** Encoding of canonical images. Outputs carry pixels only: no metadata of the source or of the machine. */
public final class RasterWriter {

    private static final int JPEG_MAX_SIDE = 65_500;

    private RasterWriter() {
    }

    public static void writePng(BufferedImage image, Path file) throws IOException, MorphException {
        write(image, file, StandardFormats.PNG, -1);
    }

    /** @param quality 1 to 100; the image must be opaque */
    public static void writeJpeg(BufferedImage image, Path file, int quality) throws IOException, MorphException {
        if (image.getColorModel().hasAlpha()) {
            throw new IllegalArgumentException("JPEG cannot store transparency");
        }
        if (image.getWidth() > JPEG_MAX_SIDE || image.getHeight() > JPEG_MAX_SIDE) {
            throw new MorphException(ErrorCode.RESOURCE_LIMIT_EXCEEDED,
                    "JPEG aceita no máximo %d pixels por lado.".formatted(JPEG_MAX_SIDE));
        }
        write(image, file, StandardFormats.JPEG, quality / 100f);
    }

    public static void writeWebp(BufferedImage image, Path file, int quality, boolean lossless)
            throws IOException, MorphException {
        Files.write(file, WebpEncoder.encode(image, quality, lossless));
    }

    private static void write(BufferedImage image, Path file, FormatDescriptor format, float quality)
            throws IOException, MorphException {
        ImageWriter writer = writer(format);
        try (ImageOutputStream out = ImageIO.createImageOutputStream(file.toFile())) {
            if (out == null) {
                throw new IOException("Unwritable output");
            }
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (quality >= 0) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
            }
            writer.setOutput(out);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    private static ImageWriter writer(FormatDescriptor format) throws MorphException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(format.id().value());
        while (writers.hasNext()) {
            ImageWriter writer = writers.next();
            if (writer.getClass().getName().startsWith("com.sun.imageio.")) {
                return writer;
            }
            writer.dispose();
        }
        throw new MorphException(ErrorCode.PROVIDER_UNAVAILABLE,
                "O codificador de %s não está disponível.".formatted(format.displayName()));
    }
}
