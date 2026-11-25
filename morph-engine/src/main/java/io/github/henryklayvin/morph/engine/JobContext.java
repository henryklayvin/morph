package io.github.henryklayvin.morph.engine;

import io.github.henryklayvin.morph.contracts.CancellationToken;
import io.github.henryklayvin.morph.contracts.Capability;
import io.github.henryklayvin.morph.contracts.ConversionContext;
import io.github.henryklayvin.morph.contracts.ConversionPlan;
import io.github.henryklayvin.morph.contracts.FormatDescriptor;
import io.github.henryklayvin.morph.contracts.ResolvedOptions;
import io.github.henryklayvin.morph.contracts.ResourceLimits;
import io.github.henryklayvin.morph.contracts.SourceFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

final class JobContext implements ConversionContext {

    private final ConversionPlan plan;
    private final ConversionJob job;
    private final Path workDirectory;
    private final AtomicInteger outputs = new AtomicInteger();
    private final List<String> warnings = Collections.synchronizedList(new ArrayList<>());

    JobContext(ConversionPlan plan, ConversionJob job, Path workDirectory) {
        this.plan = plan;
        this.job = job;
        this.workDirectory = workDirectory;
    }

    @Override
    public Capability capability() {
        return plan.capability();
    }

    @Override
    public List<SourceFile> inputs() {
        return plan.sources();
    }

    @Override
    public FormatDescriptor target() {
        return plan.target();
    }

    @Override
    public ResolvedOptions options() {
        return plan.options();
    }

    @Override
    public ResourceLimits limits() {
        return plan.request().limits();
    }

    @Override
    public CancellationToken cancellation() {
        return job.token();
    }

    @Override
    public Path workDirectory() {
        return workDirectory;
    }

    @Override
    public Path newOutputFile() {
        return workDirectory.resolve("output-%d.%s".formatted(outputs.incrementAndGet(),
                plan.target().canonicalExtension()));
    }

    @Override
    public void progress(double fraction) {
        job.progress(fraction);
    }

    @Override
    public void warn(String message) {
        warnings.add(message);
    }

    List<String> warnings() {
        synchronized (warnings) {
            return List.copyOf(warnings);
        }
    }
}
