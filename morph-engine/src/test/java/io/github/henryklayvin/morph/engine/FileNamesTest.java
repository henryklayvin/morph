package io.github.henryklayvin.morph.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class FileNamesTest {

    @ParameterizedTest
    @CsvSource({
            "photo.PNG, photo, png",
            "archive.tar.gz, archive.tar, gz",
            ".gitignore, .gitignore, ''",
            "noext, noext, ''",
            "trailing., trailing, ''",
            "C:\\Users\\a\\b.jpg, b, jpg",
            "/home/a/b.jpg, b, jpg",
    })
    void splitsNames(String name, String base, String extension) {
        assertEquals(base, FileNames.base(name));
        assertEquals(extension, FileNames.extension(name));
    }

    @ParameterizedTest
    @ValueSource(strings = {"férias 2024", "a-b_c (1)", "日本語", "emoji 🙂", "v1.2"})
    void keepsOrdinaryNamesUntouched(String name) {
        assertTrue(FileNames.isSafe(name));
        assertEquals(name, FileNames.sanitize(name));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../../etc/passwd", "..\\..\\x", "a:b", "nul", "CON", "com1.backup", "a\u0000b",
            "evil\u202Egnp", " x ", "dots...", "", "...", "a\nb"})
    void neutralisesHostileNames(String name) {
        assertFalse(FileNames.isSafe(name));
        String safe = FileNames.sanitize(name);
        assertTrue(FileNames.isSafe(safe), safe);
        assertFalse(safe.contains("/") || safe.contains("\\") || safe.contains(":"));
    }

    @Test
    void limitsLengthWithoutBreakingSurrogatePairs() {
        String safe = FileNames.sanitize("🙂".repeat(200));
        assertTrue(safe.length() <= 120);
        assertTrue(FileNames.isSafe(safe));
    }
}
