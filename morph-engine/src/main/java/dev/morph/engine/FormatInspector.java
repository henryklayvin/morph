package dev.morph.engine;

import dev.morph.contracts.ConversionRequest.InputFile;
import dev.morph.contracts.ErrorCode;
import dev.morph.contracts.FormatDescriptor;
import dev.morph.contracts.MorphException;
import dev.morph.contracts.ResourceLimits;
import dev.morph.contracts.Signature;
import dev.morph.contracts.SourceFile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Identifies files by content. The extension is only a tie-breaker between formats sharing a container signature
 * and a reason to warn; it never overrides what the bytes say.
 */
public final class FormatInspector {

    private final FormatRegistry formats;

    public FormatInspector(FormatRegistry formats) {
        this.formats = formats;
    }

    public record Inspection(SourceFile source, Optional<String> warning) {
    }

    public Inspection inspect(InputFile input, ResourceLimits limits) throws MorphException {
        Path path = input.path();
        if (!Files.exists(path)) {
            throw new MorphException(ErrorCode.INPUT_NOT_FOUND, "O arquivo não foi encontrado.");
        }
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new MorphException(ErrorCode.INPUT_NOT_READABLE,
                    "A entrada não é um arquivo comum ou não pode ser lida.");
        }
        long size;
        byte[] head;
        try (InputStream in = Files.newInputStream(path)) {
            size = Files.size(path);
            head = in.readNBytes(Signature.MAX_PROBE_BYTES);
        } catch (IOException e) {
            throw new MorphException(ErrorCode.INPUT_NOT_READABLE, "O arquivo não pode ser lido.", e);
        }
        if (size == 0) {
            throw new MorphException(ErrorCode.INPUT_EMPTY, "O arquivo está vazio.");
        }
        if (size > limits.maxInputBytes()) {
            throw new MorphException(ErrorCode.RESOURCE_LIMIT_EXCEEDED,
                    "O arquivo excede o limite de %d MiB.".formatted(limits.maxInputBytes() >> 20));
        }

        String extension = FileNames.extension(input.displayName());
        FormatDescriptor format = detect(head, extension);
        Optional<String> warning = Optional.empty();
        if (!extension.isEmpty() && !format.extensions().contains(extension)) {
            warning = Optional.of("A extensão não corresponde ao conteúdo: o arquivo foi tratado como %s."
                    .formatted(format.displayName()));
        }
        return new Inspection(new SourceFile(path, format, size), warning);
    }

    /** Used on outputs: does the content really belong to the expected format? */
    public boolean contentMatches(Path file, FormatDescriptor expected) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return expected.matchOffset(in.readNBytes(Signature.MAX_PROBE_BYTES)) >= 0;
        }
    }

    private FormatDescriptor detect(byte[] head, String extension) throws MorphException {
        int best = formats.all().stream().mapToInt(f -> f.matchOffset(head)).filter(o -> o >= 0).min().orElse(-1);
        // Smallest offset wins: a PNG that merely embeds "%PDF-" in its first kilobyte is still a PNG.
        List<FormatDescriptor> candidates = formats.all().stream()
                .filter(f -> best >= 0 && f.matchOffset(head) == best)
                .toList();
        Optional<FormatDescriptor> byExtension = candidates.stream()
                .filter(f -> f.extensions().contains(extension))
                .findFirst();
        if (byExtension.isPresent()) {
            return byExtension.get();
        }
        // Container families (ZIP) cannot be told apart by signature alone, so they need a matching extension.
        if (candidates.size() == 1 && !candidates.getFirst().containerBased()) {
            return candidates.getFirst();
        }
        throw new MorphException(ErrorCode.UNRECOGNIZED_FORMAT,
                "O conteúdo do arquivo não corresponde a nenhum formato conhecido.");
    }
}
