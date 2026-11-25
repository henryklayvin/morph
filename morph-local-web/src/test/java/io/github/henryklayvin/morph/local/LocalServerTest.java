package io.github.henryklayvin.morph.local;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.henryklayvin.morph.application.MorphRuntime;
import io.github.henryklayvin.morph.contracts.ConversionOptions;
import io.github.henryklayvin.morph.contracts.ConversionRequest;
import io.github.henryklayvin.morph.contracts.FormatId;
import io.github.henryklayvin.morph.contracts.ResourceLimits;
import io.github.henryklayvin.morph.engine.EngineConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Timeout(60)
class LocalServerTest {

    private static final Path FIXTURES = Path.of("..", "test-fixtures");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temp;

    private final List<URI> freshLinks = new CopyOnWriteArrayList<>();
    private final HttpClient http = HttpClient.newHttpClient();
    private MorphRuntime runtime;
    private LocalServer server;
    private String token;

    @BeforeEach
    void start() throws Exception {
        runtime = MorphRuntime.start(new EngineConfig(temp.resolve("work"), 2, Duration.ofSeconds(2),
                ResourceLimits.defaults()));
        server = LocalServer.start(runtime, new LocalServer.Options(0, 1 << 20, 3 << 20, Duration.ofHours(1)),
                freshLinks::add);
        token = redeem(code(server.launchUrl())).get("token").asString();
    }

    @AfterEach
    void stop() {
        http.close();
        server.close();
        runtime.close();
    }

    // ---- conversion flow ----

    @Test
    void convertsThroughTheEngineFromUploadToDownload() throws Exception {
        byte[] source = Files.readAllBytes(FIXTURES.resolve("screenshot.png"));
        JsonNode file = upload("férias & praia.png", source);
        assertEquals("png", file.get("format").asString());
        assertEquals(List.of("jpeg", "pdf", "webp"), texts(file.get("targets")));

        JsonNode job = finish(createJob("""
                {"inputs":["%s"],"target":"webp","options":{"quality":"70"}}""".formatted(id(file))));
        assertEquals("SUCCEEDED", job.get("state").asString());
        JsonNode output = job.get("result").get("outputs").get(0);
        assertEquals("férias & praia.webp", output.get("name").asString());
        assertEquals("image", job.get("result").get("provider").asString());

        HttpResponse<byte[]> download = get("/api/v1/jobs/" + id(job) + "/outputs/0");
        assertEquals(200, download.statusCode());
        assertEquals("application/octet-stream", download.headers().firstValue("Content-Type").orElseThrow());
        assertTrue(download.headers().firstValue("Content-Disposition").orElseThrow().startsWith("attachment"));
        assertEquals(output.get("size").asInt(), download.body().length);
        assertEquals("WEBP", new String(download.body(), 8, 4, StandardCharsets.US_ASCII));
    }

    @Test
    void aPdfProducesSeveralDownloads() throws Exception {
        JsonNode file = upload("doc.pdf", Files.readAllBytes(FIXTURES.resolve("document-3-pages.pdf")));
        JsonNode job = finish(createJob("""
                {"inputs":["%s"],"target":"png","options":{"dpi":"36"}}""".formatted(id(file))));
        assertEquals(3, job.get("result").get("outputs").size());
        for (int i = 0; i < 3; i++) {
            assertEquals(200, get("/api/v1/jobs/" + id(job) + "/outputs/" + i).statusCode());
        }
        assertEquals(404, get("/api/v1/jobs/" + id(job) + "/outputs/3").statusCode());
        assertEquals(404, get("/api/v1/jobs/" + id(job) + "/outputs/-1").statusCode());
        assertEquals(404, get("/api/v1/jobs/" + id(job) + "/outputs/..%2f..%2fx").statusCode());
    }

    @Test
    void severalImagesBecomeOnePdf() throws Exception {
        String a = id(upload("a.png", Files.readAllBytes(FIXTURES.resolve("alpha.png"))));
        String b = id(upload("b.jpg", Files.readAllBytes(FIXTURES.resolve("quadrants.jpg"))));
        JsonNode job = finish(createJob("""
                {"inputs":["%s","%s"],"target":"pdf"}""".formatted(a, b)));
        assertEquals("2", job.get("result").get("outputs").get(0).get("properties").get("pages").asString());
    }

