package dev.morph.provider.image;

import dev.morph.contracts.Availability;
import dev.morph.contracts.Capability;
import dev.morph.contracts.Cardinality;
import dev.morph.contracts.ConversionContext;
import dev.morph.contracts.ConversionProvider;
import dev.morph.contracts.FormatDescriptor;
import dev.morph.contracts.Loss;
import dev.morph.contracts.OptionSpec;
import dev.morph.contracts.ProducedOutput;
import dev.morph.contracts.ProducedOutput.OutputCheck;
import dev.morph.contracts.ProviderDescriptor;
import dev.morph.contracts.ProviderDescriptor.Dependency;
import dev.morph.contracts.SourceFile;
import dev.morph.contracts.StandardFormats;
import dev.morph.provider.image.raster.Pixels;
import dev.morph.provider.image.raster.RasterChecks;
import dev.morph.provider.image.raster.RasterReader;
import dev.morph.provider.image.raster.RasterWriter;
import dev.morph.provider.image.raster.WebpEncoder;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** PNG, JPEG and WebP. */
public final class ImageProvider implements ConversionProvider {

    static final OptionSpec.IntegerOption QUALITY = new OptionSpec.IntegerOption(
            "quality", "Qualidade da compressão com perdas", 85, 1, 100);
    static final OptionSpec.ColorOption BACKGROUND = new OptionSpec.ColorOption(
            "background", "Cor usada no lugar da transparência", "#ffffff");
    static final OptionSpec.BooleanOption LOSSLESS = new OptionSpec.BooleanOption(
            "lossless", "Compressão sem perdas (ignora a qualidade)", false);

    private static final Loss QUALITY_LOSS = new Loss(Loss.Kind.QUALITY, "Compressão com perdas.");
    private static final Loss TRANSPARENCY_LOSS = new Loss(Loss.Kind.TRANSPARENCY,
            "JPEG não guarda transparência; ela é substituída pela cor de fundo.");
    private static final Loss METADATA_LOSS = new Loss(Loss.Kind.METADATA,
            "EXIF, localização e demais metadados não são copiados (a rotação é aplicada aos pixels).");
    private static final Loss PROFILE_LOSS = new Loss(Loss.Kind.COLOR_PROFILE, "As cores são convertidas para sRGB.");
    private static final Loss PNG_PROFILE_LOSS = new Loss(Loss.Kind.COLOR_PROFILE,
            "Perfis de cor embutidos em PNG são ignorados.");
    private static final Loss DEPTH_LOSS = new Loss(Loss.Kind.BIT_DEPTH, "16 bits por canal são reduzidos a 8.");
    private static final String FIRST_FRAME = "PNG animado: somente o primeiro quadro é convertido.";
    private static final String WEBP_SIDE = "No máximo 16383 pixels por lado.";

    private static final List<Capability> CAPABILITIES = List.of(
            new Capability(StandardFormats.PNG.id(), StandardFormats.JPEG.id(), Cardinality.ONE_TO_ONE, 0,
                    List.of(QUALITY, BACKGROUND),
                    List.of(QUALITY_LOSS, TRANSPARENCY_LOSS, METADATA_LOSS, PNG_PROFILE_LOSS, DEPTH_LOSS),
                    List.of(FIRST_FRAME)),
            new Capability(StandardFormats.JPEG.id(), StandardFormats.PNG.id(), Cardinality.ONE_TO_ONE, 0,
                    List.of(), List.of(METADATA_LOSS, PROFILE_LOSS), List.of()),
            new Capability(StandardFormats.PNG.id(), StandardFormats.WEBP.id(), Cardinality.ONE_TO_ONE, 0,
                    List.of(QUALITY, LOSSLESS),
                    List.of(QUALITY_LOSS, METADATA_LOSS, PNG_PROFILE_LOSS, DEPTH_LOSS),
                    List.of(FIRST_FRAME, WEBP_SIDE)),
            new Capability(StandardFormats.JPEG.id(), StandardFormats.WEBP.id(), Cardinality.ONE_TO_ONE, 0,
                    List.of(QUALITY, LOSSLESS), List.of(QUALITY_LOSS, METADATA_LOSS, PROFILE_LOSS),
                    List.of(WEBP_SIDE)));

    @Override
    public ProviderDescriptor descriptor() {
        return new ProviderDescriptor("image", "Imagens", List.of(
                new Dependency("JDK ImageIO", Dependency.Kind.JAVA_LIBRARY, "GPL-2.0 com Classpath Exception",
                        "Leitura de PNG; gravação de PNG e JPEG"),
                new Dependency("TwelveMonkeys ImageIO", Dependency.Kind.JAVA_LIBRARY, "BSD-3-Clause",
                        "Leitura de JPEG; releitura de WebP na verificação"),
                new Dependency("webp4j (libwebp 1.6.0)", Dependency.Kind.NATIVE_LIBRARY, "MIT / BSD-3-Clause",
                        "Gravação de WebP em Windows, Linux (glibc) e macOS, x64 e ARM64")));
    }

    @Override
    public List<Capability> capabilities() {
        return CAPABILITIES;
    }

    @Override
    public Availability availability(Capability capability) {
        return capability.target().equals(StandardFormats.WEBP.id())
                ? WebpEncoder.availability() : Availability.ready();
    }

    @Override
    public List<ProducedOutput> convert(ConversionContext context) throws Exception {
        SourceFile input = context.singleInput();
        BufferedImage image = RasterReader.read(input.path(), input.format(), context.limits(),
                context.cancellation(), p -> context.progress(p * 0.6));
        Path file = context.newOutputFile();
        FormatDescriptor target = context.target();
        OutputCheck check;

        if (target.equals(StandardFormats.PNG)) {
            RasterWriter.writePng(image, file);
            check = exact(image, context);
        } else if (target.equals(StandardFormats.JPEG)) {
            if (Pixels.hasTransparency(image)) {
                context.warn("A transparência foi substituída pela cor de fundo.");
            }
            image = Pixels.flatten(image, Integer.parseInt(context.options().get(BACKGROUND).substring(1), 16));
            RasterWriter.writeJpeg(image, file, context.options().get(QUALITY));
            check = similar(image, context);
        } else if (target.equals(StandardFormats.WEBP)) {
            boolean lossless = context.options().get(LOSSLESS);
            RasterWriter.writeWebp(image, file, context.options().get(QUALITY), lossless);
            check = lossless ? exact(image, context) : similar(image, context);
        } else {
            throw new IllegalStateException("Undeclared target: " + target.id());
        }
        return List.of(new ProducedOutput(file, Map.of(
                "width", Integer.toString(image.getWidth()), "height", Integer.toString(image.getHeight())), check));
    }

    private static OutputCheck exact(BufferedImage image, ConversionContext context) {
        return RasterChecks.exact(image, context.target(), context.limits(), context.cancellation());
    }

    private static OutputCheck similar(BufferedImage image, ConversionContext context) {
        return RasterChecks.similar(image, context.target(), context.limits(), context.cancellation());
    }
}
