package dev.morph.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morph.contracts.Availability;
import dev.morph.contracts.Cardinality;
import dev.morph.contracts.ConversionOptions;
import dev.morph.contracts.ConversionProvider;
import dev.morph.contracts.ConversionRequest;
import dev.morph.contracts.ConversionRequest.ConflictPolicy;
import dev.morph.contracts.ConversionRequest.InputFile;
import dev.morph.contracts.ConversionRequest.OutputSpec;
import dev.morph.contracts.ConversionResult;
import dev.morph.contracts.ConversionStage;
import dev.morph.contracts.ErrorCode;
import dev.morph.contracts.FormatId;
import dev.morph.contracts.MorphException;
import dev.morph.contracts.ProducedOutput;
import dev.morph.contracts.ResourceLimits;
import dev.morph.contracts.StandardFormats;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
class MorphEngineTest {

    @TempDir
    Path temp;

    private Path input;
    private Path out;
    private Path work;

    @BeforeEach
    void setUp() throws IOException {
        input = Files.write(temp.resolve("photo.png"), FakeProvider.PNG_BYTES);
        out = temp.resolve("out");
        work = temp.resolve("work");
    }

    @Test
    void runsTheEightStagesInOrderAndDeliversTheOutput() throws Exception {
        List<ConversionStage> stages = Collections.synchronizedList(new ArrayList<>());
        try (MorphEngine engine = engine(FakeProvider.pngToJpeg(FakeProvider::writeJpeg))) {
            ConversionResult result = engine.submit(request(ConflictPolicy.FAIL), new JobListener() {
                @Override
                public void onStage(ConversionStage stage) {
                    stages.add(stage);
                }
            }).await();
            assertEquals("png-to-jpeg", result.operationId());
            assertEquals(out.resolve("photo.jpg").toAbsolutePath(), result.outputs().getFirst().path());
            assertArrayEquals(FakeProvider.JPEG_BYTES, Files.readAllBytes(out.resolve("photo.jpg")));
        }
        assertEquals(Arrays.asList(ConversionStage.values()), stages);
    }

    @Test
    void leavesNoTemporaryFilesBehind() throws Exception {
        try (MorphEngine engine = engine(FakeProvider.pngToJpeg(FakeProvider::writeJpeg))) {
            engine.convert(request(ConflictPolicy.FAIL));
            assertEquals(List.of("owner.lock"), filesUnder(work));
        }
        assertEquals(List.of(), filesUnder(work));
        assertEquals(List.of("photo.jpg"), filesUnder(out));
    }

    @Test
    void erasesTemporaryFilesWhenTheProviderFails() throws Exception {
        try (MorphEngine engine = engine(FakeProvider.pngToJpeg(context -> {
            Files.write(context.newOutputFile(), new byte[1000]);
            throw new IOException("disk on fire at C:\\Users\\someone\\secret.png");
        }))) {
            MorphException e = failure(engine, request(ConflictPolicy.FAIL));
            assertEquals(ErrorCode.CONVERSION_FAILED, e.code());
            assertFalse(e.getMessage().contains("secret"), "provider messages must not leak paths");
            assertEquals(List.of("owner.lock"), filesUnder(work));
            assertFalse(Files.exists(out));
        }
    }

    @Test
    void rejectsAnOutputThatIsNotTheRequestedFormat() throws Exception {
        try (MorphEngine engine = engine(FakeProvider.pngToJpeg(context -> {
            Path file = context.newOutputFile();
            Files.write(file, FakeProvider.PNG_BYTES);
            return List.of(new ProducedOutput(file, Map.of(), f -> { }));
        }))) {
            assertEquals(ErrorCode.OUTPUT_VERIFICATION_FAILED,
                    failure(engine, request(ConflictPolicy.FAIL)).code());
            assertFalse(Files.exists(out));
        }
    }

    @Test
    void rejectsAnOutputWhoseOwnCheckFails() throws Exception {
        try (MorphEngine engine = engine(FakeProvider.pngToJpeg(context -> {
            Path file = context.newOutputFile();
            Files.write(file, FakeProvider.JPEG_BYTES);
            return List.of(new ProducedOutput(file, Map.of(), f -> {
                throw new IOException("cannot decode");
            }));
        }))) {
            assertEquals(ErrorCode.OUTPUT_VERIFICATION_FAILED,
                    failure(engine, request(ConflictPolicy.FAIL)).code());
            assertFalse(Files.exists(out));
        }
    }

