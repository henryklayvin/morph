package dev.morph.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morph.contracts.ConversionRequest.InputFile;
import dev.morph.contracts.ErrorCode;
import dev.morph.contracts.FormatCategory;
import dev.morph.contracts.FormatDescriptor;
import dev.morph.contracts.FormatId;
import dev.morph.contracts.MorphException;
import dev.morph.contracts.ResourceLimits;
import dev.morph.contracts.Signature;
import dev.morph.contracts.StandardFormats;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FormatInspectorTest {

    @TempDir
    Path temp;

    private final FormatRegistry registry = new FormatRegistry(StandardFormats.all());
    private final FormatInspector inspector = new FormatInspector(registry);

    @Test
    void contentWinsOverExtensionAndTheMismatchIsReported() throws Exception {
        Path file = Files.write(temp.resolve("fake.jpg"), FakeProvider.PNG_BYTES);
        FormatInspector.Inspection inspection = inspect(file);
        assertEquals(StandardFormats.PNG, inspection.source().format());
        assertTrue(inspection.warning().isPresent());
    }

    @Test
    void matchingExtensionProducesNoWarning() throws Exception {
        Path file = Files.write(temp.resolve("a.JPEG"), FakeProvider.JPEG_BYTES);
        assertEquals(StandardFormats.JPEG, inspect(file).source().format());
        assertFalse(inspect(file).warning().isPresent());
    }

    @Test
    void detectsPdfWithLeadingJunkButDoesNotMistakeAPolyglotPng() throws Exception {
        Path pdf = Files.writeString(temp.resolve("a.pdf"), "junk junk\n%PDF-1.7\n", StandardCharsets.US_ASCII);
        assertEquals(StandardFormats.PDF, inspect(pdf).source().format());

        byte[] polyglot = new byte[64];
        System.arraycopy(FakeProvider.PNG_BYTES, 0, polyglot, 0, 8);
        System.arraycopy("%PDF-1.7".getBytes(StandardCharsets.US_ASCII), 0, polyglot, 20, 8);
        assertEquals(StandardFormats.PNG,
                inspect(Files.write(temp.resolve("p.png"), polyglot)).source().format());
    }

    @Test
    void riffFilesAreWebpOnlyWithTheWebpMarker() throws Exception {
        byte[] wav = "RIFF1234WAVEfmt ".getBytes(StandardCharsets.US_ASCII);
        byte[] webp = "RIFF1234WEBPVP8 ".getBytes(StandardCharsets.US_ASCII);
        assertEquals(StandardFormats.WEBP, inspect(Files.write(temp.resolve("a.webp"), webp)).source().format());
        assertEquals(ErrorCode.UNRECOGNIZED_FORMAT,
                failure(Files.write(temp.resolve("b.webp"), wav)).code());
    }

    @Test
    void aZipIsNotADocxUnlessItsNameSaysSo() throws Exception {
        byte[] zip = {'P', 'K', 3, 4, 20, 0};
        assertEquals(StandardFormats.DOCX, inspect(Files.write(temp.resolve("a.docx"), zip)).source().format());
        assertEquals(ErrorCode.UNRECOGNIZED_FORMAT, failure(Files.write(temp.resolve("a.zip"), zip)).code());
    }

    @Test
    void reportsMissingEmptyDirectoryAndOversizedInputs() throws Exception {
        assertEquals(ErrorCode.INPUT_NOT_FOUND, failure(temp.resolve("nope.png")).code());
        assertEquals(ErrorCode.INPUT_EMPTY, failure(Files.createFile(temp.resolve("empty.png"))).code());
        assertEquals(ErrorCode.INPUT_NOT_READABLE, failure(temp).code());
        Path file = Files.write(temp.resolve("big.png"), FakeProvider.PNG_BYTES);
        ResourceLimits tiny = new ResourceLimits(5, 5, 5, 5, 5, Duration.ofSeconds(1));
        MorphException e = assertThrows(MorphException.class, () -> inspector.inspect(InputFile.of(file), tiny));
        assertEquals(ErrorCode.RESOURCE_LIMIT_EXCEEDED, e.code());
    }

    @Test
    void conflictingFormatDeclarationsFailFast() {
        FormatDescriptor impostor = new FormatDescriptor(FormatId.of("png"), "Not PNG", FormatCategory.IMAGE,
                List.of("png"), "image/x", List.of(Signature.hex("00")));
        assertThrows(IllegalStateException.class,
                () -> new FormatRegistry(List.of(StandardFormats.PNG, impostor)));
    }

    @Test
    void resolvesWhatPeopleType() throws Exception {
        assertEquals(StandardFormats.JPEG, registry.resolve("JPG"));
        assertEquals(StandardFormats.JPEG, registry.resolve(".jpeg"));
        assertEquals(StandardFormats.WEBP, registry.resolve(" webp "));
        assertThrows(MorphException.class, () -> registry.resolve("banana"));
        assertThrows(MorphException.class, () -> registry.resolve(""));
    }

    private FormatInspector.Inspection inspect(Path file) throws MorphException {
        return inspector.inspect(InputFile.of(file), ResourceLimits.defaults());
    }

    private MorphException failure(Path file) {
        return assertThrows(MorphException.class, () -> inspect(file));
    }
}