    @Test
    void engineErrorsReachTheClientWithStableCodes() throws Exception {
        HttpResponse<String> bad = uploadRaw("x.png", "not an image".getBytes(StandardCharsets.UTF_8));
        assertEquals(422, bad.statusCode());
        assertEquals("UNRECOGNIZED_FORMAT", errorCode(bad));

        String png = id(upload("a.png", Files.readAllBytes(FIXTURES.resolve("alpha.png"))));
        JsonNode job = finish(createJob("""
                {"inputs":["%s"],"target":"jpeg","options":{"quality":"900"}}""".formatted(png)));
        assertEquals("FAILED", job.get("state").asString());
        assertEquals("INVALID_OPTION", job.get("error").get("code").asString());
        assertEquals("UNKNOWN_FORMAT", errorCode(post("/api/v1/jobs", """
                {"inputs":["%s"],"target":"banana"}""".formatted(png))));
        assertEquals(404, post("/api/v1/jobs", """
                {"inputs":["nope"],"target":"png"}""").statusCode());
    }

    @Test
    void capabilitiesDescribeTheMatrix() throws Exception {
        JsonNode view = JSON.readTree(get("/api/v1/capabilities").body());
        assertEquals(8, view.get("operations").size());
        JsonNode webp = null;
        for (JsonNode operation : view.get("operations")) {
            if (operation.get("id").asString().equals("png-to-webp")) {
                webp = operation;
            }
        }
        assertTrue(webp.get("available").asBoolean());
        assertEquals("quality", webp.get("options").get(0).get("key").asString());
        assertEquals("integer", webp.get("options").get(0).get("type").asString());
        assertFalse(webp.get("losses").isEmpty());
    }

    // ---- privacy of temporary data ----

    @Test
    void deletedAndClosedSessionsLeaveNothingOnDisk() throws Exception {
        byte[] source = Files.readAllBytes(FIXTURES.resolve("alpha.png"));
        JsonNode file = upload("a.png", source);
        JsonNode job = finish(createJob("""
                {"inputs":["%s"],"target":"jpeg"}""".formatted(id(file))));
        assertEquals(2, files().size(), "one upload and one result: " + files());
        assertTrue(files().stream().noneMatch(n -> n.equals("a.png")), "uploads never keep their names on disk");

        assertEquals(204, delete("/api/v1/files/" + id(file)).statusCode());
        assertEquals(204, delete("/api/v1/jobs/" + id(job)).statusCode());
        assertEquals(List.of(), files());

        upload("b.png", source);
        server.close();
        runtime.close();
        assertEquals(List.of(), files());
    }

    @Test
    void responsesNeverContainServerPaths() throws Exception {
        JsonNode file = upload("a.png", Files.readAllBytes(FIXTURES.resolve("alpha.png")));
        JsonNode job = finish(createJob("""
                {"inputs":["%s"],"target":"jpeg"}""".formatted(id(file))));
        String everything = file.toString() + job + new String(get("/api/v1/info").body(), StandardCharsets.UTF_8)
                + new String(get("/api/v1/capabilities").body(), StandardCharsets.UTF_8);
        assertFalse(everything.contains(temp.getFileName().toString()));
        assertFalse(everything.contains("morph-ws-"));
    }

    @ParameterizedTest
    @CsvSource({
            "'../../../etc/passwd', 'passwd.jpg'",
            "'C:\\Windows\\evil.png', 'evil.jpg'",
            "'nul.png', '_nul.jpg'",
            "'a<b>.png', 'a_b_.jpg'",
    })
    void hostileFileNamesAreNeutralised(String name, String expected) throws Exception {
        JsonNode file = upload(name, Files.readAllBytes(FIXTURES.resolve("alpha.png")));
        JsonNode job = finish(createJob("""
                {"inputs":["%s"],"target":"jpeg"}""".formatted(id(file))));
        assertEquals(expected, job.get("result").get("outputs").get(0).get("name").asString());
    }

    // ---- access control ----