    @Test
    void rejectsEmptyAndEscapingOutputs() throws Exception {
        Path outside = temp.resolve("outside.jpg");
        Files.write(outside, FakeProvider.JPEG_BYTES);
        try (MorphEngine engine = engine(FakeProvider.pngToJpeg(
                context -> List.of(new ProducedOutput(outside, Map.of(), f -> { }))))) {
            assertEquals(ErrorCode.OUTPUT_VERIFICATION_FAILED,
                    failure(engine, request(ConflictPolicy.FAIL)).code());
        }
        try (MorphEngine engine = engine(FakeProvider.pngToJpeg(context -> {
            Path file = Files.createFile(context.newOutputFile());
            return List.of(new ProducedOutput(file, Map.of(), f -> { }));
        }))) {
            assertEquals(ErrorCode.OUTPUT_VERIFICATION_FAILED,
                    failure(engine, request(ConflictPolicy.FAIL)).code());
        }
        try (MorphEngine engine = engine(FakeProvider.pngToJpeg(c -> FakeProvider.writeJpegs(c, 2)))) {
            assertEquals(ErrorCode.OUTPUT_VERIFICATION_FAILED,
                    failure(engine, request(ConflictPolicy.FAIL)).code(), "one-to-one must produce one file");
        }
    }

    @Test
    void neverOverwritesByDefault() throws Exception {
        Files.createDirectories(out);
        Files.writeString(out.resolve("photo.jpg"), "precious");
        try (MorphEngine engine = engine(FakeProvider.pngToJpeg(FakeProvider::writeJpeg))) {
            assertEquals(ErrorCode.OUTPUT_EXISTS, failure(engine, request(ConflictPolicy.FAIL)).code());
        }
        assertEquals("precious", Files.readString(out.resolve("photo.jpg")));
        assertEquals(List.of("photo.jpg"), filesUnder(out));
    }

    @Test
    void neverOverwritesAFileCreatedWhileConverting() throws Exception {
        try (MorphEngine engine = engine(FakeProvider.pngToJpeg(context -> {
            Files.createDirectories(out);
            Files.writeString(out.resolve("photo.jpg"), "arrived first");
            return FakeProvider.writeJpeg(context);
        }))) {
            assertEquals(ErrorCode.OUTPUT_EXISTS, failure(engine, request(ConflictPolicy.FAIL)).code());
        }
        assertEquals("arrived first", Files.readString(out.resolve("photo.jpg")));
        assertEquals(List.of("photo.jpg"), filesUnder(out));
    }

    @Test
    void renamesOrOverwritesWhenAsked() throws Exception {
        Files.createDirectories(out);
        Files.writeString(out.resolve("photo.jpg"), "old");
        try (MorphEngine engine = engine(FakeProvider.pngToJpeg(FakeProvider::writeJpeg))) {
            assertEquals("photo (1).jpg", name(engine.convert(request(ConflictPolicy.RENAME))));
            assertEquals("photo (2).jpg", name(engine.convert(request(ConflictPolicy.RENAME))));
            assertEquals("old", Files.readString(out.resolve("photo.jpg")));
            assertEquals("photo.jpg", name(engine.convert(request(ConflictPolicy.OVERWRITE))));
            assertArrayEquals(FakeProvider.JPEG_BYTES, Files.readAllBytes(out.resolve("photo.jpg")));
        }
    }

    @Test
    void multipleOutputsAreAllOrNothing() throws Exception {
        Files.createDirectories(out);
        Files.writeString(out.resolve("photo-2.jpg"), "precious");
        ConversionProvider provider = new FakeProvider("many", 0, Cardinality.ONE_TO_MANY, Availability.ready(),
                context -> FakeProvider.writeJpegs(context, 3));
        try (MorphEngine engine = engine(provider)) {
            assertEquals(ErrorCode.OUTPUT_EXISTS, failure(engine, request(ConflictPolicy.FAIL)).code());
            assertEquals(List.of("photo-2.jpg"), filesUnder(out));
            Files.delete(out.resolve("photo-2.jpg"));
            engine.convert(request(ConflictPolicy.FAIL));
            assertEquals(List.of("photo-1.jpg", "photo-2.jpg", "photo-3.jpg"), filesUnder(out));
        }
    }

