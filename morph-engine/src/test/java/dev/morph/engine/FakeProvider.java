package dev.morph.engine;

import dev.morph.contracts.Availability;
import dev.morph.contracts.Capability;
import dev.morph.contracts.Cardinality;
import dev.morph.contracts.ConversionContext;
import dev.morph.contracts.ConversionProvider;
import dev.morph.contracts.FormatDescriptor;
import dev.morph.contracts.OptionSpec;
import dev.morph.contracts.ProducedOutput;
import dev.morph.contracts.ProviderDescriptor;
import dev.morph.contracts.StandardFormats;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Scriptable provider: no real codec is involved in the engine's own tests. */
final class FakeProvider implements ConversionProvider {

    static final OptionSpec.IntegerOption QUALITY = new OptionSpec.IntegerOption("quality", "q", 85, 1, 100);
    static final byte[] PNG_BYTES = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3};
    static final byte[] JPEG_BYTES = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 4, 5, 6};

    @FunctionalInterface
    interface Behaviour {
        List<ProducedOutput> convert(ConversionContext context) throws Exception;
    }

    private final String id;
    private final int priority;
    private final Cardinality cardinality;
    private final Availability availability;
    private final Behaviour behaviour;

    FakeProvider(String id, int priority, Cardinality cardinality, Availability availability, Behaviour behaviour) {
        this.id = id;
        this.priority = priority;
        this.cardinality = cardinality;
        this.availability = availability;
        this.behaviour = behaviour;
    }

    static FakeProvider pngToJpeg(Behaviour behaviour) {
        return new FakeProvider("fake", 0, Cardinality.ONE_TO_ONE, Availability.ready(), behaviour);
    }

    static List<ProducedOutput> writeJpeg(ConversionContext context) throws Exception {
        return writeJpegs(context, 1);
    }

    static List<ProducedOutput> writeJpegs(ConversionContext context, int count) throws Exception {
        List<ProducedOutput> outputs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Path out = context.newOutputFile();
            Files.write(out, JPEG_BYTES);
            outputs.add(new ProducedOutput(out, Map.of("n", "" + i), file -> { }));
        }
        return outputs;
    }

    @Override
    public ProviderDescriptor descriptor() {
        return new ProviderDescriptor(id, "Fake", List.of());
    }

    @Override
    public List<Capability> capabilities() {
        FormatDescriptor source = StandardFormats.PNG;
        return List.of(new Capability(source.id(), StandardFormats.JPEG.id(), cardinality, priority,
                List.of(QUALITY), List.of(), List.of()));
    }

    @Override
    public Availability availability(Capability capability) {
        return availability;
    }

    @Override
    public List<ProducedOutput> convert(ConversionContext context) throws Exception {
        return behaviour.convert(context);
    }
}
