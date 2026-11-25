package io.github.henryklayvin.morph.engine;

import java.util.Locale;
import java.util.Set;

/** Safe handling of file names that come from users or from the browser. */
public final class FileNames {

    private static final int MAX_BASE_LENGTH = 120;
    private static final Set<String> WINDOWS_RESERVED = Set.of("con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    private FileNames() {
    }

    /** Lower-case extension without the dot, or an empty string. */
    public static String extension(String fileName) {
        String name = lastSegment(fileName);
        int dot = name.lastIndexOf('.');
        return dot <= 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Name without directories and without the last extension. Not necessarily safe; see {@link #sanitize}. */
    public static String base(String fileName) {
        String name = lastSegment(fileName);
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    /** Whether the text can be used as a file name component on every supported platform. */
    public static boolean isSafe(String name) {
        return !name.isEmpty() && name.length() <= MAX_BASE_LENGTH && name.equals(sanitize(name));
    }

    /**
     * Turns any text into a plain, portable name: no separators, no control characters, no NTFS stream marker
     * ({@code :}), no reserved device names, no leading or trailing dots and spaces.
     */
    public static String sanitize(String raw) {
        StringBuilder out = new StringBuilder(Math.min(raw.length(), MAX_BASE_LENGTH));
        raw.codePoints().forEach(cp -> {
            boolean forbidden = cp < 0x20 || (cp >= 0x7f && cp <= 0x9f) || "<>:\"/\\|?*".indexOf(cp) >= 0
                    || Character.getType(cp) == Character.FORMAT
                    || Character.getType(cp) == Character.SURROGATE;
            out.appendCodePoint(forbidden ? '_' : cp);
        });
        String name = out.toString();
        if (name.length() > MAX_BASE_LENGTH) {
            int end = Character.isHighSurrogate(name.charAt(MAX_BASE_LENGTH - 1))
                    ? MAX_BASE_LENGTH - 1 : MAX_BASE_LENGTH;
            name = name.substring(0, end);
        }
        name = name.replaceAll("^[. ]+|[. ]+$", "");
        String stem = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        if (WINDOWS_RESERVED.contains(stem.toLowerCase(Locale.ROOT))) {
            name = "_" + name;
        }
        return name.isEmpty() ? "arquivo" : name;
    }

    private static String lastSegment(String fileName) {
        int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        return fileName.substring(slash + 1);
    }
}
