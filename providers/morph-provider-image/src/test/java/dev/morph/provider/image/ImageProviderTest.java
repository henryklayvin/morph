package dev.morph.provider.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.matrixlab.webp4j.WebPCodec;
import dev.morph.contracts.ConversionOptions;
import dev.morph.contracts.ConversionRequest;
import dev.morph.contracts.ConversionRequest.ConflictPolicy;
import dev.morph.contracts.ConversionRequest.InputFile;
import dev.morph.contracts.ConversionRequest.OutputSpec;
import dev.morph.contracts.ConversionResult;
import dev.morph.contracts.ErrorCode;
import dev.morph.contracts.FormatId;
import dev.morph.contracts.MorphException;
import dev.morph.contracts.ResourceLimits;
import dev.morph.engine.ConversionJob;
import dev.morph.engine.EngineConfig;
import dev.morph.engine.JobListener;
import dev.morph.engine.MorphEngine;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises the provider through the real engine: what is tested is what users get. */
@Timeout(60)
class ImageProviderTest {

    private static final Path FIXTURES = Path.of("..", "..", "test-fixtures");

    @TempDir
    Path temp;

    private MorphEngine engine;

    @BeforeEach
    void start() throws IOException {
        engine = new MorphEngine(new EngineConfig(temp.resolve("work"), 2, Duration.ofSeconds(2),
                ResourceLimits.defaults()), List.of(new ImageProvider()));
    }

    @AfterEach
    void stop() {
        engine.close();
    }

    // ---- the four conversions of the MVP ----

    @Test
    void jpegToPngKeepsEveryPixel() throws Exception {
        Path png = convert(FIXTURES.resolve("quadrants.jpg"), "png", Map.of());
        BufferedImage expected = ImageIO.read(FIXTURES.resolve("quadrants.jpg").toFile());
        assertSamePixels(expected, ImageIO.read(png.toFile()));
    }

    @Test
    void pngToJpegLooksLikeTheSource() throws Exception {
        Path jpeg = convert(FIXTURES.resolve("screenshot.png"), "jpeg", Map.of());
        assertClose(ImageIO.read(FIXTURES.resolve("screenshot.png").toFile()), ImageIO.read(jpeg.toFile()), 3.0);
    }

    @Test
    void pngToWebpLossyAndLossless() throws Exception {
        Path source = FIXTURES.resolve("screenshot.png");
        BufferedImage original = ImageIO.read(source.toFile());
        Path lossy = convert(source, "webp", Map.of("quality", "80"));
        Path lossless = convert(source, "webp", Map.of("lossless", "true"));
        // libwebp itself, an independent reader from the one used by the verification stage.
        assertClose(original, WebPCodec.decodeImage(Files.readAllBytes(lossy)), 3.0);
        assertSamePixels(original, WebPCodec.decodeImage(Files.readAllBytes(lossless)));
    }

    @Test
    void jpegToWebp() throws Exception {
        Path source = FIXTURES.resolve("quadrants.jpg");
        Path webp = convert(source, "webp", Map.of());
        assertClose(ImageIO.read(source.toFile()), WebPCodec.decodeImage(Files.readAllBytes(webp)), 4.0);
    }

    // ---- colour and transparency ----

    @Test
    void transparencyIsFlattenedOverTheChosenBackgroundWithAWarning() throws Exception {
        ConversionResult white = run(FIXTURES.resolve("alpha.png"), "jpeg", Map.of("quality", "100"));
        assertEquals(1, white.warnings().size());
        // Right half is (20,60,200) at alpha 128 over white.
        assertColor(new Color(137, 157, 227), ImageIO.read(white.outputs().getFirst().path().toFile()), 90, 40, 4);

        ConversionResult black = run(FIXTURES.resolve("alpha.png"), "jpeg",
                Map.of("quality", "100", "background", "#000000"));
        assertColor(new Color(10, 30, 100), ImageIO.read(black.outputs().getFirst().path().toFile()), 90, 40, 4);
        assertColor(new Color(230, 120, 20), ImageIO.read(black.outputs().getFirst().path().toFile()), 20, 40, 4);
    }

    @Test
    void anUnusedAlphaChannelIsNotReportedAsTransparency() throws Exception {
        BufferedImage opaque = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        fill(opaque, 0xFF336699);
        assertEquals(List.of(), run(png(opaque), "jpeg", Map.of()).warnings());
    }

