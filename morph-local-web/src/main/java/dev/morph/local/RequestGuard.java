package dev.morph.local;

import io.javalin.http.Context;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Decides who may talk to the local service. "It only listens on localhost" is not access control: any web page the
 * user visits can send requests to localhost, and so can other accounts on the same machine.
 *
 * <ul>
 *   <li>Host allow-list: defeats DNS rebinding.</li>
 *   <li>Origin and Fetch Metadata: defeats cross-site requests (no CORS header is ever sent either).</li>
 *   <li>Bearer token in a header, never in a cookie: there is no ambient credential for CSRF to abuse.</li>
 *   <li>The token is obtained with a single-use code carried in the URL fragment, which browsers do not send over
 *       the network and which is worthless to whoever reads it later from a process list or the history.</li>
 * </ul>
 */
final class RequestGuard {

    private static final Map<String, String> SECURITY_HEADERS = Map.of(
            "Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; "
                    + "img-src 'self' blob:; font-src 'self'; connect-src 'self'; manifest-src 'self'; "
                    + "base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
            "X-Content-Type-Options", "nosniff",
            "X-Frame-Options", "DENY",
            "Referrer-Policy", "no-referrer",
            "Cross-Origin-Opener-Policy", "same-origin",
            "Cross-Origin-Resource-Policy", "same-origin",
            "Permissions-Policy", "camera=(), microphone=(), geolocation=()",
            "Cache-Control", "no-store");

    private final SecureRandom random = new SecureRandom();
    private final String token = randomSecret();
    private final AtomicReference<String> launchCode = new AtomicReference<>(randomSecret());
    private final Consumer<String> onNewCode;
    private volatile Set<String> hosts = Set.of();
    private volatile Set<String> origins = Set.of();

    RequestGuard(Consumer<String> onNewCode) {
        this.onNewCode = onNewCode;
    }

    /** Must be called with the real port before any request is accepted; until then everything is refused. */
    void bind(int port) {
        hosts = Set.of("127.0.0.1:" + port, "localhost:" + port);
        origins = Set.of("http://127.0.0.1:" + port, "http://localhost:" + port);
    }

    String launchCode() {
        return launchCode.get();
    }

    /** Runs before everything, static files included. */
    void checkTransport(Context ctx) {
        SECURITY_HEADERS.forEach(ctx::header);
        String host = ctx.header("Host");
        if (host == null || !hosts.contains(host)) {
            throw new ApiException(403, "FORBIDDEN_HOST", "Host não permitido.");
        }
        String origin = ctx.header("Origin");
        if (origin != null && !origins.contains(origin)) {
            throw new ApiException(403, "FORBIDDEN_ORIGIN", "Origem não permitida.");
        }
        String site = ctx.header("Sec-Fetch-Site");
        if (site != null && !site.equals("same-origin") && !site.equals("none")) {
            throw new ApiException(403, "FORBIDDEN_ORIGIN", "Origem não permitida.");
        }
    }

    /** Runs before every API call except the code exchange. */
    void checkToken(Context ctx) {
        String header = ctx.header("Authorization");
        if (header == null || !header.startsWith("Bearer ") || !same(header.substring(7), token)) {
            throw new ApiException(401, "UNAUTHORIZED", "Sessão inválida. Abra o link exibido pelo 'morph web'.");
        }
    }

    /** Exchanges the single-use code for the session token. */
    String redeem(String code) {
        String expected = launchCode.get();
        if (code == null || !same(code, expected) || !launchCode.compareAndSet(expected, randomSecret())) {
            throw new ApiException(401, "UNAUTHORIZED",
                    "Este link já foi usado. Use o link mais recente exibido no terminal.");
        }
        onNewCode.accept(launchCode.get());
        return token;
    }

    private static boolean same(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private String randomSecret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
