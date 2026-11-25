package io.github.henryklayvin.morph.provider.pdf;

import io.github.henryklayvin.morph.contracts.ErrorCode;
import io.github.henryklayvin.morph.contracts.MorphException;
import io.github.henryklayvin.morph.contracts.ResourceLimits;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

/** PDFBox configuration shared by both directions. */
final class PdfDocuments {

    /** Strong references: java.util.logging forgets the level of loggers that nobody holds. */
    private static final Logger PDFBOX_LOG = Logger.getLogger("org.apache.pdfbox");
    private static final Logger FONTBOX_LOG = Logger.getLogger("org.apache.fontbox");

    static {
        // Log messages of a parser may quote the document being parsed.
        PDFBOX_LOG.setLevel(Level.OFF);
        FONTBOX_LOG.setLevel(Level.OFF);
        configureFontCache();
    }

    private PdfDocuments() {
    }

    static PDDocument open(Path file, ResourceLimits limits, Path temporaryDirectory)
            throws IOException, MorphException {
        // PDFBox spools to the system temporary directory unless told otherwise.
        MemoryUsageSetting memory = MemoryUsageSetting
                .setupMixed(limits.maxWorkingMemoryBytes() / 4, limits.maxOutputBytes())
                .setTempDir(temporaryDirectory.toFile());
        PDDocument document;
        try {
            document = Loader.loadPDF(file.toFile(), "", memory.streamCache);
        } catch (InvalidPasswordException e) {
            throw new MorphException(ErrorCode.PROTECTED_INPUT, "O PDF é protegido por senha.", e);
        } catch (IOException | RuntimeException e) {
            throw new MorphException(ErrorCode.CORRUPT_INPUT, "O PDF está corrompido ou não pôde ser lido.", e);
        }
        int pages = document.getNumberOfPages();
        if (pages == 0 || pages > limits.maxPages()) {
            document.close();
            throw pages == 0
                    ? new MorphException(ErrorCode.CORRUPT_INPUT, "O PDF não possui páginas.")
                    : new MorphException(ErrorCode.RESOURCE_LIMIT_EXCEEDED,
                            "O PDF possui %d páginas e excede o limite de %d.".formatted(pages, limits.maxPages()));
        }
        return document;
    }

    /** PDFBox indexes the system fonts into a file, by default in the user's home; keep it in a cache directory. */
    private static void configureFontCache() {
        if (System.getProperty("pdfbox.fontcache") != null) {
            return;
        }
        try {
            Path cache = userCacheDirectory().resolve("pdf-fonts");
            Files.createDirectories(cache);
            System.setProperty("pdfbox.fontcache", cache.toString());
        } catch (IOException | RuntimeException e) {
            // Only costs a font scan per run.
        }
    }

    private static Path userCacheDirectory() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        Path home = Path.of(System.getProperty("user.home"));
        if (os.startsWith("windows")) {
            String local = System.getenv("LOCALAPPDATA");
            return (local == null ? home.resolve("AppData/Local") : Path.of(local)).resolve("Morph").resolve("cache");
        }
        if (os.startsWith("mac")) {
            return home.resolve("Library/Caches/Morph");
        }
        String xdg = System.getenv("XDG_CACHE_HOME");
        return (xdg == null || xdg.isBlank() ? home.resolve(".cache") : Path.of(xdg)).resolve("morph");
    }
}