    @Test
    void webpKeepsTransparency() throws Exception {
        Path webp = convert(FIXTURES.resolve("alpha.png"), "webp", Map.of("lossless", "true"));
        BufferedImage decoded = WebPCodec.decodeImage(Files.readAllBytes(webp));
        assertEquals(0x80143CC8, decoded.getRGB(90, 40));
        assertEquals(0xFFE67814, decoded.getRGB(20, 40));
    }

    /** Java 2D would turn mid grey (128) into roughly 188 here. */
    @Test
    void greyscaleVariantsKeepTheirBrightness() throws Exception {
        for (byte[] source : List.of(
                greyPng(8, false, 128), greyPng(8, true, 128), greyPng(16, false, 128), greyPng(16, true, 128))) {
            Path file = Files.write(Files.createTempFile(temp, "grey", ".png"), source);
            BufferedImage jpeg = ImageIO.read(convert(file, "jpeg", Map.of("quality", "100")).toFile());
            int level = jpeg.getRaster().getSample(4, 4, 0);
            assertTrue(Math.abs(level - 128) <= 2, "grey 128 became " + level);
        }
    }

    @Test
    void palettePngWithTransparentEntry() throws Exception {
        byte[] r = {0, (byte) 255};
        byte[] g = {0, 0};
        byte[] b = {0, 0};
        byte[] a = {0, (byte) 255};
        IndexColorModel palette = new IndexColorModel(1, 2, r, g, b, a);
        BufferedImage indexed = new BufferedImage(16, 16, BufferedImage.TYPE_BYTE_BINARY, palette);
        for (int y = 0; y < 16; y++) {
            for (int x = 8; x < 16; x++) {
                indexed.getRaster().setSample(x, y, 0, 1);
            }
        }
        BufferedImage jpeg = ImageIO.read(convert(png(indexed), "jpeg", Map.of("quality", "100")).toFile());
        assertColor(Color.WHITE, jpeg, 2, 8, 4);
        assertColor(Color.RED, jpeg, 13, 8, 4);
    }

    // ---- orientation and metadata ----

    @Test
    void exifRotationIsAppliedToThePixels() throws Exception {
        for (String target : List.of("png", "webp")) {
            ConversionResult result = run(FIXTURES.resolve("quadrants-exif-orientation-6.jpg"), target, Map.of());
            assertEquals("160", result.outputs().getFirst().properties().get("width"));
            assertEquals("240", result.outputs().getFirst().properties().get("height"));
            Path file = result.outputs().getFirst().path();
            BufferedImage image = target.equals("png") ? ImageIO.read(file.toFile())
                    : WebPCodec.decodeImage(Files.readAllBytes(file));
            // Stored TL red, TR green, BL blue, BR yellow; rotated 90 degrees clockwise.
            assertColor(new Color(50, 80, 210), image, 40, 60, 12);
            assertColor(new Color(220, 40, 40), image, 120, 60, 12);
            assertColor(new Color(240, 210, 60), image, 40, 180, 12);
            assertColor(new Color(40, 180, 70), image, 120, 180, 12);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"png", "webp"})
    void sourceMetadataNeverReachesTheOutput(String target) throws Exception {
        Path source = FIXTURES.resolve("quadrants-exif-orientation-6.jpg");
        assertTrue(contains(Files.readAllBytes(source), "PRIVATE-GPS-MARKER"), "the fixture must carry metadata");
        byte[] output = Files.readAllBytes(convert(source, target, Map.of()));
        assertFalse(contains(output, "PRIVATE-GPS-MARKER"));
        assertFalse(contains(output, "Exif"));
    }

    // ---- hostile and broken files ----

    @Test
    void aDecompressionBombIsRefusedBeforeAllocating() throws Exception {
        Path bomb = Files.write(temp.resolve("bomb.png"), pngHeader(60_000, 60_000));
        long before = System.nanoTime();
        assertEquals(ErrorCode.RESOURCE_LIMIT_EXCEEDED, failure(bomb, "jpeg").code());
        assertTrue(Duration.ofNanos(System.nanoTime() - before).toSeconds() < 5);
    }

