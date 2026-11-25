package io.github.henryklayvin.morph.engine;

import io.github.henryklayvin.morph.contracts.Cardinality;
import io.github.henryklayvin.morph.contracts.ConversionPlan;
import io.github.henryklayvin.morph.contracts.ConversionRequest;
import io.github.henryklayvin.morph.contracts.ConversionRequest.InputFile;
import io.github.henryklayvin.morph.contracts.ConversionResult;
import io.github.henryklayvin.morph.contracts.ConversionResult.OutputFile;
import io.github.henryklayvin.morph.contracts.ConversionStage;
import io.github.henryklayvin.morph.contracts.ErrorCode;
import io.github.henryklayvin.morph.contracts.FormatDescriptor;
import io.github.henryklayvin.morph.contracts.MorphException;
import io.github.henryklayvin.morph.contracts.ProducedOutput;
import io.github.henryklayvin.morph.contracts.SourceFile;
import io.github.henryklayvin.morph.engine.ProviderRegistry.Offer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** The eight stages of a conversion. The only place where their order is defined. */
final class ConversionPipeline {

    private final FormatRegistry formats;
    private final FormatInspector inspector;
    private final ConversionPlanner planner;
    private final Workspaces workspaces;

    ConversionPipeline(ProviderRegistry registry, Workspaces workspaces) {
        this.formats = registry.formats();
        this.inspector = new FormatInspector(formats);
        this.planner = new ConversionPlanner(registry);
        this.workspaces = workspaces;
    }

    FormatInspector inspector() {
        return inspector;
    }

    ConversionResult run(ConversionRequest request, ConversionJob job) throws MorphException {
        long start = System.nanoTime();
        Path workDirectory = null;
        try {
            // 1. File inspection
            job.enter(ConversionStage.INSPECTION);
            FormatDescriptor target = formats.require(request.target());
            List<String> warnings = new ArrayList<>();
            List<SourceFile> sources = inspectAll(request, warnings);
            job.token().throwIfCancelled();

            // 2. Compatibility check
            job.enter(ConversionStage.COMPATIBILITY);
            List<Offer> candidates = planner.compatibleOffers(sources, target);

            // 3. Conversion planning
            job.enter(ConversionStage.PLANNING);
            ConversionPlan plan = planner.plan(request, sources, target, candidates, warnings);

            // 4. Validation
            job.enter(ConversionStage.VALIDATION);
            OutputDelivery.validate(request.output(), target, !plan.capability().cardinality().mayProduceMany());
            job.token().throwIfCancelled();

            // 5. Conversion execution
            job.enter(ConversionStage.EXECUTION);
            workDirectory = workspaces.create("job");
            JobContext context = new JobContext(plan, job, workDirectory);
            List<ProducedOutput> outputs = execute(plan, context);
            job.progress(1.0);

            // 6. Output verification
            job.enter(ConversionStage.VERIFICATION);
            verify(outputs, plan, workDirectory, job);

            // 7. Output delivery; from here on the job can no longer be cancelled
            job.token().commit();
            job.enter(ConversionStage.DELIVERY);
            List<Path> delivered = OutputDelivery.deliver(
                    outputs.stream().map(ProducedOutput::file).toList(), request.output(), target);

            List<String> allWarnings = new ArrayList<>(plan.warnings());
            allWarnings.addAll(context.warnings());
            return new ConversionResult(plan.capability().operationId(), plan.provider().id(),
                    describe(outputs, delivered, target), allWarnings.stream().distinct().toList(),
                    Duration.ofNanos(System.nanoTime() - start));
        } catch (IOException e) {
            throw new MorphException(ErrorCode.INTERNAL_ERROR, "Falha ao preparar a área temporária.", e);
        } finally {
            // 8. Temporary resource cleanup
            job.enter(ConversionStage.CLEANUP);
            if (workDirectory != null) {
                Workspaces.deleteTree(workDirectory);
            }
        }
    }

    private List<SourceFile> inspectAll(ConversionRequest request, List<String> warnings) throws MorphException {
        List<SourceFile> sources = new ArrayList<>();
        for (InputFile input : request.inputs()) {
            FormatInspector.Inspection inspection = inspector.inspect(input, request.limits());
            sources.add(inspection.source());
            inspection.warning().ifPresent(warnings::add);
        }
        return sources;
    }

    private List<ProducedOutput> execute(ConversionPlan plan, JobContext context) throws MorphException {
        try {
            List<ProducedOutput> outputs = planner.providerOf(plan).convert(context);
            context.cancellation().throwIfCancelled();
            return outputs;
        } catch (MorphException e) {
            throw e;
        } catch (Exception e) {
            throw new MorphException(ErrorCode.CONVERSION_FAILED,
                    "O arquivo não pôde ser convertido. Ele pode estar corrompido ou usar um recurso não suportado.",
                    e);
        }
    }

    private void verify(List<ProducedOutput> outputs, ConversionPlan plan, Path workDirectory, ConversionJob job)
            throws MorphException {
        Cardinality cardinality = plan.capability().cardinality();
        if (outputs.isEmpty() || (outputs.size() > 1 && !cardinality.mayProduceMany())) {
            throw rejected("quantidade inesperada de arquivos", null);
        }
        long total = 0;
        for (ProducedOutput output : outputs) {
            job.token().throwIfCancelled();
            Path file = output.file();
            try {
                if (!file.normalize().startsWith(workDirectory)
                        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw rejected("arquivo fora da área temporária", null);
                }
                long size = Files.size(file);
                total += size;
                if (size == 0) {
                    throw rejected("arquivo vazio", null);
                }
                if (total > plan.request().limits().maxOutputBytes()) {
                    throw new MorphException(ErrorCode.RESOURCE_LIMIT_EXCEEDED,
                            "O resultado excede o tamanho máximo permitido.");
                }
                if (!inspector.contentMatches(file, plan.target())) {
                    throw rejected("conteúdo não corresponde a " + plan.target().displayName(), null);
                }
                output.check().verify(file);
            } catch (MorphException e) {
                if (e.code().category() == ErrorCode.Category.INPUT) {
                    // A check reuses the input decoders; here their complaints are about our own output.
                    throw rejected("o resultado não pôde ser relido", e);
                }
                throw e;
            } catch (Exception e) {
                throw rejected("o resultado não pôde ser relido", e);
            }
        }
    }

    private static List<OutputFile> describe(List<ProducedOutput> outputs, List<Path> delivered,
            FormatDescriptor target) {
        List<OutputFile> files = new ArrayList<>();
        for (int i = 0; i < outputs.size(); i++) {
            Path path = delivered.get(i);
            files.add(new OutputFile(path, target.id(), path.toFile().length(), outputs.get(i).properties()));
        }
        return files;
    }

    private static MorphException rejected(String why, Throwable cause) {
        return new MorphException(ErrorCode.OUTPUT_VERIFICATION_FAILED,
                "O resultado foi descartado porque não passou na verificação: %s.".formatted(why), cause);
    }
}
