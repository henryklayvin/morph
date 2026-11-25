package io.github.henryklayvin.morph.engine;

import io.github.henryklayvin.morph.contracts.Availability;
import io.github.henryklayvin.morph.contracts.Capability;
import io.github.henryklayvin.morph.contracts.ConversionPlan;
import io.github.henryklayvin.morph.contracts.ConversionProvider;
import io.github.henryklayvin.morph.contracts.ConversionRequest;
import io.github.henryklayvin.morph.contracts.ErrorCode;
import io.github.henryklayvin.morph.contracts.FormatDescriptor;
import io.github.henryklayvin.morph.contracts.FormatId;
import io.github.henryklayvin.morph.contracts.MorphException;
import io.github.henryklayvin.morph.contracts.ResolvedOptions;
import io.github.henryklayvin.morph.contracts.SourceFile;
import io.github.henryklayvin.morph.engine.ProviderRegistry.Offer;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Chooses the provider for a request. Plans are single-step on purpose: chaining conversions multiplies quality
 * loss in ways the user did not ask for (docs/adr/0004).
 */
public final class ConversionPlanner {

    private final ProviderRegistry registry;

    public ConversionPlanner(ProviderRegistry registry) {
        this.registry = registry;
    }

    /** Candidate offers for the first input that can also take every other input in the request. */
    public List<Offer> compatibleOffers(List<SourceFile> sources, FormatDescriptor target) throws MorphException {
        FormatDescriptor first = sources.getFirst().format();
        List<Offer> offers = registry.offers(first.id(), target.id());
        if (offers.isEmpty()) {
            throw unsupported(first, target);
        }
        List<Offer> usable = offers.stream().filter(o -> acceptsAll(o, sources, target.id())).toList();
        if (usable.isEmpty()) {
            throw new MorphException(ErrorCode.UNSUPPORTED_CONVERSION, sources.size() > 1
                    ? "Estes arquivos não podem ser combinados em um único %s.".formatted(target.displayName())
                    : unsupported(first, target).getMessage());
        }
        return usable;
    }

    public ConversionPlan plan(ConversionRequest request, List<SourceFile> sources, FormatDescriptor target,
            List<Offer> candidates, List<String> warnings) throws MorphException {
        Availability lastFailure = null;
        for (Offer offer : candidates) {
            Availability availability = registry.availability(offer);
            if (availability.available()) {
                Capability capability = offer.capability();
                return new ConversionPlan(request, sources, target, offer.provider().descriptor(), capability,
                        ResolvedOptions.resolve(capability.options(), request.options()), warnings);
            }
            lastFailure = availability;
        }
        throw new MorphException(ErrorCode.PROVIDER_UNAVAILABLE,
                "A conversão existe, mas não está disponível neste computador: "
                        + (lastFailure == null ? "" : lastFailure.reason()));
    }

    ConversionProvider providerOf(ConversionPlan plan) {
        return registry.providers().stream()
                .filter(p -> p.descriptor().id().equals(plan.provider().id()))
                .findFirst().orElseThrow();
    }

    private boolean acceptsAll(Offer offer, List<SourceFile> sources, FormatId target) {
        if (sources.size() > 1 && !offer.capability().cardinality().acceptsManyInputs()) {
            return false;
        }
        // Mixed inputs (PNG + JPEG into one PDF) need the same provider and the same settings for every format.
        return sources.stream().map(s -> s.format().id()).distinct().allMatch(source -> {
            Optional<Capability> match = offer.provider().capabilities().stream()
                    .filter(c -> c.source().equals(source) && c.target().equals(target))
                    .findFirst();
            return match.isPresent()
                    && match.get().cardinality() == offer.capability().cardinality()
                    && match.get().options().equals(offer.capability().options());
        });
    }

    private MorphException unsupported(FormatDescriptor source, FormatDescriptor target) {
        String targets = registry.availableTargets(source.id()).stream()
                .map(FormatId::value).collect(Collectors.joining(", "));
        String hint = targets.isEmpty()
                ? "Nenhum provider instalado converte arquivos %s.".formatted(source.displayName())
                : "Destinos disponíveis para %s: %s.".formatted(source.displayName(), targets);
        return new MorphException(ErrorCode.UNSUPPORTED_CONVERSION,
                "Não há conversão de %s para %s. %s".formatted(source.displayName(), target.displayName(), hint));
    }
}