    @Test
    void listensOnLoopbackOnly() throws Exception {
        for (InetAddress address : nonLoopbackAddresses()) {
            try (Socket socket = new Socket()) {
                assertThrows(IOException.class,
                        () -> socket.connect(new InetSocketAddress(address, server.port()), 800),
                        "reachable through " + address);
            }
        }
        assertEquals("127.0.0.1", server.baseUrl().getHost());
    }

    @ParameterizedTest
    @ValueSource(strings = {"evil.example", "evil.example:80", "127.0.0.1", "127.0.0.1.evil.example", "[::1]",
            "0.0.0.0", "localhost"})
    void unknownHostsAreRefused(String host) throws Exception {
        for (String path : List.of("/", "/index.html", "/api/v1/info", "/api/v1/session")) {
            String response = raw("GET " + path + " HTTP/1.1\r\nHost: " + host
                    + (host.contains(":") || host.equals("127.0.0.1") || host.equals("localhost") ? "" : ":" + server.port())
                    + "\r\nAuthorization: Bearer " + token + "\r\nConnection: close\r\n\r\n");
            assertTrue(response.startsWith("HTTP/1.1 403"), host + path + " -> " + response.lines().findFirst());
        }
    }

    @Test
    void requestsWithoutAHostAreRefused() throws Exception {
        assertTrue(raw("GET /api/v1/info HTTP/1.1\r\nConnection: close\r\n\r\n").startsWith("HTTP/1.1 400"));
        assertTrue(raw("GET /api/v1/info HTTP/1.0\r\nAuthorization: Bearer " + token + "\r\n\r\n")
                .matches("(?s)HTTP/1.[01] (400|403).*"));
    }

    @ParameterizedTest
    @CsvSource({"Origin, https://evil.example", "Origin, null", "Origin, http://127.0.0.1:1",
            "Origin, https://127.0.0.1", "Sec-Fetch-Site, cross-site", "Sec-Fetch-Site, same-site"})
    void crossSiteRequestsAreRefusedEvenWithAValidToken(String header, String value) throws Exception {
        for (String path : List.of("/api/v1/info", "/")) {
            String response = raw("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + server.port() + "\r\n" + header
                    + ": " + value + "\r\nAuthorization: Bearer " + token + "\r\nConnection: close\r\n\r\n");
            assertTrue(response.startsWith("HTTP/1.1 403"), response);
            assertFalse(response.toLowerCase().contains("access-control-allow"));
        }
    }

    @Test
    void preflightRequestsNeverSucceed() throws Exception {
        String response = raw("OPTIONS /api/v1/files HTTP/1.1\r\nHost: 127.0.0.1:" + server.port()
                + "\r\nOrigin: https://evil.example\r\nAccess-Control-Request-Method: POST\r\n"
                + "Access-Control-Request-Headers: authorization\r\nConnection: close\r\n\r\n");
        assertTrue(response.startsWith("HTTP/1.1 403"), response);
        assertFalse(response.toLowerCase().contains("access-control-allow"));
    }

    @Test
    void everyEndpointRequiresTheToken() throws Exception {
        for (String bad : List.of("", "Bearer ", "Bearer wrong", "Basic " + token, token)) {
            for (String[] call : new String[][] {{"GET", "/info"}, {"GET", "/capabilities"}, {"POST", "/files"},
                    {"DELETE", "/files/x"}, {"POST", "/jobs"}, {"GET", "/jobs/x"}, {"DELETE", "/jobs/x"},
                    {"POST", "/jobs/x/cancel"}, {"GET", "/jobs/x/outputs/0"}, {"POST", "/shutdown"}}) {
                HttpRequest.Builder request = HttpRequest.newBuilder(uri("/api/v1" + call[1]))
                        .method(call[0], BodyPublishers.noBody());
                if (!bad.isEmpty()) {
                    request.header("Authorization", bad);
                }
                assertEquals(401, http.send(request.build(), BodyHandlers.ofString()).statusCode(),
                        call[0] + call[1]);
            }
        }
    }

