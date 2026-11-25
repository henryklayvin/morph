package dev.morph.provider.pdf;

import dev.morph.contracts.Capability;
import dev.morph.contracts.Cardinality;
import dev.morph.contracts.ConversionContext;
import dev.morph.contracts.ConversionProvider;
import dev.morph.contracts.Loss;
import dev.morph.contracts.OptionSpec;
import dev.morph.contracts.ProducedOutput;
import dev.morph.contracts.ProviderDescriptor;
import dev.morph.contracts.ProviderDescriptor.Dependency;
import dev.morph.contracts.StandardFormats;
import java.util.List;

/** Images to PDF (many to one) and PDF to images (one to many). */
public final class PdfProvider implements ConversionProvider {

    static final OptionSpec.IntegerOption DPI = new OptionSpec.IntegerOption(
            "dpi", "Resolução das imagens geradas", 150, 36, 600);
    static final OptionSpec.IntegerOption QUALITY = new OptionSpec.IntegerOption(
            "quality", "Qualidade da compressão com perdas", 85, 1, 100);
    static final OptionSpec.ChoiceOption PAGE = new OptionSpec.ChoiceOption(
            "page", "Tamanho da página: ajustada à imagem ou A4", "fit", List.of("fit", "a4"));

    private static final Loss METADATA_LOSS = new Loss(Loss.Kind.METADATA,
            "EXIF, localização e demais metadados não são copiados (a rotação é aplicada).");
    private static final Loss TEXT_LOSS = new Loss(Loss.Kind.VECTOR_AND_TEXT,
            "Texto e desenhos viram pixels: não podem mais ser selecionados nem ampliados sem perda.");
    private static final Loss QUALITY_LOSS = new Loss(Loss.Kind.QUALITY, "Compressão com perdas.");
    private static final String RENDER_LIMITS =
            "Imagens JPEG 2000 dentro do PDF não são suportadas; fontes não incorporadas são substituídas.";

    private static final List<Capability> CAPABILITIES = List.of(
            new Capability(StandardFormats.PNG.id(), StandardFormats.PDF.id(), Cardinality.MANY_TO_ONE, 0,
                    List.of(PAGE), List.of(METADATA_LOSS, new Loss(Loss.Kind.BIT_DEPTH,
                            "16 bits por canal são reduzidos a 8.")), List.of()),
            new Capability(StandardFormats.JPEG.id(), StandardFormats.PDF.id(), Cardinality.MANY_TO_ONE, 0,
                    List.of(PAGE), List.of(METADATA_LOSS, new Loss(Loss.Kind.COLOR_PROFILE,
                            "Imagens com perfil de cor, CMYK ou rotação são convertidas para sRGB e recomprimidas em "
                                    + "qualidade 95; as demais são incorporadas sem recompressão.")), List.of()),
            new Capability(StandardFormats.PDF.id(), StandardFormats.PNG.id(), Cardinality.ONE_TO_MANY, 0,
                    List.of(DPI), List.of(TEXT_LOSS), List.of(RENDER_LIMITS)),
            new Capability(StandardFormats.PDF.id(), StandardFormats.JPEG.id(), Cardinality.ONE_TO_MANY, 0,
                    List.of(DPI, QUALITY), List.of(TEXT_LOSS, QUALITY_LOSS), List.of(RENDER_LIMITS)));

    @Override
    public ProviderDescriptor descriptor() {
        return new ProviderDescriptor("pdf", "PDF", List.of(
                new Dependency("Apache PDFBox", Dependency.Kind.JAVA_LIBRARY, "Apache-2.0",
                        "Leitura, renderização e gravação de PDF"),
                new Dependency("Apache PDFBox JBIG2", Dependency.Kind.JAVA_LIBRARY, "Apache-2.0",
                        "Páginas digitalizadas em JBIG2")));
    }

    @Override
    public List<Capability> capabilities() {
        return CAPABILITIES;
    }

    @Override
    public List<ProducedOutput> convert(ConversionContext context) throws Exception {
        return context.target().equals(StandardFormats.PDF)
                ? ImagesToPdf.convert(context) : PdfToImages.convert(context);
    }
}
