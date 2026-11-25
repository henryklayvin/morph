package io.github.henryklayvin.morph.local;

import io.github.henryklayvin.morph.application.MorphRuntime;
import io.github.henryklayvin.morph.contracts.ConversionOptions;
import io.github.henryklayvin.morph.contracts.ConversionRequest;
import io.github.henryklayvin.morph.contracts.ConversionRequest.ConflictPolicy;
import io.github.henryklayvin.morph.contracts.ConversionRequest.InputFile;
import io.github.henryklayvin.morph.contracts.ConversionRequest.OutputSpec;
import io.github.henryklayvin.morph.contracts.ConversionResult.OutputFile;
import io.github.henryklayvin.morph.contracts.FormatDescriptor;
import io.github.henryklayvin.morph.contracts.MorphException;
import io.github.henryklayvin.morph.engine.ConversionJob;
import io.github.henryklayvin.morph.engine.FileNames;
import io.github.henryklayvin.morph.engine.FormatInspector;
import io.github.henryklayvin.morph.engine.MorphEngine;
import io.github.henryklayvin.morph.local.ApiViews.CapabilitiesView;
import io.github.henryklayvin.morph.local.ApiViews.ErrorView;
import io.github.henryklayvin.morph.local.ApiViews.FileView;
import io.github.henryklayvin.morph.local.ApiViews.JobView;
import io.javalin.Javalin;
import io.javalin.compression.CompressionStrategy;
import io.javalin.config.JavalinConfig;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import io.javalin.http.staticfiles.Location;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Morph Local: the Web UI in front of the very same engine the CLI uses. This is a bridge between two programs on
 * one computer, not a server in the usual sense, and it is never reachable from the network.
 */
public final class LocalServer implements AutoCloseable {

    /** Literal address, not a name: "localhost" depends on the hosts file and may resolve elsewhere. */
    static final String LOOPBACK = "127.0.0.1";
    private static final String UI_ROOT = "/morph-web";
    private static final String API = "/api/v1";
    private static final int MAX_JSON_BYTES = 64 * 1024;

    /**
     * @param port       0 picks a free port
     * @param idleLimit  files and results not touched for this long are erased
     */
    public record Options(int port, long maxUploadBytes, long maxStoredBytes, Duration idleLimit) {
        public static Options defaults() {
            return new Options(0, 512L << 20, 4L << 30, Duration.ofHours(1));
        }

        public Options withPort(int newPort) {
            return new Options(newPort, maxUploadBytes, maxStoredBytes, idleLimit);
        }
    }

    private record SessionRequest(String code) {
    }

    private record JobRequest(List<String> inputs, String target, Map<String, String> options) {
    }

    private final JsonMapper json = JsonMapper.builder().build();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final ScheduledExecutorService housekeeping;
    private final MorphEngine engine;
    private final Options options;
    private final RequestGuard guard;
    private final UploadStore uploads;
    private final JobStore jobs = new JobStore();
    private final Javalin app;
    private volatile int port;

    private LocalServer(MorphRuntime runtime, Options options, Consumer<URI> onNewLaunchUrl) throws IOException {
        this.engine = runtime.engine();
        this.options = options;
        this.guard = new RequestGuard(code -> onNewLaunchUrl.accept(launchUrl()));
        this.uploads = new UploadStore(engine.createTemporaryDirectory("uploads"), options.maxUploadBytes(),
                options.maxStoredBytes());
        this.app = Javalin.create(this::configure);
        this.housekeeping = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "morph-local-housekeeping");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** @param onNewLaunchUrl told whenever a launch link was used and a fresh one became valid */
    public static LocalServer start(MorphRuntime runtime, Options options, Consumer<URI> onNewLaunchUrl)
            throws IOException {
        LocalServer server = new LocalServer(runtime, options, onNewLaunchUrl);
        try {
            server.app.start(LOOPBACK, options.port());
        } catch (RuntimeException e) {
            server.close();
            throw new IOException("Port unavailable", e);
        }
        server.port = server.app.port();
        server.guard.bind(server.port);
        server.housekeeping.scheduleWithFixedDelay(() -> {
            server.uploads.removeIdle(options.idleLimit());
            server.jobs.removeIdle(options.idleLimit());
        }, 1, 1, TimeUnit.MINUTES);
        return server;
    }

    public int port() {
        return port;
    }