    @Test
    void launchCodesWorkOnceAndAreReplaced() throws Exception {
        String used = freshLinks.isEmpty() ? null : code(freshLinks.getFirst());
        assertEquals(1, freshLinks.size(), "redeeming the first code announces the next link");
        assertEquals(used, code(server.launchUrl()));
        assertNotEquals(null, redeem(used).get("token"));
        assertNull(redeem(used).get("token"), "a code cannot be used twice");
        assertNull(redeem("").get("token"));
        assertNotEquals(used, code(server.launchUrl()));
    }

    @Test
    void bodiesMustUseNonSimpleContentTypes() throws Exception {
        for (String type : List.of("text/plain", "multipart/form-data; boundary=x",
                "application/x-www-form-urlencoded")) {
            HttpRequest request = authorized("/api/v1/files").header("Content-Type", type)
                    .header("X-Morph-Filename", "a.png").POST(BodyPublishers.ofString("x")).build();
            assertEquals(415, http.send(request, BodyHandlers.ofString()).statusCode());
            HttpRequest job = authorized("/api/v1/jobs").header("Content-Type", type)
                    .POST(BodyPublishers.ofString("{}")).build();
            assertEquals(415, http.send(job, BodyHandlers.ofString()).statusCode());
        }
    }

    @Test
    void securityHeadersAreAlwaysPresent() throws Exception {
        for (String path : List.of("/", "/api/v1/info", "/nope")) {
            var headers = get(path).headers();
            String csp = headers.firstValue("Content-Security-Policy").orElseThrow();
            assertTrue(csp.contains("connect-src 'self'") && csp.contains("default-src 'none'"), csp);
            assertEquals("nosniff", headers.firstValue("X-Content-Type-Options").orElseThrow());
            assertEquals("no-store", headers.firstValue("Cache-Control").orElseThrow());
            assertTrue(headers.firstValue("Set-Cookie").isEmpty());
            assertTrue(headers.firstValue("Server").isEmpty());
            assertTrue(headers.firstValue("Access-Control-Allow-Origin").isEmpty());
        }
    }

    // ---- limits ----

