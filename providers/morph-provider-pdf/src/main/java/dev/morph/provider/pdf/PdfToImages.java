package dev.morph.provider.pdf;

import dev.morph.contracts.ConversionContext;
import dev.morph.contracts.ProducedOutput;
import dev.morph.contracts.ProducedOutput.OutputCheck;
import dev.morph.contracts.StandardFormats;
import dev.morph.provider.image.raster.RasterChecks;
import dev.morph.provider.image.raster.RasterWriter;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;

/** One image per page. */
final class PdfToImages {

    private static final float POINTS_PER_INCH = 72f;

    private PdfToImages() {
    }

    static List<ProducedOutput> convert(ConversionContext context) throws Exception {
        boolean png = context.target().equals(StandardFormats.PNG);
        float scale = context.options().get(PdfProvider.DPI) / POINTS_PER_INCH;
        List<ProducedOutput> outputs = new ArrayList<>();
        try (PDDocument document = PdfDocuments.open(context.singleInput().path(), context.limits(),
                context.workDirectory())) {
            StrictRenderer renderer = new StrictRenderer(document, context.limits());
            int pages = document.getNumberOfPages();
            for (int i = 0; i < pages; i++) {
                context.cancellation().throwIfCancelled();
                BufferedImage image = renderer.render(i, scale);
                Path file = context.newOutputFile();
                OutputCheck check;
                if (png) {
                    RasterWriter.writePng(image, file);
                    check = RasterChecks.exact(image, context.target(), context.limits(), context.cancellation());
                } else {
                    RasterWriter.writeJpeg(image, file, context.options().get(PdfProvider.QUALITY));
                    check = RasterChecks.similar(image, context.target(), context.limits(), context.cancellation());
                }
                outputs.add(new ProducedOutput(file, Map.of(
                        "page", Integer.toString(i + 1),
                        "width", Integer.toString(image.getWidth()),
                        "height", Integer.toString(image.getHeight())), check));
                context.progress((i + 1.0) / pages);
            }
        }
        return outputs;
    }
}