    public URI baseUrl() {
        return URI.create("http://" + LOOPBACK + ":" + port() + "/");
    }

    /** Link that opens an authenticated session once. The code travels in the fragment, so it is never sent. */
    public URI launchUrl() {
        return URI.create(baseUrl() + "#code=" + guard.launchCode());
    }

    /** Whether the Web UI was bundled in this build. */
    public static boolean hasUserInterface() {
        return LocalServer.class.getResource(UI_ROOT + "/index.html") != null;
    }

    public void awaitStop() throws InterruptedException {
        stopped.await();
    }

    @Override
    public void close() {
        housekeeping.shutdownNow();
        app.stop();
        stopped.countDown();
    }

    private void configure(JavalinConfig config) {
        config.startup.showJavalinBanner = false;
        config.startup.showOldJavalinVersionWarning = false;
        config.startup.startupWatcherEnabled = false;
        config.http.compressionStrategy = CompressionStrategy.NONE;
        config.http.maxRequestSize = MAX_JSON_BYTES;
        config.jetty.threadPool = new QueuedThreadPool(32, 4);
        config.jetty.modifyHttpConfiguration(http -> {
            http.setSendServerVersion(false);
            http.setSendDateHeader(false);
        });
        if (hasUserInterface()) {
            config.staticFiles.add(files -> {
                files.hostedPath = "/";
                files.directory = UI_ROOT;
                files.location = Location.CLASSPATH;
                files.headers = Map.of("Cache-Control", "no-store");
            });
        }
        routes(config.routes);
    }

    private void routes(RoutesConfig routes) {
        routes.before(guard::checkTransport);
        routes.before(API + "/*", ctx -> {
            if (!ctx.path().equals(API + "/session")) {
                guard.checkToken(ctx);
            }
        });

        if (!hasUserInterface()) {
            routes.get("/", ctx -> ctx.contentType("text/plain; charset=utf-8")
                    .result("A interface Web não foi incluída nesta compilação do Morph."));
        }
        routes.post(API + "/session", ctx -> write(ctx, 200,
                Map.of("token", guard.redeem(read(ctx, SessionRequest.class).code()))));
        routes.get(API + "/info", ctx -> write(ctx, 200, ApiViews.info(options.maxUploadBytes())));
        routes.get(API + "/capabilities", ctx -> write(ctx, 200, CapabilitiesView.of(engine)));
        routes.post(API + "/files", this::receiveFile);
        routes.delete(API + "/files/{id}", ctx -> {
            uploads.remove(ctx.pathParam("id"));
            ctx.status(204);
        });
        routes.post(API + "/jobs", this::createJob);
        routes.get(API + "/jobs/{id}", ctx -> write(ctx, 200, JobView.of(jobs.require(ctx.pathParam("id")).job())));
        routes.post(API + "/jobs/{id}/cancel", ctx -> {
            ConversionJob job = jobs.require(ctx.pathParam("id")).job();
            job.cancel();
            write(ctx, 200, JobView.of(job));
        });
        routes.delete(API + "/jobs/{id}", ctx -> {
            jobs.remove(ctx.pathParam("id"));
            ctx.status(204);
        });
        routes.get(API + "/jobs/{id}/outputs/{index}", this::sendOutput);
        routes.post(API + "/shutdown", ctx -> {
            ctx.status(202);
            // Lets this response leave before the server goes away.
            housekeeping.schedule(() -> stopped.countDown(), 200, TimeUnit.MILLISECONDS);
        });

        routes.exception(ApiException.class, (e, ctx) -> fail(ctx, e));
        routes.exception(MorphException.class, (e, ctx) -> fail(ctx, ApiException.of(e)));
        routes.exception(HttpResponseException.class, (e, ctx) -> fail(ctx,
                new ApiException(e.getStatus(), "HTTP_" + e.getStatus(), "Requisição não aceita.")));
        // No stack traces, no exception text: either could reveal paths.
        routes.exception(Exception.class, (e, ctx) -> fail(ctx,
                new ApiException(500, "INTERNAL_ERROR", "Erro interno.")));
    }