    @Test
    void oversizedUploadsAreCutOffAndErased() throws Exception {
        byte[] big = new byte[(1 << 20) + 1];
        assertEquals(413, uploadRaw("big.png", big).statusCode());
        // Same without announcing the size.
        HttpRequest chunked = authorized("/api/v1/files").header("Content-Type", "application/octet-stream")
                .header("X-Morph-Filename", "big.png")
                .POST(BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(big))).build();
        assertEquals(413, http.send(chunked, BodyHandlers.ofString()).statusCode());
        assertEquals(List.of(), files());
    }

    @Test
    void theTemporaryAreaHasACeilingThatIsReleasedOnDeletion() throws Exception {
        byte[] padded = new byte[900_000];
        byte[] png = Files.readAllBytes(FIXTURES.resolve("alpha.png"));
        System.arraycopy(png, 0, padded, 0, png.length);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(id(upload("a.png", padded)));
        }
        assertEquals(413, uploadRaw("a.png", padded).statusCode());
        delete("/api/v1/files/" + ids.getFirst());
        assertEquals(201, uploadRaw("a.png", padded).statusCode());
    }

    @Test
    void malformedJsonIsRefused() throws Exception {
        for (String body : List.of("", "{", "[]", "null", "{\"inputs\":[]}", "{\"inputs\":[\"a\"]}",
                "{\"a\":\"" + "x".repeat(70_000) + "\"}")) {
            int status = post("/api/v1/jobs", body).statusCode();
            assertTrue(status == 400 || status == 413, body.length() + " -> " + status);
        }
    }

    // ---- life cycle ----

    @Test
    void shutdownEndsTheServer() throws Exception {
        assertEquals(202, post("/api/v1/shutdown", "").statusCode());
        server.awaitStop();
        server.close();
        assertThrows(ConnectException.class, () -> get("/api/v1/info"));
    }

    @Test
    void aBusyPortIsReported() {
        assertThrows(IOException.class, () -> LocalServer.start(runtime,
                LocalServer.Options.defaults().withPort(server.port()), u -> { }));
    }

    @Test
    void jobsCanBeCancelled() throws Exception {
        JsonNode file = upload("doc.pdf", Files.readAllBytes(FIXTURES.resolve("document-3-pages.pdf")));
        JsonNode job = createJob("""
                {"inputs":["%s"],"target":"png","options":{"dpi":"600"}}""".formatted(id(file)));
        post("/api/v1/jobs/" + id(job) + "/cancel", "");
        assertEquals("CANCELLED", finish(job).get("state").asString());
    }

    // ---- helpers ----

    private URI uri(String path) {
        return server.baseUrl().resolve(path);
    }

    private HttpRequest.Builder authorized(String path) {
        return HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token);
    }

    private HttpResponse<byte[]> get(String path) throws Exception {
        return http.send(authorized(path).build(), BodyHandlers.ofByteArray());
    }

    private HttpResponse<String> delete(String path) throws Exception {
        return http.send(authorized(path).DELETE().build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        return http.send(authorized(path).header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(json)).build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> uploadRaw(String name, byte[] content) throws Exception {
        return http.send(authorized("/api/v1/files").header("Content-Type", "application/octet-stream")
                .header("X-Morph-Filename", java.net.URLEncoder.encode(name, StandardCharsets.UTF_8)
                        .replace("+", "%20"))
                .POST(BodyPublishers.ofByteArray(content)).build(), BodyHandlers.ofString());
    }

    private JsonNode upload(String name, byte[] content) throws Exception {
        HttpResponse<String> response = uploadRaw(name, content);
        assertEquals(201, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private JsonNode createJob(String body) throws Exception {
        HttpResponse<String> response = post("/api/v1/jobs", body);
        assertEquals(202, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private JsonNode finish(JsonNode job) throws Exception {
        while (true) {
            JsonNode current = JSON.readTree(get("/api/v1/jobs/" + id(job)).body());
            if (!List.of("QUEUED", "RUNNING").contains(current.get("state").asString())) {
                return current;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
    }

    private JsonNode redeem(String code) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/session"))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(JSON.writeValueAsString(java.util.Map.of("code", code)))).build();
        return JSON.readTree(http.send(request, BodyHandlers.ofString()).body());
    }

    private String raw(String request) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(response);
            return response.toString(StandardCharsets.ISO_8859_1);
        }
    }

    /** Everything stored in the private area, minus the lock. */
    private List<String> files() throws IOException {
        try (Stream<Path> walk = Files.walk(temp.resolve("work"))) {
            return walk.filter(Files::isRegularFile).map(p -> p.getFileName().toString())
                    .filter(n -> !n.equals("owner.lock")).toList();
        }
    }

    private static List<InetAddress> nonLoopbackAddresses() throws IOException {
        List<InetAddress> addresses = new ArrayList<>();
        for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (network.isUp()) {
                Collections.list(network.getInetAddresses()).stream()
                        .filter(a -> !a.isLoopbackAddress() && a instanceof Inet4Address).forEach(addresses::add);
            }
        }
        return addresses;
    }

    private static String code(URI launch) {
        return launch.getFragment().substring("code=".length());
    }

    private static String id(JsonNode node) {
        return node.get("id").asString();
    }

    private static String errorCode(HttpResponse<String> response) {
        return JSON.readTree(response.body()).get("error").get("code").asString();
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(n -> values.add(n.asString()));
        return values;
    }

    @Test
    void theSameBytesComeOutAsFromTheEngineItself() throws Exception {
        byte[] source = Files.readAllBytes(FIXTURES.resolve("screenshot.png"));
        JsonNode job = finish(createJob("""
                {"inputs":["%s"],"target":"jpeg"}""".formatted(id(upload("s.png", source)))));
        byte[] viaHttp = get("/api/v1/jobs/" + id(job) + "/outputs/0").body();
        var direct = runtime.engine().convert(new ConversionRequest(
                List.of(ConversionRequest.InputFile.of(FIXTURES.resolve("screenshot.png"))),
                FormatId.of("jpeg"), ConversionOptions.NONE,
                new ConversionRequest.OutputSpec(temp.resolve("direct"), "s", null,
                        ConversionRequest.ConflictPolicy.FAIL),
                runtime.engine().config().limits()));
        assertArrayEquals(Files.readAllBytes(direct.outputs().getFirst().path()), viaHttp);
    }
}
