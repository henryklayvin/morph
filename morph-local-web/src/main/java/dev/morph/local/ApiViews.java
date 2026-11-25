package dev.morph.local;

import dev.morph.application.MorphRuntime;
import dev.morph.contracts.Availability;
import dev.morph.contracts.Capability;
import dev.morph.contracts.ConversionResult;
import dev.morph.contracts.FormatDescriptor;
import dev.morph.contracts.FormatId;
import dev.morph.contracts.Loss;
import dev.morph.contracts.OptionSpec;
import dev.morph.contracts.OptionSpec.BooleanOption;
import dev.morph.contracts.OptionSpec.ChoiceOption;
import dev.morph.contracts.OptionSpec.ColorOption;
import dev.morph.contracts.OptionSpec.IntegerOption;
import dev.morph.engine.ConversionJob;
import dev.morph.engine.MorphEngine;
import dev.morph.engine.ProviderRegistry.Offer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON shapes of the local API, mirrored by {@code web/src/backend/types.ts}. Server paths never appear here.
 */
final class ApiViews {

    private ApiViews() {
    }

    record ErrorView(String code, String message) {
    }

    record InfoView(String name, String version, String javaVersion, long maxUploadBytes) {
    }

    record FormatView(String id, String name, String category, List<String> extensions, String mediaType) {
        static FormatView of(FormatDescriptor f) {
            return new FormatView(f.id().value(), f.displayName(), f.category().name(), f.extensions(),
                    f.mediaType());
        }
    }

    record LossView(String kind, String description) {
        static LossView of(Loss loss) {
            return new LossView(loss.kind().name(), loss.description());
        }
    }

    record OperationView(String id, String source, String target, String provider, boolean available, String reason,
            String cardinality, List<Map<String, Object>> options, List<LossView> losses, List<String> limitations) {
    }

    record CapabilitiesView(List<FormatView> formats, List<OperationView> operations) {
        static CapabilitiesView of(MorphEngine engine) {
            List<OperationView> operations = new ArrayList<>();
            for (Offer offer : engine.registry().offers()) {
                Capability c = offer.capability();
                Availability availability = engine.registry().availability(offer);
                operations.add(new OperationView(c.operationId(), c.source().value(), c.target().value(),
                        offer.provider().descriptor().id(), availability.available(), availability.reason(),
                        c.cardinality().name(), c.options().stream().map(ApiViews::option).toList(),
                        c.losses().stream().map(LossView::of).toList(), c.limitations()));
            }
            return new CapabilitiesView(engine.formats().all().stream().map(FormatView::of).toList(), operations);
        }
    }

    record FileView(String id, String name, long size, String format, String warning, List<String> targets) {
        static FileView of(UploadStore.Upload upload, String warning, MorphEngine engine) {
            return new FileView(upload.id(), upload.name(), upload.size(), upload.format().id().value(), warning,
                    engine.registry().availableTargets(upload.format().id()).stream().map(FormatId::value).toList());
        }
    }

    record OutputView(int index, String name, long size, String format, Map<String, String> properties) {
    }

    record ResultView(String operation, String provider, List<OutputView> outputs, List<String> warnings,
            long elapsedMillis) {
        static ResultView of(ConversionResult result) {
            List<OutputView> outputs = new ArrayList<>();
            for (int i = 0; i < result.outputs().size(); i++) {
                var file = result.outputs().get(i);
                outputs.add(new OutputView(i, file.path().getFileName().toString(), file.size(),
                        file.format().value(), file.properties()));
            }
            return new ResultView(result.operationId(), result.providerId(), outputs, result.warnings(),
                    result.elapsed().toMillis());
        }
    }

    record JobView(String id, String state, String stage, double progress, ResultView result, ErrorView error) {
        static JobView of(ConversionJob job) {
            return new JobView(job.id(), job.state().name(), job.stage().map(Enum::name).orElse(null),
                    job.progress(), job.result().map(ResultView::of).orElse(null),
                    job.failure().map(e -> new ErrorView(e.code().name(), e.getMessage())).orElse(null));
        }
    }

    static InfoView info(long maxUploadBytes) {
        return new InfoView("Morph", MorphRuntime.version(), Runtime.version().toString(), maxUploadBytes);
    }

    private static Map<String, Object> option(OptionSpec<?> spec) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("key", spec.key());
        view.put("description", spec.description());
        view.put("default", spec.defaultValue());
        switch (spec) {
            case IntegerOption o -> {
                view.put("type", "integer");
                view.put("min", o.min());
                view.put("max", o.max());
            }
            case BooleanOption o -> view.put("type", "boolean");
            case ChoiceOption o -> {
                view.put("type", "choice");
                view.put("choices", o.choices());
            }
            case ColorOption o -> view.put("type", "color");
        }
        return view;
    }
}