    @Test
    void cancelsACooperativeConversion() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try (MorphEngine engine = engine(FakeProvider.pngToJpeg(context -> {
            started.countDown();
            while (true) {
                context.cancellation().throwIfCancelled();
                Thread.onSpinWait();
            }
        }))) {
            ConversionJob job = engine.submit(request(ConflictPolicy.FAIL));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            job.cancel();
            MorphException e = assertThrows(MorphException.class, job::await);
            assertEquals(ErrorCode.CANCELLED, e.code());
            assertEquals(ConversionJob.State.CANCELLED, job.state());
            assertFalse(Files.exists(out));
        }
    }

    @Test
    void interruptsBlockedWorkOnCancellation() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try (MorphEngine engine = engine(FakeProvider.pngToJpeg(context -> {
            started.countDown();
            Thread.sleep(60_000);
            return FakeProvider.writeJpeg(context);
        }))) {
            ConversionJob job = engine.submit(request(ConflictPolicy.FAIL));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            job.cancel();
            assertEquals(ErrorCode.CANCELLED, assertThrows(MorphException.class, job::await).code());
            // The worker must be reusable and not carry the interruption over.
        }
        try (MorphEngine engine = engine(FakeProvider.pngToJpeg(FakeProvider::writeJpeg))) {
            engine.convert(request(ConflictPolicy.FAIL));
        }
    }

    @Test
    void cancellationWinsOverTheMisleadingErrorsItCauses() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try (MorphEngine engine = engine(FakeProvider.pngToJpeg(context -> {
            started.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                throw new MorphException(ErrorCode.CORRUPT_INPUT, "looks corrupt");
            }
            return List.of();
        }))) {
            ConversionJob job = engine.submit(request(ConflictPolicy.FAIL));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            job.cancel();
            assertEquals(ErrorCode.CANCELLED, assertThrows(MorphException.class, job::await).code());
        }
    }

    @Test
    void reportsATimeoutEvenWhenTheProviderIgnoresIt() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        MorphEngine engine = engine(FakeProvider.pngToJpeg(context -> {
            while (release.getCount() > 0) {
                Thread.interrupted(); // a decoder that swallows interruption
                Thread.onSpinWait();
            }
            finished.countDown();
            return FakeProvider.writeJpeg(context);
        }));
        try (engine) {
            ConversionRequest base = request(ConflictPolicy.FAIL);
            ConversionRequest quick = new ConversionRequest(base.inputs(), base.target(), base.options(),
                    base.output(), base.limits().withTimeout(Duration.ofMillis(200)));
            ConversionJob job = engine.submit(quick);
            MorphException e = assertThrows(MorphException.class, job::await);
            assertEquals(ErrorCode.TIMED_OUT, e.code());
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
        }
        assertFalse(Files.exists(out), "an abandoned job must never deliver its output");
        assertEquals(List.of(), filesUnder(work));
    }

    @Test
    void cancellingDuringVerificationPreventsDelivery() throws Exception {
        AtomicReference<ConversionJob> handle = new AtomicReference<>();
        CountDownLatch submitted = new CountDownLatch(1);
        try (MorphEngine engine = engine(FakeProvider.pngToJpeg(context -> {
            Path file = context.newOutputFile();
            Files.write(file, FakeProvider.JPEG_BYTES);
            return List.of(new ProducedOutput(file, Map.of(), f -> {
                submitted.await();
                handle.get().cancel();
                Thread.interrupted();
            }));
        }))) {
            handle.set(engine.submit(request(ConflictPolicy.FAIL)));
            submitted.countDown();
            assertEquals(ErrorCode.CANCELLED, assertThrows(MorphException.class, handle.get()::await).code());
        }
        assertFalse(Files.exists(out));
    }

    @Test
    void lateCancellationDoesNotUndoADeliveredResult() throws Exception {
        try (MorphEngine engine = engine(FakeProvider.pngToJpeg(FakeProvider::writeJpeg))) {
            ConversionJob job = engine.submit(request(ConflictPolicy.FAIL));
            job.await();
            job.cancel();
            assertEquals(ConversionJob.State.SUCCEEDED, job.state());
            assertTrue(Files.exists(out.resolve("photo.jpg")));
        }
    }

    @Test
    void choosesTheBestAvailableProvider() throws Exception {
        FakeProvider low = new FakeProvider("low", 1, Cardinality.ONE_TO_ONE, Availability.ready(),
                FakeProvider::writeJpeg);
        FakeProvider high = new FakeProvider("high", 10, Cardinality.ONE_TO_ONE, Availability.ready(),
                FakeProvider::writeJpeg);
        FakeProvider broken = new FakeProvider("best-but-missing", 99, Cardinality.ONE_TO_ONE,
                Availability.unavailable("ferramenta ausente"), FakeProvider::writeJpeg);
        try (MorphEngine engine = engine(low, broken, high)) {
            assertEquals("high", engine.convert(request(ConflictPolicy.FAIL)).providerId());
        }
        try (MorphEngine engine = engine(broken)) {
            MorphException e = failure(engine, request(ConflictPolicy.OVERWRITE));
            assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, e.code());
            assertTrue(e.getMessage().contains("ferramenta ausente"));
        }
    }

    @Test
    void explainsUnsupportedConversions() throws Exception {
        try (MorphEngine engine = engine(FakeProvider.pngToJpeg(FakeProvider::writeJpeg))) {
            MorphException e = failure(engine, request("webp", ConversionOptions.NONE));
            assertEquals(ErrorCode.UNSUPPORTED_CONVERSION, e.code());
            assertTrue(e.getMessage().contains("jpeg"), "must list what is possible");
        }
    }

    @Test
    void recognisesDocxButReportsThatNoProviderConvertsIt() throws Exception {
        input = Files.write(temp.resolve("doc.docx"), new byte[] {'P', 'K', 3, 4, 0, 0});
        try (MorphEngine engine = engine(FakeProvider.pngToJpeg(FakeProvider::writeJpeg))) {
            MorphException e = failure(engine, request("pdf", ConversionOptions.NONE));
            assertEquals(ErrorCode.UNSUPPORTED_CONVERSION, e.code());
            assertTrue(e.getMessage().contains("DOCX"));
        }
    }

    @Test
    void validatesOptions() throws Exception {
        try (MorphEngine engine = engine(FakeProvider.pngToJpeg(FakeProvider::writeJpeg))) {
            assertEquals(ErrorCode.INVALID_OPTION,
                    failure(engine, request("jpeg", new ConversionOptions(Map.of("quality", "101")))).code());
            assertEquals(ErrorCode.INVALID_OPTION,
                    failure(engine, request("jpeg", new ConversionOptions(Map.of("qualty", "80")))).code());
        }
    }

    @Test
    void refusesUnsafeOutputNames() throws Exception {
        try (MorphEngine engine = engine(FakeProvider.pngToJpeg(FakeProvider::writeJpeg))) {
            for (String name : List.of("../evil", "a/b", "a\\b", "nul", "x:stream", "", ".", "..")) {
                ConversionRequest bad = new ConversionRequest(List.of(InputFile.of(input)), FormatId.of("jpeg"),
                        ConversionOptions.NONE, new OutputSpec(out, name, null, ConflictPolicy.FAIL),
                        ResourceLimits.defaults());
                assertEquals(ErrorCode.INVALID_REQUEST, failure(engine, bad).code(), name);
            }
        }
        assertFalse(Files.exists(out));
    }

    @Test
    void inputsAreNeverModified() throws Exception {
        try (MorphEngine engine = engine(FakeProvider.pngToJpeg(FakeProvider::writeJpeg))) {
            engine.convert(request(ConflictPolicy.FAIL));
        }
        assertArrayEquals(FakeProvider.PNG_BYTES, Files.readAllBytes(input));
    }

    @Test
    void aProviderThatRunsOutOfMemoryDoesNotKillTheEngine() throws Exception {
        try (MorphEngine engine = engine(FakeProvider.pngToJpeg(context -> {
            throw new OutOfMemoryError("simulated");
        }))) {
            assertEquals(ErrorCode.RESOURCE_LIMIT_EXCEEDED,
                    failure(engine, request(ConflictPolicy.FAIL)).code());
            assertEquals(ErrorCode.RESOURCE_LIMIT_EXCEEDED,
                    failure(engine, request(ConflictPolicy.FAIL)).code());
        }
    }

    private MorphEngine engine(ConversionProvider... providers) throws IOException {
        return new MorphEngine(new EngineConfig(work, 1, Duration.ofMillis(300), ResourceLimits.defaults()),
                List.of(providers));
    }

    private ConversionRequest request(ConflictPolicy policy) {
        return new ConversionRequest(List.of(InputFile.of(input)), StandardFormats.JPEG.id(), ConversionOptions.NONE,
                new OutputSpec(out, "photo", null, policy), ResourceLimits.defaults());
    }

    private ConversionRequest request(String target, ConversionOptions options) {
        return new ConversionRequest(List.of(InputFile.of(input)), FormatId.of(target), options,
                new OutputSpec(out, "photo", null, ConflictPolicy.FAIL), ResourceLimits.defaults());
    }

    private static MorphException failure(MorphEngine engine, ConversionRequest request) {
        return assertThrows(MorphException.class, () -> engine.convert(request));
    }

    private static String name(ConversionResult result) {
        return result.outputs().getFirst().path().getFileName().toString();
    }

    private static List<String> filesUnder(Path root) throws IOException {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).sorted().toList();
        }
    }
}
