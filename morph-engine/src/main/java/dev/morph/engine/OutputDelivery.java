package dev.morph.engine;

import dev.morph.contracts.ConversionRequest.ConflictPolicy;
import dev.morph.contracts.ConversionRequest.OutputSpec;
import dev.morph.contracts.ErrorCode;
import dev.morph.contracts.FormatDescriptor;
import dev.morph.contracts.MorphException;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Moves verified outputs to their destination. Readers never observe a partially written file: bytes are staged
 * next to the destination and renamed into place. Names are reserved with CREATE_NEW first, so "do not overwrite"
 * holds even when another program creates the same file at the same moment.
 */
final class OutputDelivery {

    private static final int MAX_RENAME_ATTEMPTS = 10_000;

    private OutputDelivery() {
    }

    static Path directory(OutputSpec spec) {
        return spec.directory().toAbsolutePath().normalize();
    }

    static String extension(OutputSpec spec, FormatDescriptor target) {
        return spec.extension() == null ? target.canonicalExtension() : spec.extension();
    }

    /** Validates names before any work is done, so that a doomed request fails immediately. */
    static void validate(OutputSpec spec, FormatDescriptor target, boolean singleOutput) throws MorphException {
        if (!FileNames.isSafe(spec.baseName())) {
            throw new MorphException(ErrorCode.INVALID_REQUEST, "O nome do arquivo de saída não é válido.");
        }
        String extension = extension(spec, target);
        if (!target.extensions().contains(extension.toLowerCase(Locale.ROOT))) {
            throw new MorphException(ErrorCode.INVALID_REQUEST,
                    "A extensão de saída não corresponde ao formato %s.".formatted(target.displayName()));
        }
        Path directory = directory(spec);
        if (Files.exists(directory) && !Files.isDirectory(directory)) {
            throw new MorphException(ErrorCode.INVALID_REQUEST, "O destino não é um diretório.");
        }
        if (singleOutput && spec.onConflict() == ConflictPolicy.FAIL
                && Files.exists(directory.resolve(spec.baseName() + "." + extension), LinkOption.NOFOLLOW_LINKS)) {
            throw alreadyExists();
        }
    }

    static List<Path> deliver(List<Path> produced, OutputSpec spec, FormatDescriptor target) throws MorphException {
        Path directory = directory(spec);
        List<Path> staged = new ArrayList<>();
        List<Path> created = new ArrayList<>();
        List<Path> delivered = new ArrayList<>();
        try {
            Files.createDirectories(directory);
            for (Path file : produced) {
                Path part = directory.resolve(".morph-" + UUID.randomUUID() + ".part");
                staged.add(part);
                Files.copy(file, part);
            }
            String extension = extension(spec, target);
            for (int i = 0; i < produced.size(); i++) {
                String base = produced.size() == 1 ? spec.baseName() : spec.baseName() + "-" + (i + 1);
                delivered.add(reserve(directory, base, extension, spec.onConflict(), created));
            }
            for (int i = 0; i < staged.size(); i++) {
                replace(staged.get(i), delivered.get(i));
            }
            return List.copyOf(delivered);
        } catch (MorphException e) {
            rollback(staged, created);
            throw e;
        } catch (IOException e) {
            rollback(staged, created);
            throw new MorphException(ErrorCode.OUTPUT_WRITE_FAILED,
                    "Não foi possível gravar o resultado no destino.", e);
        }
    }

    private static Path reserve(Path directory, String base, String extension, ConflictPolicy policy,
            List<Path> created) throws IOException, MorphException {
        Path wanted = directory.resolve(base + "." + extension);
        if (policy == ConflictPolicy.OVERWRITE) {
            if (Files.isDirectory(wanted, LinkOption.NOFOLLOW_LINKS)) {
                throw alreadyExists();
            }
            return wanted;
        }
        for (int attempt = 0; attempt < MAX_RENAME_ATTEMPTS; attempt++) {
            Path candidate = attempt == 0 ? wanted
                    : directory.resolve("%s (%d).%s".formatted(base, attempt, extension));
            try {
                Files.createFile(candidate);
                created.add(candidate);
                return candidate;
            } catch (FileAlreadyExistsException e) {
                if (policy == ConflictPolicy.FAIL) {
                    throw alreadyExists();
                }
            }
        }
        throw alreadyExists();
    }

    private static void replace(Path part, Path destination) throws IOException {
        try {
            Files.move(part, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(part, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void rollback(List<Path> staged, List<Path> created) {
        for (List<Path> group : List.of(staged, created)) {
            for (Path path : group) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Nothing else can be done; the original error is the one worth reporting.
                }
            }
        }
    }

    private static MorphException alreadyExists() {
        return new MorphException(ErrorCode.OUTPUT_EXISTS, "Já existe um arquivo com esse nome no destino.");
    }
}
