package dev.morph.contracts;

import java.util.List;
import java.util.Objects;

/** The decision taken by the planner: which provider will run which operation with which settings. */
public record ConversionPlan(
        ConversionRequest request,
        List<SourceFile> sources,
        FormatDescriptor target,
        ProviderDescriptor provider,
        Capability capability,
        ResolvedOptions options,
        List<String> warnings) {

    public ConversionPlan {
        Objects.requireNonNull(request, "request");
        sources = List.copyOf(sources);
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(options, "options");
        warnings = List.copyOf(warnings);
    }
}
