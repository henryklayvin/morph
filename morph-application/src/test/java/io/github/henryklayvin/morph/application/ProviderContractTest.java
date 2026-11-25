package io.github.henryklayvin.morph.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import io.github.henryklayvin.morph.contracts.Capability;
import io.github.henryklayvin.morph.contracts.ConversionOptions;
import io.github.henryklayvin.morph.contracts.ConversionRequest;
import io.github.henryklayvin.morph.contracts.ConversionRequest.ConflictPolicy;
import io.github.henryklayvin.morph.contracts.ConversionRequest.InputFile;
import io.github.henryklayvin.morph.contracts.ConversionRequest.OutputSpec;
import io.github.henryklayvin.morph.contracts.ConversionResult;
import io.github.henryklayvin.morph.contracts.ErrorCode;
import io.github.henryklayvin.morph.contracts.FormatDescriptor;
import io.github.henryklayvin.morph.contracts.FormatId;
import io.github.henryklayvin.morph.contracts.MorphException;
import io.github.henryklayvin.morph.contracts.OptionSpec;
import io.github.henryklayvin.morph.contracts.ResourceLimits;
import io.github.henryklayvin.morph.contracts.Signature;
import io.github.henryklayvin.morph.contracts.StandardFormats;
import io.github.henryklayvin.morph.engine.EngineConfig;
import io.github.henryklayvin.morph.engine.ProviderRegistry.Offer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * Rules that every discovered provider must obey. New providers are covered automatically; a new source format only
 * needs a sample here.
 */
class ProviderContractTest {

    private static final Path FIXTURES = Path.of("..", "test-fixtures");
    private static final Map<FormatId, Path> SAMPLES = Map.of(
            StandardFormats.PNG.id(), FIXTURES.resolve("screenshot.png"),
            StandardFormats.JPEG.id(), FIXTURES.resolve("quadrants.jpg"),
            StandardFormats.PDF.id(), FIXTURES.resolve("document-3-pages.pdf"));

    @TempDir
    Path temp;

    private MorphRuntime runtime;

    @BeforeEach
    void start() throws IOException {
        runtime = MorphRuntime.start(new EngineConfig(temp.resolve("work"), 2, Duration.ofSeconds(2),
                ResourceLimits.defaults()));
    }

    @AfterEach
    void stop() {
        runtime.close();
    }

    @Test
    void theExpectedProvidersAreDiscovered() {
        assertEquals(List.of(), runtime.providerFailures());
        assertEquals(List.of("image", "pdf"), runtime.engine().registry().providers().stream()
                .map(p -> p.descriptor().id()).sorted().toList());
    }

    @Test
    void theMvpMatrixIsComplete() {
        List<String> operations = runtime.engine().registry().offers().stream()
                .map(o -> o.capability().operationId()).sorted().toList();
        assertEquals(List.of("jpeg-to-pdf", "jpeg-to-png", "jpeg-to-webp", "pdf-to-jpeg", "pdf-to-png",
                "png-to-jpeg", "png-to-pdf", "png-to-webp"), operations);
    }

    @TestFactory
    Stream<DynamicTest> everyCapabilityIsAvailableAndDeliversAVerifiedResult() {
        return each((capability, sample) -> {
            byte[] before = Files.readAllBytes(sample);
            ConversionResult result = convert(sample, capability);
            assertEquals(capability.operationId(), result.operationId());
            assertFalse(result.outputs().isEmpty());
            FormatDescriptor target = runtime.engine().formats().find(capability.target()).orElseThrow();
            for (var output : result.outputs()) {
                byte[] head = Arrays.copyOf(Files.readAllBytes(output.path()), Signature.MAX_PROBE_BYTES);
                assertTrue(target.matchOffset(head) >= 0, "content must be " + target.displayName());
                assertTrue(output.path().toString().endsWith("." + target.canonicalExtension()));
            }
            if (!capability.cardinality().mayProduceMany()) {
                assertEquals(1, result.outputs().size());
            }
            assertArrayEquals(before, Files.readAllBytes(sample), "sources are read-only");
        });
    }

    @TestFactory
    Stream<DynamicTest> garbageWithAValidSignatureFailsCleanly() {
        return each((capability, sample) -> {
            byte[] garbage = Arrays.copyOf(Arrays.copyOf(Files.readAllBytes(sample), 16), 4096);
            Path file = Files.write(Files.createTempFile(temp, "garbage", ".bin"), garbage);
            MorphException e = assertThrows(MorphException.class, () -> convert(file, capability));
            assertTrue(e.code().category() == ErrorCode.Category.INPUT
                    || e.code().category() == ErrorCode.Category.CONVERSION, e.code()::name);
            assertFalse(e.getMessage().contains(temp.toString()), "messages must not contain paths");
        });
    }

    @TestFactory
    Stream<DynamicTest> nothingRemainsInTheTemporaryArea() {
        return each((capability, sample) -> {
            convert(sample, capability);
            try (Stream<Path> walk = Files.walk(temp.resolve("work"))) {
                assertEquals(List.of("owner.lock"), walk.filter(Files::isRegularFile)
                        .map(p -> p.getFileName().toString()).toList());
            }
        });
    }

    @Test
    void declarationsAreUsable() {
        for (Offer offer : runtime.engine().registry().offers()) {
            Capability capability = offer.capability();
            assertNotNull(SAMPLES.get(capability.source()), "add a sample for " + capability.source());
            for (OptionSpec<?> option : capability.options()) {
                assertFalse(option.description().isBlank());
                assertDoesNotThrow(() -> option.parse(option.defaultValue().toString()),
                        "the default of " + option.key() + " must be accepted as input");
            }
            assertFalse(capability.losses().isEmpty(), "declare losses of " + capability.operationId());
            offer.provider().descriptor().dependencies().forEach(d -> assertFalse(d.license().isBlank()));
        }
    }

    @FunctionalInterface
    private interface Check {
        void run(Capability capability, Path sample) throws Exception;
    }

    private Stream<DynamicTest> each(Check check) {
        return runtime.engine().registry().offers().stream().map(offer -> dynamicTest(
                offer.capability().operationId(), () -> {
                    assertTrue(runtime.engine().registry().availability(offer).available(),
                            runtime.engine().registry().availability(offer).reason());
                    check.run(offer.capability(), SAMPLES.get(offer.capability().source()));
                }));
    }

    private ConversionResult convert(Path sample, Capability capability) throws Exception {
        return runtime.engine().convert(new ConversionRequest(List.of(InputFile.of(sample)), capability.target(),
                ConversionOptions.NONE,
                new OutputSpec(temp.resolve("out"), "result", null, ConflictPolicy.RENAME),
                runtime.engine().config().limits()));
    }
}