    @Test
    void pixelLimitIsIndependentFromTheMemoryLimit() throws Exception {
        Path source = png(new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB));
        ResourceLimits tight = new ResourceLimits(1 << 20, 1 << 20, 9_999, 1 << 30, 10, Duration.ofSeconds(10));
        MorphException e = assertThrows(MorphException.class,
                () -> engine.convert(request(source, "jpeg", Map.of(), tight)));
        assertEquals(ErrorCode.RESOURCE_LIMIT_EXCEEDED, e.code());
        assertTrue(e.getMessage().contains("100 x 100"));
    }

    @Test
    void memoryLimitIsIndependentFromThePixelLimit() throws Exception {
        Path source = png(new BufferedImage(1000, 1000, BufferedImage.TYPE_INT_RGB));
        ResourceLimits tight = new ResourceLimits(1 << 20, 1 << 20, 10_000_000, 1 << 20, 10, Duration.ofSeconds(10));
        MorphException e = assertThrows(MorphException.class,
                () -> engine.convert(request(source, "jpeg", Map.of(), tight)));
        assertEquals(ErrorCode.RESOURCE_LIMIT_EXCEEDED, e.code());
    }

    @Test
    void truncatedFilesAreReportedAsFailuresAndDeliverNothing() throws Exception {
        for (String name : List.of("screenshot.png", "quadrants.jpg")) {
            byte[] whole = Files.readAllBytes(FIXTURES.resolve(name));
            Path cut = Files.write(temp.resolve("cut-" + name), Arrays.copyOf(whole, 40));
            MorphException e = failure(cut, name.endsWith("png") ? "jpeg" : "png");
            assertTrue(e.code() == ErrorCode.CORRUPT_INPUT || e.code() == ErrorCode.CONVERSION_FAILED, e.code()::name);
        }
        assertFalse(Files.exists(temp.resolve("out")));
    }

    @Test
    void randomlyDamagedFilesNeverCrashTheEngine() throws Exception {
        Random random = new Random(42);
        for (String name : List.of("screenshot.png", "quadrants.jpg", "quadrants-exif-orientation-6.jpg")) {
            byte[] original = Files.readAllBytes(FIXTURES.resolve(name));
            for (int i = 0; i < 60; i++) {
                byte[] damaged = original.clone();
                for (int n = 0; n < 1 + random.nextInt(20); n++) {
                    damaged[8 + random.nextInt(damaged.length - 8)] = (byte) random.nextInt();
                }
                Path file = Files.write(temp.resolve("fuzz-" + i + "-" + name), damaged);
                try {
                    engine.convert(request(file, name.endsWith("png") ? "jpeg" : "png", Map.of(),
                            engine.config().limits()));
                } catch (MorphException e) {
                    assertTrue(e.code() != ErrorCode.INTERNAL_ERROR, () -> "internal error: " + e.getCause());
                }
            }
        }
    }

    @Test
    void webpSideLimitIsExplained() throws Exception {
        Path wide = png(new BufferedImage(16_384, 1, BufferedImage.TYPE_INT_RGB));
        MorphException e = failure(wide, "webp");
        assertEquals(ErrorCode.RESOURCE_LIMIT_EXCEEDED, e.code());
        assertTrue(e.getMessage().contains("16383"));
    }

    @Test
    void onePixelImages() throws Exception {
        BufferedImage dot = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        for (String target : List.of("jpeg", "webp")) {
            assertEquals("1", run(png(dot), target, Map.of()).outputs().getFirst().properties().get("width"));
        }
    }

    @Test
    void lowestQualityStillPassesVerification() throws Exception {
        for (String target : List.of("jpeg", "webp")) {
            run(FIXTURES.resolve("screenshot.png"), target, Map.of("quality", "1"));
        }
    }

    // ---- behaviour ----

    @Test
    void decodingCanBeCancelledHalfWay() throws Exception {
        BufferedImage big = new BufferedImage(6000, 6000, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(1);
        for (int y = 0; y < 6000; y += 2) {
            for (int x = 0; x < 6000; x++) {
                big.setRGB(x, y, random.nextInt());
            }
        }
        Path source = png(big);
        AtomicReference<ConversionJob> handle = new AtomicReference<>();
        ConversionJob job = engine.submit(request(source, "jpeg", Map.of(), engine.config().limits()),
                new JobListener() {
                    @Override
                    public void onProgress(double fraction) {
                        ConversionJob current = handle.get();
                        if (current != null && fraction > 0.02) {
                            current.cancel();
                        }
                    }
                });
        handle.set(job);
        assertEquals(ErrorCode.CANCELLED, assertThrows(MorphException.class, job::await).code());
        assertTrue(job.progress() < 0.6, "stopped during decoding, at " + job.progress());
    }

    @Test
    void conversionsAreDeterministic() throws Exception {
        for (String target : List.of("jpeg", "webp")) {
            byte[] first = Files.readAllBytes(convert(FIXTURES.resolve("screenshot.png"), target, Map.of()));
            byte[] second = Files.readAllBytes(convert(FIXTURES.resolve("screenshot.png"), target, Map.of()));
            assertTrue(Arrays.equals(first, second), target);
        }
    }

    // ---- helpers ----

    private Path convert(Path source, String target, Map<String, String> options) throws Exception {
        return run(source, target, options).outputs().getFirst().path();
    }

    private ConversionResult run(Path source, String target, Map<String, String> options) throws Exception {
        return engine.convert(request(source, target, options, engine.config().limits()));
    }

    private MorphException failure(Path source, String target) {
        return assertThrows(MorphException.class, () -> run(source, target, Map.of()));
    }

    private ConversionRequest request(Path source, String target, Map<String, String> options,
            ResourceLimits limits) {
        return new ConversionRequest(List.of(InputFile.of(source)), FormatId.of(target),
                new ConversionOptions(options),
                new OutputSpec(temp.resolve("out"), "result", null, ConflictPolicy.RENAME), limits);
    }

    private Path png(BufferedImage image) throws IOException {
        Path file = Files.createTempFile(temp, "source", ".png");
        ImageIO.write(image, "png", file.toFile());
        return file;
    }

    private static void fill(BufferedImage image, int argb) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, argb);
            }
        }
    }

    private static void assertSamePixels(BufferedImage expected, BufferedImage actual) {
        assertEquals(expected.getWidth(), actual.getWidth());
        assertEquals(expected.getHeight(), actual.getHeight());
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                assertEquals(expected.getRGB(x, y), actual.getRGB(x, y), "pixel " + x + "," + y);
            }
        }
    }

    private static void assertClose(BufferedImage expected, BufferedImage actual, double tolerance) {
        assertEquals(expected.getWidth(), actual.getWidth());
        assertEquals(expected.getHeight(), actual.getHeight());
        double sum = 0;
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                Color a = new Color(expected.getRGB(x, y));
                Color b = new Color(actual.getRGB(x, y));
                sum += Math.abs(a.getRed() - b.getRed()) + Math.abs(a.getGreen() - b.getGreen())
                        + Math.abs(a.getBlue() - b.getBlue());
            }
        }
        double mean = sum / (3.0 * expected.getWidth() * expected.getHeight());
        assertTrue(mean <= tolerance, "mean difference " + mean);
    }

    private static void assertColor(Color expected, BufferedImage image, int x, int y, int tolerance) {
        Color actual = new Color(image.getRGB(x, y));
        boolean close = Math.abs(expected.getRed() - actual.getRed()) <= tolerance
                && Math.abs(expected.getGreen() - actual.getGreen()) <= tolerance
                && Math.abs(expected.getBlue() - actual.getBlue()) <= tolerance;
        assertTrue(close, "expected " + expected + " but was " + actual);
    }

    private static boolean contains(byte[] data, String text) {
        return new String(data, StandardCharsets.ISO_8859_1).contains(text);
    }

    /** Uniform grey PNG written by hand, so that no Java colour conversion takes part in producing the input. */
    private static byte[] greyPng(int depth, boolean alpha, int level) throws IOException {
        int side = 8;
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (int y = 0; y < side; y++) {
            raw.write(0);
            for (int x = 0; x < side; x++) {
                for (int i = 0; i < depth / 8; i++) {
                    raw.write(level);
                }
                if (alpha) {
                    for (int i = 0; i < depth / 8; i++) {
                        raw.write(255);
                    }
                }
            }
        }
        ByteArrayOutputStream deflated = new ByteArrayOutputStream();
        try (var zip = new java.util.zip.DeflaterOutputStream(deflated)) {
            zip.write(raw.toByteArray());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
        chunk(out, "IHDR", header(side, side, depth, alpha ? 4 : 0));
        chunk(out, "IDAT", deflated.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static byte[] pngHeader(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
        chunk(out, "IHDR", header(width, height, 8, 6));
        chunk(out, "IDAT", new byte[16]);
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static byte[] header(int width, int height, int depth, int colorType) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(bytes);
        data.writeInt(width);
        data.writeInt(height);
        data.write(new byte[] {(byte) depth, (byte) colorType, 0, 0, 0});
        return bytes.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] content) throws IOException {
        DataOutputStream data = new DataOutputStream(out);
        data.writeInt(content.length);
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        data.write(name);
        data.write(content);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(content);
        data.writeInt((int) crc.getValue());
    }
}
