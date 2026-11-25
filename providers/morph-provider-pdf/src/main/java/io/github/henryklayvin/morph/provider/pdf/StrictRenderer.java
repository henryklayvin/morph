package io.github.henryklayvin.morph.provider.pdf;

import io.github.henryklayvin.morph.contracts.ErrorCode;
import io.github.henryklayvin.morph.contracts.MorphException;
import io.github.henryklayvin.morph.contracts.ResourceLimits;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.filter.MissingImageReaderException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;

/**
 * PDFBox logs an error and leaves a hole in the page when it cannot decode a picture. A blank page is not a
 * successful conversion, so here the same situation stops the conversion. Embedded pictures also obey the limits.
 */
final class StrictRenderer {

    private final PDDocument document;
    private final ResourceLimits limits;
    private final Renderer renderer;
    private MorphException problem;

    StrictRenderer(PDDocument document, ResourceLimits limits) {
        this.document = document;
        this.limits = limits;
        this.renderer = new Renderer(document);
    }

    BufferedImage render(int pageIndex, float scale) throws IOException, MorphException {
        checkPageSize(pageIndex, scale);
        problem = null;
        BufferedImage image;
        try {
            image = renderer.renderImage(pageIndex, scale, ImageType.RGB);
        } catch (IOException | RuntimeException e) {
            if (problem != null) {
                throw problem;
            }
            throw new MorphException(ErrorCode.CORRUPT_INPUT,
                    "A página %d do PDF não pôde ser desenhada.".formatted(pageIndex + 1), e);
        }
        if (problem != null) {
            throw problem;
        }
        return image;
    }

    private void checkPageSize(int pageIndex, float scale) throws MorphException {
        PDRectangle box = document.getPage(pageIndex).getCropBox();
        double pixels = Math.ceil(box.getWidth() * (double) scale) * Math.ceil(box.getHeight() * (double) scale);
        if (pixels > limits.maxPixels() || pixels * 4 > limits.maxWorkingMemoryBytes()) {
            throw new MorphException(ErrorCode.RESOURCE_LIMIT_EXCEEDED,
                    "A página %d é grande demais nesta resolução. Use um DPI menor.".formatted(pageIndex + 1));
        }
    }

    private final class Renderer extends PDFRenderer {
        Renderer(PDDocument document) {
            super(document);
            setSubsamplingAllowed(true);
        }

        @Override
        protected PageDrawer createPageDrawer(PageDrawerParameters parameters) throws IOException {
            return new Drawer(parameters);
        }
    }

    private final class Drawer extends PageDrawer {
        Drawer(PageDrawerParameters parameters) throws IOException {
            super(parameters);
        }

        @Override
        public void drawImage(PDImage image) throws IOException {
            if ((long) image.getWidth() * image.getHeight() > limits.maxPixels()) {
                problem = new MorphException(ErrorCode.RESOURCE_LIMIT_EXCEEDED,
                        "O PDF contém uma imagem de %d x %d pixels, acima do limite."
                                .formatted(image.getWidth(), image.getHeight()));
                throw new IOException(problem);
            }
            super.drawImage(image);
        }

        @Override
        protected void operatorException(Operator operator, List<COSBase> operands, IOException exception)
                throws IOException {
            if (exception instanceof MissingImageReaderException) {
                problem = new MorphException(ErrorCode.CONVERSION_FAILED,
                        "O PDF contém uma imagem em um formato ainda não suportado (provavelmente JPEG 2000).",
                        exception);
                throw exception;
            }
            super.operatorException(operator, operands, exception);
        }
    }
}
