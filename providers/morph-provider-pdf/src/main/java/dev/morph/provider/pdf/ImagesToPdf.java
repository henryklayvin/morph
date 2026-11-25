package dev.morph.provider.pdf;

import dev.morph.contracts.ConversionContext;
import dev.morph.contracts.ErrorCode;
import dev.morph.contracts.MorphException;
import dev.morph.contracts.ProducedOutput;
import dev.morph.contracts.ProducedOutput.OutputCheck;
import dev.morph.contracts.SourceFile;
import dev.morph.contracts.StandardFormats;
import dev.morph.provider.image.raster.ExifOrientation;
import dev.morph.provider.image.raster.Fingerprint;
import dev.morph.provider.image.raster.JpegCleaner;
import dev.morph.provider.image.raster.Pixels;
import dev.morph.provider.image.raster.RasterChecks;
import dev.morph.provider.image.raster.RasterReader;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/** One page per image, in the order given by the user. */
final class ImagesToPdf {

    private static final float POINTS_PER_PIXEL = 72f / 96f;
    /** PDF's own ceiling for a page side. */
    private static final float MAX_PAGE_SIDE = 14_400f;
    private static final float REENCODE_QUALITY = 0.95f;
    private static final int PREVIEW_SIDE = 160;
    /** A fixed seed makes the file identifier, and so the whole file, reproducible and free of timestamps. */
    private static final long DOCUMENT_ID_SEED = 0x4D4F525048L;

    private ImagesToPdf() {
    }

    /** What a page must show: where the picture is and what it looks like. */
    private record ExpectedPage(float x, float y, float width, float height, Fingerprint fingerprint) {
    }

    static List<ProducedOutput> convert(ConversionContext context) throws Exception {
        List<SourceFile> inputs = context.inputs();
        if (inputs.size() > context.limits().maxPages()) {
            throw new MorphException(ErrorCode.RESOURCE_LIMIT_EXCEEDED,
                    "São aceitas no máximo %d imagens por PDF.".formatted(context.limits().maxPages()));
        }
        boolean a4 = context.options().get(PdfProvider.PAGE).equals("a4");
        Path file = context.newOutputFile();
        List<ExpectedPage> expected = new ArrayList<>();
        try (PDDocument document = new PDDocument()) {
            document.setDocumentId(DOCUMENT_ID_SEED);
            for (int i = 0; i < inputs.size(); i++) {
                context.cancellation().throwIfCancelled();
                expected.add(addPage(document, inputs.get(i), a4, context));
                context.progress((i + 1.0) / inputs.size());
            }
            document.save(file.toFile());
        }
        return List.of(new ProducedOutput(file, Map.of("pages", Integer.toString(inputs.size())),
                check(expected, context)));
    }

    private static ExpectedPage addPage(PDDocument document, SourceFile source, boolean a4,
            ConversionContext context) throws IOException, MorphException {
        BufferedImage image = RasterReader.read(source.path(), source.format(), context.limits(),
                context.cancellation(), p -> { });
        PDImageXObject embedded = embed(document, source, image);

        float width = image.getWidth() * POINTS_PER_PIXEL;
        float height = image.getHeight() * POINTS_PER_PIXEL;
        PDRectangle box;
        float scale;
        if (a4) {
            PDRectangle portrait = PDRectangle.A4;
            box = width > height ? new PDRectangle(portrait.getHeight(), portrait.getWidth())
                    : new PDRectangle(portrait.getWidth(), portrait.getHeight());
            scale = Math.min(box.getWidth() / width, box.getHeight() / height);
        } else {
            scale = Math.min(1f, MAX_PAGE_SIDE / Math.max(width, height));
            box = new PDRectangle(width * scale, height * scale);
        }
        width *= scale;
        height *= scale;
        float x = (box.getWidth() - width) / 2;
        float y = (box.getHeight() - height) / 2;

        PDPage page = new PDPage(box);
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.drawImage(embedded, x, y, width, height);
        }
        return new ExpectedPage(x, y, width, height, Fingerprint.of(Pixels.flatten(image, 0xFFFFFF)));
    }

    private static PDImageXObject embed(PDDocument document, SourceFile source, BufferedImage image)
            throws IOException {
        if (!source.format().equals(StandardFormats.JPEG)) {
            return LosslessFactory.createFromImage(document, image);
        }
        if (ExifOrientation.ofJpeg(source.path()) == ExifOrientation.NORMAL) {
            Optional<byte[]> cleaned = JpegCleaner.clean(source.path());
            if (cleaned.isPresent()) {
                return JPEGFactory.createFromByteArray(document, cleaned.get());
            }
        }
        return JPEGFactory.createFromImage(document, image, REENCODE_QUALITY);
    }

    private static OutputCheck check(List<ExpectedPage> expected, ConversionContext context) {
        return file -> {
            try (PDDocument document = PdfDocuments.open(file, context.limits(), context.workDirectory())) {
                if (document.getNumberOfPages() != expected.size()) {
                    throw RasterChecks.mismatch("quantidade de páginas diferente da esperada");
                }
                StrictRenderer renderer = new StrictRenderer(document, context.limits());
                for (int i = 0; i < expected.size(); i++) {
                    context.cancellation().throwIfCancelled();
                    verifyPage(document, renderer, i, expected.get(i));
                }
            }
        };
    }

    /** Renders a small preview of the page and compares the region of the picture with the source. */
    private static void verifyPage(PDDocument document, StrictRenderer renderer, int index, ExpectedPage page)
            throws IOException, MorphException {
        float longest = Math.max(page.width(), page.height());
        float scale = Math.min(4f, PREVIEW_SIDE / longest);
        BufferedImage preview = renderer.render(index, scale);
        float pageHeight = document.getPage(index).getCropBox().getHeight();
        int x = Math.clamp(Math.round(page.x() * scale), 0, preview.getWidth() - 1);
        int y = Math.clamp(Math.round((pageHeight - page.y() - page.height()) * scale), 0, preview.getHeight() - 1);
        int width = Math.clamp(Math.round(page.width() * scale), 1, preview.getWidth() - x);
        int height = Math.clamp(Math.round(page.height() * scale), 1, preview.getHeight() - y);
        BufferedImage region = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = region.createGraphics();
        g.drawImage(preview, -x, -y, null);
        g.dispose();
        Fingerprint actual = Fingerprint.like(page.fingerprint(), region);
        // Extremely thin pictures have fewer preview pixels than grid cells; only the page itself is checked then.
        if (width >= 8 && height >= 8 && !page.fingerprint().resembles(actual)) {
            throw RasterChecks.mismatch("a página %d não se parece com a imagem".formatted(index + 1));
        }
    }
}
