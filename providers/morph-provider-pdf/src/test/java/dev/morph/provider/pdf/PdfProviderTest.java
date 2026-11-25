package dev.morph.provider.pdf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import dev.morph.engine.EngineConfig;
import dev.morph.engine.MorphEngine;
import dev.morph.provider.image.raster.JpegCleaner;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(120)
class PdfProviderTest {

    private static final Path FIXTURES = Path.of("..", "..", "test-fixtures");

    @TempDir
    Path temp;

    private MorphEngine engine;

    @BeforeEach
    void start() throws IOException {
        engine = new MorphEngine(new EngineConfig(temp.resolve("work"), 1, Duration.ofSeconds(2),
                ResourceLimits.defaults()), List.of(new PdfProvider()));
    }

    @AfterEach
    void stop() {
        engine.close();
    }

    @Test
    void aPdfBecomesOneImagePerPage() throws Exception {
        for (String target : List.of("png", "jpeg")) {
            ConversionResult result = run(List.of(FIXTURES.resolve("document-3-pages.pdf")), target,
                    Map.of("dpi", "72"));
            assertEquals(3, result.outputs().size());
            for (int page = 1; page <= 3; page++) {
                var output = result.outputs().get(page - 1);
                assertEquals(Integer.toString(page), output.properties().get("page"));
                BufferedImage image = ImageIO.read(output.path().toFile());
                assertEquals(420, image.getWidth(), 1); // A5 at 72 dpi
                assertEquals(595, image.getHeight(), 1);
                assertTrue(distinctColors(image) > 10, "page " + page + " must not be blank");
            }
        }
        assertEquals(List.of("result-1.jpg", "result-1.png", "result-2.jpg", "result-2.png", "result-3.jpg",
                "result-3.png"), filesUnder(temp.resolve("out")));
    }

    @Test
    void theDpiOptionChangesTheResolution() throws Exception {
        var output = run(List.of(FIXTURES.resolve("document-3-pages.pdf")), "png", Map.of("dpi", "144"))
                .outputs().getFirst();
        assertEquals(840, Integer.parseInt(output.properties().get("width")), 2);
    }

    @Test
    void mixedImagesBecomeOnePdfInTheGivenOrder() throws Exception {
        ConversionResult result = run(List.of(FIXTURES.resolve("quadrants.jpg"), FIXTURES.resolve("alpha.png"),
                FIXTURES.resolve("screenshot.png")), "pdf", Map.of());
        assertEquals(1, result.outputs().size());
        try (PDDocument document = Loader.loadPDF(result.outputs().getFirst().path().toFile())) {
            assertEquals(3, document.getNumberOfPages());
            PDRectangle first = document.getPage(0).getCropBox();
            assertEquals(180, first.getWidth(), 0.1); // 240 px at 96 dpi
            assertEquals(120, first.getHeight(), 0.1);
            BufferedImage page = new PDFRenderer(document).renderImage(0);
            assertColor(new Color(220, 40, 40), page, 40, 30, 14);
            assertColor(new Color(240, 210, 60), page, 140, 90, 14);
            // Transparency survives as a soft mask over the white page.
            assertColor(new Color(137, 157, 227), new PDFRenderer(document).renderImage(1), 70, 30, 6);
        }
    }

