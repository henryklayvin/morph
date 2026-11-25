package io.github.henryklayvin.morph.provider.image.raster;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBufferInt;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;

/** Pixel layout helpers. Every image inside Morph is 8-bit sRGB, either opaque (INT_RGB) or not (INT_ARGB). */
public final class Pixels {

    private Pixels() {
    }

    static int[] data(BufferedImage image) {
        return ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
    }

    /** Converts whatever a decoder produced to the canonical layout. */
    static BufferedImage normalize(BufferedImage source) {
        int type = source.getType();
        if (type == BufferedImage.TYPE_INT_RGB || type == BufferedImage.TYPE_INT_ARGB) {
            return source;
        }
        ColorModel model = source.getColorModel();
        BufferedImage out = new BufferedImage(source.getWidth(), source.getHeight(),
                model.hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        if (model.getColorSpace().getType() == ColorSpace.TYPE_GRAY && !(model instanceof IndexColorModel)) {
            // Java 2D treats CS_GRAY as linear light, while PNG and JPEG grey samples are gamma encoded like sRGB.
            // Going through Graphics2D would visibly brighten or darken the picture.
            copyGray(source.getRaster(), model, out);
            return out;
        }
        Graphics2D g = out.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** Whether any pixel is not fully opaque; an alpha channel alone does not mean transparency. */
    public static boolean hasTransparency(BufferedImage image) {
        if (!image.getColorModel().hasAlpha()) {
            return false;
        }
        for (int pixel : data(image)) {
            if (pixel >>> 24 != 0xFF) {
                return true;
            }
        }
        return false;
    }

    /** Composites over an opaque background. */
    public static BufferedImage flatten(BufferedImage image, int backgroundRgb) {
        if (!image.getColorModel().hasAlpha()) {
            return image;
        }
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        int[] from = data(image);
        int[] to = data(out);
        for (int i = 0; i < from.length; i++) {
            int pixel = from[i];
            int alpha = pixel >>> 24;
            to[i] = blend(pixel >> 16, backgroundRgb >> 16, alpha) << 16
                    | blend(pixel >> 8, backgroundRgb >> 8, alpha) << 8
                    | blend(pixel, backgroundRgb, alpha);
        }
        return out;
    }

    private static int blend(int foreground, int background, int alpha) {
        return ((foreground & 0xFF) * alpha + (background & 0xFF) * (255 - alpha) + 127) / 255;
    }

    private static void copyGray(Raster raster, ColorModel model, BufferedImage out) {
        int width = raster.getWidth();
        boolean alpha = model.hasAlpha();
        int max = (1 << model.getComponentSize(0)) - 1;
        int[] gray = new int[width];
        int[] opacity = new int[width];
        int[] to = data(out);
        for (int y = 0; y < raster.getHeight(); y++) {
            raster.getSamples(0, y, width, 1, 0, gray);
            if (alpha) {
                raster.getSamples(0, y, width, 1, 1, opacity);
            }
            for (int x = 0; x < width; x++) {
                int level = (int) ((gray[x] * 255L + max / 2) / max);
                int a = alpha ? (int) ((opacity[x] * 255L + max / 2) / max) : 255;
                to[y * width + x] = a << 24 | level << 16 | level << 8 | level;
            }
        }
    }
}
