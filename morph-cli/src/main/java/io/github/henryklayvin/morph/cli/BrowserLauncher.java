package io.github.henryklayvin.morph.cli;

import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.net.URI;
import java.util.List;
import java.util.Locale;

/** Opens the default browser. The process receives an argument list, never a shell command line. */
final class BrowserLauncher {

    private BrowserLauncher() {
    }

    static boolean open(URI link) {
        try {
            new ProcessBuilder(command(System.getProperty("os.name"), link))
                    .redirectOutput(Redirect.DISCARD)
                    .redirectError(Redirect.DISCARD)
                    .start();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static List<String> command(String osName, URI link) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            return List.of("rundll32", "url.dll,FileProtocolHandler", link.toString());
        }
        return List.of(os.startsWith("mac") ? "open" : "xdg-open", link.toString());
    }
}
