package dev.morph.cli;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Starts the real {@code morph web} command and talks to it the way the browser does. */
final class WebSession implements AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern LINK = Pattern.compile("(http://127\\.0\\.0\\.1:\\d+/)#code=(\\S+)");

    private final HttpClient http = HttpClient.newHttpClient();
    private final CompletableFuture<Integer> exit;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final URI base;
    private final String token;

    WebSession() throws Exception {
        exit = CompletableFuture.supplyAsync(() -> Main.run(
                new Terminal(out, out, StandardCharsets.UTF_8, false), "web", "--no-browser"),
                task -> Thread.ofPlatform().daemon().start(task));
        Matcher link = LINK.matcher("");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!link.reset(output()).find()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("morph web did not start: " + output());
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        base = URI.create(link.group(1));
        token = json(http.send(HttpRequest.newBuilder(base.resolve("/api/v1/session"))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(JSON.writeValueAsString(Map.of("code", link.group(2))))).build(),
                BodyHandlers.ofString())).get("token").asString();
    }

    String output() {
        return out.toString(StandardCharsets.UTF_8);
    }

    /** Uploads, converts and downloads every result. */
    List<byte[]> convert(String name, byte[] content, String target, Map<String, String> options) throws Exception {
        JsonNode file = json(http.send(request("/api/v1/files").header("Content-Type", "application/octet-stream")
                .header("X-Morph-Filename", URLEncoder.encode(name, StandardCharsets.UTF_8))
                .POST(BodyPublishers.ofByteArray(content)).build(), BodyHandlers.ofString()));
        JsonNode job = json(http.send(request("/api/v1/jobs").header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(JSON.writeValueAsString(Map.of("inputs",
                        List.of(file.get("id").asString()), "target", target, "options", options)))).build(),
                BodyHandlers.ofString()));
        String id = job.get("id").asString();
        while (List.of("QUEUED", "RUNNING").contains(job.get("state").asString())) {
            TimeUnit.MILLISECONDS.sleep(10);
            job = json(http.send(request("/api/v1/jobs/" + id).build(), BodyHandlers.ofString()));
        }
        if (!job.get("state").asString().equals("SUCCEEDED")) {
            throw new AssertionError(job.toString());
        }
        List<byte[]> outputs = new ArrayList<>();
        for (int i = 0; i < job.get("result").get("outputs").size(); i++) {
            outputs.add(http.send(request("/api/v1/jobs/" + id + "/outputs/" + i).build(),
                    BodyHandlers.ofByteArray()).body());
        }
        return outputs;
    }

    /** Asks the service to stop and returns the exit code of the command. */
    int shutdown() throws Exception {
        http.send(request("/api/v1/shutdown").POST(BodyPublishers.noBody()).build(), BodyHandlers.discarding());
        return exit.get(20, TimeUnit.SECONDS);
    }

    @Override
    public void close() throws Exception {
        if (!exit.isDone()) {
            shutdown();
        }
        http.close();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(base.resolve(path)).header("Authorization", "Bearer " + token);
    }

    private static JsonNode json(HttpResponse<String> response) {
        if (response.statusCode() >= 300) {
            throw new AssertionError(response.statusCode() + " " + response.body());
        }
        return JSON.readTree(response.body());
    }
}