    @Test
    void a4PagesFollowTheOrientationOfTheImage() throws Exception {
        Path file = run(List.of(FIXTURES.resolve("quadrants.jpg"),
                FIXTURES.resolve("quadrants-exif-orientation-6.jpg")), "pdf", Map.of("page", "a4"))
                .outputs().getFirst().path();
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            assertTrue(document.getPage(0).getCropBox().getWidth() > document.getPage(0).getCropBox().getHeight());
            assertTrue(document.getPage(1).getCropBox().getWidth() < document.getPage(1).getCropBox().getHeight());
            BufferedImage rotated = new PDFRenderer(document).renderImage(1);
            assertColor(new Color(50, 80, 210), rotated, 150, 200, 14);
            assertColor(new Color(220, 40, 40), rotated, 450, 200, 14);
        }
    }

    @Test
    void jpegIsEmbeddedWithoutRecompressionAndWithoutMetadata() throws Exception {
        // Appends what phones append: metadata before the image and extra data after it.
        byte[] plain = Files.readAllBytes(FIXTURES.resolve("quadrants.jpg"));
        ByteArrayOutputStream dressed = new ByteArrayOutputStream();
        dressed.write(plain, 0, 2);
        byte[] exif = "Exif\0\0PRIVATE-LOCATION".getBytes(StandardCharsets.ISO_8859_1);
        dressed.write(new byte[] {(byte) 0xFF, (byte) 0xE1, 0, (byte) (exif.length + 2)});
        dressed.write(exif);
        byte[] comment = "PRIVATE-COMMENT".getBytes(StandardCharsets.ISO_8859_1);
        dressed.write(new byte[] {(byte) 0xFF, (byte) 0xFE, 0, (byte) (comment.length + 2)});
        dressed.write(comment);
        dressed.write(plain, 2, plain.length - 2);
        dressed.write("PRIVATE-TRAILER".getBytes(StandardCharsets.ISO_8859_1));
        Path source = Files.write(temp.resolve("phone.jpg"), dressed.toByteArray());

        Path pdf = run(List.of(source), "pdf", Map.of()).outputs().getFirst().path();
        assertFalse(new String(Files.readAllBytes(pdf), StandardCharsets.ISO_8859_1).contains("PRIVATE"));
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            PDImageXObject image = (PDImageXObject) document.getPage(0).getResources()
                    .getXObject(document.getPage(0).getResources().getXObjectNames().iterator().next());
            assertEquals("jpg", image.getSuffix(), "must stay a JPEG stream");
            byte[] embedded = image.getCOSObject().createRawInputStream().readAllBytes();
            assertArrayEquals(JpegCleaner.clean(source).orElseThrow(), embedded);
            BufferedImage expected = ImageIO.read(FIXTURES.resolve("quadrants.jpg").toFile());
            BufferedImage actual = ImageIO.read(new java.io.ByteArrayInputStream(embedded));
            for (int y = 0; y < expected.getHeight(); y += 7) {
                for (int x = 0; x < expected.getWidth(); x += 7) {
                    assertEquals(expected.getRGB(x, y), actual.getRGB(x, y));
                }
            }
        }
    }

    @Test
    void generatedPdfsAreReproducibleAndCarryNoIdentity() throws Exception {
        byte[] first = Files.readAllBytes(run(List.of(FIXTURES.resolve("alpha.png")), "pdf", Map.of())
                .outputs().getFirst().path());
        byte[] second = Files.readAllBytes(run(List.of(FIXTURES.resolve("alpha.png")), "pdf", Map.of())
                .outputs().getFirst().path());
        assertArrayEquals(first, second);
        String text = new String(first, StandardCharsets.ISO_8859_1);
        for (String word : List.of("/Author", "/Creator", "/Producer", "/CreationDate",
                System.getProperty("user.name"))) {
            assertFalse(text.contains(word), word);
        }
    }

    @Test
    void passwordProtectedPdfsAreReported() throws Exception {
        Path file = temp.resolve("locked.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.protect(new StandardProtectionPolicy("owner", "secret", new AccessPermission()));
            document.save(file.toFile());
        }
        assertEquals(ErrorCode.PROTECTED_INPUT, failure(file, "png", Map.of()).code());
    }

    @Test
    void hugePagesAreRefusedBeforeRendering() throws Exception {
        Path file = temp.resolve("huge.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(new PDRectangle(14_400, 14_400)));
            document.save(file.toFile());
        }
        assertEquals(ErrorCode.RESOURCE_LIMIT_EXCEEDED, failure(file, "png", Map.of("dpi", "600")).code());
    }

    @Test
    void hugeEmbeddedImagesAreRefusedBeforeDecoding() throws Exception {
        Path file = temp.resolve("bomb.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A6);
            document.addPage(page);
            PDImageXObject image = new PDImageXObject(document,
                    new java.io.ByteArrayInputStream(new byte[] {0x78, (byte) 0x9c, 3, 0, 0, 0, 0, 1}),
                    COSName.FLATE_DECODE, 60_000, 60_000, 8, PDDeviceRGB.INSTANCE);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(image, 0, 0, 100, 100);
            }
            document.save(file.toFile());
        }
        assertEquals(ErrorCode.RESOURCE_LIMIT_EXCEEDED, failure(file, "png", Map.of()).code());
    }

    @Test
    void undecodablePicturesFailInsteadOfLeavingABlankArea() throws Exception {
        Path file = temp.resolve("jpx.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A6);
            document.addPage(page);
            PDImageXObject image = new PDImageXObject(document,
                    new java.io.ByteArrayInputStream(new byte[] {0, 0, 0, 12, 'j', 'P', ' ', ' '}),
                    COSName.JPX_DECODE, 10, 10, 8, PDDeviceRGB.INSTANCE);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(image, 0, 0, 100, 100);
            }
            document.save(file.toFile());
        }
        MorphException e = failure(file, "png", Map.of());
        assertEquals(ErrorCode.CONVERSION_FAILED, e.code());
        assertTrue(e.getMessage().contains("JPEG 2000"));
        assertFalse(Files.exists(temp.resolve("out")));
    }

    @Test
    void pageLimit() throws Exception {
        ResourceLimits limits = new ResourceLimits(1 << 20, 1 << 24, 10_000_000, 1 << 28, 2, Duration.ofSeconds(30));
        ConversionRequest request = request(List.of(FIXTURES.resolve("document-3-pages.pdf")), "png", Map.of(),
                limits);
        assertEquals(ErrorCode.RESOURCE_LIMIT_EXCEEDED,
                assertThrows(MorphException.class, () -> engine.convert(request)).code());
    }

    @Test
    void damagedPdfsNeverCrashTheEngine() throws Exception {
        byte[] original = Files.readAllBytes(FIXTURES.resolve("document-3-pages.pdf"));
        Random random = new Random(7);
        for (int i = 0; i < 25; i++) {
            byte[] damaged = i % 2 == 0 ? original.clone()
                    : Arrays.copyOf(original, 100 + random.nextInt(original.length - 100));
            for (int n = 0; n < 30; n++) {
                damaged[8 + random.nextInt(damaged.length - 8)] = (byte) random.nextInt();
            }
            Path file = Files.write(temp.resolve("fuzz-" + i + ".pdf"), damaged);
            try {
                run(List.of(file), "png", Map.of("dpi", "36"));
            } catch (MorphException e) {
                assertTrue(e.code() != ErrorCode.INTERNAL_ERROR, () -> "internal error: " + e.getCause());
            }
        }
    }

    @Test
    void nothingIsWrittenOutsideTheWorkAndOutputDirectories() throws Exception {
        Path home = Path.of(System.getProperty("user.home"), ".pdfbox.cache");
        boolean existedBefore = Files.exists(home);
        run(List.of(FIXTURES.resolve("document-3-pages.pdf")), "png", Map.of("dpi", "36"));
        assertEquals(existedBefore, Files.exists(home), "the font index must not land in the user's home");
        assertEquals(List.of("owner.lock"), filesUnder(temp.resolve("work")));
    }

    private ConversionResult run(List<Path> sources, String target, Map<String, String> options) throws Exception {
        return engine.convert(request(sources, target, options, engine.config().limits()));
    }

    private MorphException failure(Path source, String target, Map<String, String> options) {
        return assertThrows(MorphException.class, () -> run(List.of(source), target, options));
    }

    private ConversionRequest request(List<Path> sources, String target, Map<String, String> options,
            ResourceLimits limits) {
        return new ConversionRequest(sources.stream().map(InputFile::of).toList(), FormatId.of(target),
                new ConversionOptions(options),
                new OutputSpec(temp.resolve("out"), "result", null, ConflictPolicy.RENAME), limits);
    }

    private static long distinctColors(BufferedImage image) {
        return Arrays.stream(image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()))
                .distinct().count();
    }

    private static void assertColor(Color expected, BufferedImage image, int x, int y, int tolerance) {
        Color actual = new Color(image.getRGB(x, y));
        assertTrue(Math.abs(expected.getRed() - actual.getRed()) <= tolerance
                && Math.abs(expected.getGreen() - actual.getGreen()) <= tolerance
                && Math.abs(expected.getBlue() - actual.getBlue()) <= tolerance,
                "expected " + expected + " but was " + actual);
    }

    private static List<String> filesUnder(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).sorted().toList();
        }
    }
}