    private void receiveFile(Context ctx) throws IOException, MorphException {
        // Not a "simple" content type: even if every other check failed, a foreign page would need a CORS preflight.
        if (!"application/octet-stream".equals(ctx.contentType())) {
            throw new ApiException(415, "UNSUPPORTED_MEDIA_TYPE", "Envie o arquivo como application/octet-stream.");
        }
        if (ctx.req().getContentLengthLong() > options.maxUploadBytes()) {
            throw new ApiException(413, "RESOURCE_LIMIT_EXCEEDED",
                    "O arquivo excede o limite de %d MiB.".formatted(options.maxUploadBytes() >> 20));
        }
        UploadStore.Upload upload = uploads.receive(fileName(ctx), ctx.bodyInputStream());
        try {
            FormatInspector.Inspection inspection = engine.inspect(upload.asInput());
            upload.format(inspection.source().format());
            write(ctx, 201, FileView.of(upload, inspection.warning().orElse(null), engine));
        } catch (MorphException e) {
            uploads.remove(upload.id());
            throw e;
        }
    }

    private void createJob(Context ctx) throws IOException, MorphException {
        JobRequest body = read(ctx, JobRequest.class);
        if (body.inputs() == null || body.inputs().isEmpty() || body.target() == null) {
            throw ApiException.badRequest("Informe os arquivos e o formato de destino.");
        }
        List<InputFile> inputs = body.inputs().stream().map(id -> uploads.require(id).asInput()).toList();
        FormatDescriptor target = engine.formats().resolve(body.target());
        Path directory = engine.createTemporaryDirectory("results");
        String base = FileNames.sanitize(FileNames.base(inputs.getFirst().displayName()));
        ConversionRequest request = new ConversionRequest(inputs, target.id(),
                new ConversionOptions(body.options() == null ? Map.of() : body.options()),
                new OutputSpec(directory, base, null, ConflictPolicy.RENAME), engine.config().limits());
        ConversionJob job = engine.submit(request);
        jobs.add(job, directory);
        write(ctx, 202, JobView.of(job));
    }

    private void sendOutput(Context ctx) throws IOException {
        ConversionJob job = jobs.require(ctx.pathParam("id")).job();
        List<OutputFile> outputs = job.result().orElseThrow(ApiException::notFound).outputs();
        int index;
        try {
            index = Integer.parseInt(ctx.pathParam("index"));
        } catch (NumberFormatException e) {
            throw ApiException.notFound();
        }
        if (index < 0 || index >= outputs.size()) {
            throw ApiException.notFound();
        }
        OutputFile output = outputs.get(index);
        String name = URLEncoder.encode(output.path().getFileName().toString(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        InputStream content = Files.newInputStream(output.path());
        // Always a download of opaque bytes: a converted file must never be interpreted inside the app's origin.
        ctx.contentType("application/octet-stream")
                .header("Content-Disposition", "attachment; filename*=UTF-8''" + name)
                .header("Content-Length", Long.toString(output.size()))
                .result(content);
    }

    private static String fileName(Context ctx) {
        String raw = ctx.header("X-Morph-Filename");
        if (raw == null || raw.length() > 2048) {
            throw ApiException.badRequest("Nome de arquivo ausente ou longo demais.");
        }
        try {
            String decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8);
            String base = FileNames.sanitize(FileNames.base(decoded));
            String extension = FileNames.extension(decoded);
            return extension.isEmpty() ? base : base + "." + FileNames.sanitize(extension);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Nome de arquivo inválido.");
        }
    }

    private <T> T read(Context ctx, Class<T> type) {
        if (!"application/json".equals(ctx.contentType())) {
            throw new ApiException(415, "UNSUPPORTED_MEDIA_TYPE", "Envie application/json.");
        }
        try {
            byte[] body = ctx.bodyInputStream().readNBytes(MAX_JSON_BYTES + 1);
            if (body.length > MAX_JSON_BYTES) {
                throw new ApiException(413, "RESOURCE_LIMIT_EXCEEDED", "Requisição grande demais.");
            }
            T value = json.readValue(body, type);
            if (value == null) {
                throw ApiException.badRequest("JSON inválido.");
            }
            return value;
        } catch (IOException | JacksonException e) {
            throw ApiException.badRequest("JSON inválido.");
        }
    }

    private void write(Context ctx, int status, Object body) {
        ctx.status(status).contentType("application/json; charset=utf-8").result(json.writeValueAsBytes(body));
    }

    private void fail(Context ctx, ApiException e) {
        write(ctx, e.status(), Map.of("error", new ErrorView(e.code(), e.getMessage())));
    }
}
