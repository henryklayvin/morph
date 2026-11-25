package dev.morph.provider.image.raster;

import java.awt.image.BufferedImage;

/** Coarse description of how a picture looks: average premultiplied ARGB over a small grid. */
public final class Fingerprint {

    private static final int GRID = 8;
    /** Mean absolute difference per channel (0-255); generous enough for JPEG quality 1. */
    private static final double TOLERANCE = 24.0;

    private final int columns;
    private final int rows;
    private final double[] values;

    private Fingerprint(int columns, int rows, double[] values) {
        this.columns = columns;
        this.rows = rows;
        this.values = values;
    }

    /** Expects TYPE_INT_RGB or TYPE_INT_ARGB. */
    public static Fingerprint of(BufferedImage image) {
        return of(image, Math.min(GRID, image.getWidth()), Math.min(GRID, image.getHeight()));
    }

    /** Uses the grid of another fingerprint, so that pictures of different sizes can be compared. */
    public static Fingerprint like(Fingerprint other, BufferedImage image) {
        return of(image, Math.min(other.columns, image.getWidth()), Math.min(other.rows, image.getHeight()));
    }

    public boolean resembles(Fingerprint other) {
        if (columns != other.columns || rows != other.rows) {
            return false;
        }
        double sum = 0;
        for (int i = 0; i < values.length; i++) {
            sum += Math.abs(values[i] - other.values[i]);
        }
        return sum / values.length <= TOLERANCE;
    }

    private static Fingerprint of(BufferedImage image, int columns, int rows) {
        int width = image.getWidth();
        int height = image.getHeight();
        boolean alpha = image.getColorModel().hasAlpha();
        int[] row = new int[width];
        double[] sums = new double[columns * rows * 4];
        long[] counts = new long[columns * rows];
        for (int y = 0; y < height; y++) {
            image.getRaster().getDataElements(0, y, width, 1, row);
            int cellRow = (int) ((long) y * rows / height) * columns;
            for (int x = 0; x < width; x++) {
                int cell = cellRow + (int) ((long) x * columns / width);
                int pixel = row[x];
                int a = alpha ? pixel >>> 24 : 255;
                sums[cell * 4] += a;
                sums[cell * 4 + 1] += (pixel >> 16 & 0xFF) * a / 255.0;
                sums[cell * 4 + 2] += (pixel >> 8 & 0xFF) * a / 255.0;
                sums[cell * 4 + 3] += (pixel & 0xFF) * a / 255.0;
                counts[cell]++;
            }
        }
        for (int i = 0; i < sums.length; i++) {
            sums[i] /= counts[i / 4];
        }
        return new Fingerprint(columns, rows, sums);
    }
}
