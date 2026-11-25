package dev.morph.contracts;

import java.util.List;

/**
 * SPI implemented by every conversion mechanism and discovered with {@link java.util.ServiceLoader}.
 *
 * <p>Implementations must be stateless and thread-safe, must only write inside
 * {@link ConversionContext#workDirectory()} and must not open network connections.
 */
public interface ConversionProvider {

    ProviderDescriptor descriptor();

    List<Capability> capabilities();

    /**
     * Availability of one capability on this machine (native library loads, external tool present...). It is per
     * capability, not per provider, so that a missing WebP encoder does not disable PNG and JPEG.
     */
    default Availability availability(Capability capability) {
        return Availability.ready();
    }

    /**
     * Formats beyond {@link StandardFormats} introduced by this provider. This is what lets a new category be
     * added without touching the engine.
     */
    default List<FormatDescriptor> additionalFormats() {
        return List.of();
    }

    /**
     * Performs the conversion. Every produced file carries a check that re-reads it from disk; the engine runs the
     * checks before anything is delivered to the user.
     */
    List<ProducedOutput> convert(ConversionContext context) throws Exception;
}
