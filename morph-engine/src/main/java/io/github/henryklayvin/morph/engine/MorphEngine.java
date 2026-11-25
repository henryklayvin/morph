package io.github.henryklayvin.morph.engine;

import io.github.henryklayvin.morph.contracts.ConversionProvider;
import io.github.henryklayvin.morph.contracts.ConversionRequest;
import io.github.henryklayvin.morph.contracts.ConversionRequest.InputFile;
import io.github.henryklayvin.morph.contracts.ConversionResult;
import io.github.henryklayvin.morph.contracts.ErrorCode;
import io.github.henryklayvin.morph.contracts.MorphException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Entry point of the conversion core. Knows nothing about terminals, HTTP or browsers; the CLI and Morph Local are
 * thin clients of this class.
 */
public final class MorphEngine implements AutoCloseable {

    private final EngineConfig config;
    private final ProviderRegistry registry;
    private final Workspaces workspaces;
    private final ConversionPipeline pipeline;
    private final ExecutorService workers;
    private final ScheduledExecutorService watchdog;
    private final Set<ConversionJob> active = ConcurrentHashMap.newKeySet();

    public MorphEngine(EngineConfig config, List<ConversionProvider> providers) throws IOException {
        this.config = config;
        this.registry = new ProviderRegistry(providers);
        this.workspaces = new Workspaces(config.temporaryRoot());
        this.pipeline = new ConversionPipeline(registry, workspaces);
        this.workers = Executors.newFixedThreadPool(config.workers(), daemon("morph-worker"));
        this.watchdog = Executors.newSingleThreadScheduledExecutor(daemon("morph-watchdog"));
    }

    public EngineConfig config() {
        return config;
    }

    public ProviderRegistry registry() {
        return registry;
    }

    public FormatRegistry formats() {
        return registry.formats();
    }

    public FormatInspector.Inspection inspect(InputFile input) throws MorphException {
        return pipeline.inspector().inspect(input, config.limits());
    }

    /** Private temporary directory for a front end (uploads, downloads); erased when the engine closes. */
    public Path createTemporaryDirectory(String kind) throws IOException {
        return workspaces.create(kind);
    }

    public ConversionJob submit(ConversionRequest request) throws MorphException {
        return submit(request, JobListener.NONE);
    }

    public ConversionJob submit(ConversionRequest request, JobListener listener) throws MorphException {
        ConversionJob job = new ConversionJob(listener);
        active.add(job);
        try {
            workers.execute(() -> execute(request, job));
        } catch (RejectedExecutionException e) {
            active.remove(job);
            throw new MorphException(ErrorCode.CANCELLED, "O Morph está sendo encerrado.", e);
        }
        return job;
    }

    /** Convenience for callers that have nothing better to do than wait. */
    public ConversionResult convert(ConversionRequest request) throws MorphException, InterruptedException {
        return submit(request).await();
    }

    @Override
    public void close() {
        active.forEach(ConversionJob::cancel);
        workers.shutdown();
        try {
            if (!workers.awaitTermination(config.cancelGrace().toMillis(), TimeUnit.MILLISECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
        watchdog.shutdownNow();
        workspaces.close();
    }

    private void execute(ConversionRequest request, ConversionJob job) {
        WorkerInterruption interruption = new WorkerInterruption(Thread.currentThread());
        ScheduledFuture<?> deadline = null;
        try {
            job.token().throwIfCancelled();
            job.running();
            deadline = watchdog.schedule(() -> job.token().cancel(ErrorCode.TIMED_OUT),
                    request.limits().timeout().toMillis(), TimeUnit.MILLISECONDS);
            // Unblocks interruptible I/O right away; code that ignores interruption is reported after the grace
            // period, although the JVM offers no way to kill its thread (docs/seguranca.md).
            job.token().onCancel(interruption::trigger);
            job.token().onCancel(() -> watchdog.schedule(() -> job.fail(job.token().toException()),
                    config.cancelGrace().toMillis(), TimeUnit.MILLISECONDS));
            job.succeed(pipeline.run(request, job));
        } catch (MorphException e) {
            // Interrupted code fails in misleading ways ("the file is corrupt"); the cancellation is the real reason.
            job.fail(job.token().isCancelled() ? job.token().toException() : e);
        } catch (OutOfMemoryError e) {
            job.fail(new MorphException(ErrorCode.RESOURCE_LIMIT_EXCEEDED,
                    "Memória insuficiente para converter este arquivo.", e));
        } catch (StackOverflowError e) {
            job.fail(new MorphException(ErrorCode.CONVERSION_FAILED,
                    "A estrutura do arquivo é complexa demais para ser convertida.", e));
        } catch (RuntimeException | LinkageError e) {
            job.fail(new MorphException(ErrorCode.INTERNAL_ERROR, "Erro interno durante a conversão.", e));
        } finally {
            if (deadline != null) {
                deadline.cancel(false);
            }
            active.remove(job);
            interruption.disarm();
        }
    }

    /** Ensures that a late cancellation of one job never interrupts the next job of the same worker. */
    private static final class WorkerInterruption {
        private final Thread worker;
        private boolean armed = true;

        WorkerInterruption(Thread worker) {
            this.worker = worker;
        }

        synchronized void trigger() {
            if (armed) {
                worker.interrupt();
            }
        }

        synchronized void disarm() {
            armed = false;
            Thread.interrupted();
        }
    }

    private static ThreadFactory daemon(String name) {
        AtomicInteger counter = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, name + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
