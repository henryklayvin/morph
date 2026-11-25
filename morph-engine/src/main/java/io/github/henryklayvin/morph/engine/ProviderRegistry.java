package io.github.henryklayvin.morph.engine;

import io.github.henryklayvin.morph.contracts.Availability;
import io.github.henryklayvin.morph.contracts.Capability;
import io.github.henryklayvin.morph.contracts.ConversionProvider;
import io.github.henryklayvin.morph.contracts.FormatDescriptor;
import io.github.henryklayvin.morph.contracts.FormatId;
import io.github.henryklayvin.morph.contracts.StandardFormats;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Registered providers plus the compatibility matrix derived from what they declare. */
public final class ProviderRegistry {

    /** One offer in the matrix. Availability is evaluated lazily: probing native code or tools is not free. */
    public record Offer(ConversionProvider provider, Capability capability) {
    }

    private static final Comparator<Offer> BEST_FIRST = Comparator
            .comparingInt((Offer o) -> o.capability().priority()).reversed()
            .thenComparing(o -> o.provider().descriptor().id());

    private final List<ConversionProvider> providers;
    private final List<Offer> offers;
    private final FormatRegistry formats;
    private final Map<Offer, Availability> availability = new ConcurrentHashMap<>();

    public ProviderRegistry(List<ConversionProvider> providers) {
        this.providers = List.copyOf(providers);
        List<FormatDescriptor> allFormats = new ArrayList<>(StandardFormats.all());
        Set<String> ids = new HashSet<>();
        List<Offer> collected = new ArrayList<>();
        for (ConversionProvider provider : this.providers) {
            if (!ids.add(provider.descriptor().id())) {
                throw new IllegalStateException("Duplicate provider id: " + provider.descriptor().id());
            }
            allFormats.addAll(provider.additionalFormats());
            provider.capabilities().forEach(c -> collected.add(new Offer(provider, c)));
        }
        this.formats = new FormatRegistry(allFormats);
        for (Offer offer : collected) {
            requireKnown(offer.capability().source());
            requireKnown(offer.capability().target());
        }
        this.offers = collected.stream().sorted(BEST_FIRST).toList();
    }

    public FormatRegistry formats() {
        return formats;
    }

    public List<ConversionProvider> providers() {
        return providers;
    }

    public List<Offer> offers() {
        return offers;
    }

    /** Offers for one operation, best first, available or not. */
    public List<Offer> offers(FormatId source, FormatId target) {
        return offers.stream()
                .filter(o -> o.capability().source().equals(source) && o.capability().target().equals(target))
                .toList();
    }

    /** Targets reachable from a source with the providers usable on this machine. */
    public List<FormatId> availableTargets(FormatId source) {
        return offers.stream()
                .filter(o -> o.capability().source().equals(source) && availability(o).available())
                .map(o -> o.capability().target())
                .distinct().sorted().toList();
    }

    public Availability availability(Offer offer) {
        return availability.computeIfAbsent(offer, o -> {
            try {
                return o.provider().availability(o.capability());
            } catch (RuntimeException | LinkageError e) {
                // A broken provider must not take the others down with it.
                return Availability.unavailable("Falha ao inicializar o provider (%s)."
                        .formatted(e.getClass().getSimpleName()));
            }
        });
    }

    private void requireKnown(FormatId id) {
        if (formats.find(id).isEmpty()) {
            throw new IllegalStateException("A provider refers to an undeclared format: " + id);
        }
    }
}
