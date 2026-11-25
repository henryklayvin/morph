package dev.morph.application;

import dev.morph.contracts.ConversionProvider;
import dev.morph.engine.EngineConfig;
import dev.morph.engine.MorphEngine;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Composition root shared by the CLI and Morph Local: both get the same engine with the same providers.
 *
 * <p>Providers come only from the application's own class path. There is no plug-in directory: loading third-party
 * code is out of scope until it can be isolated.
 */
public final class MorphRuntime implements AutoCloseable {

    private final MorphEngine engine;
    private final List<String> providerFailures;

    private MorphRuntime(MorphEngine engine, List<String> providerFailures) {
        this.engine = engine;
        this.providerFailures = List.copyOf(providerFailures);
    }

    public static MorphRuntime start(EngineConfig config) throws IOException {
        // Conversions never need a display, and AWT must not try to open one on servers or over SSH.
        System.setProperty("java.awt.headless", "true");
        List<ConversionProvider> providers = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        Iterator<ConversionProvider> iterator = ServiceLoader
                .load(ConversionProvider.class, MorphRuntime.class.getClassLoader()).iterator();
        while (true) {
            try {
                if (!iterator.hasNext()) {
                    break;
                }
                providers.add(iterator.next());
            } catch (ServiceConfigurationError | LinkageError e) {
                // One broken provider must not disable the others; `morph doctor` reports it.
                failures.add(e.getMessage());
            }
        }
        return new MorphRuntime(new MorphEngine(config, providers), failures);
    }

    public MorphEngine engine() {
        return engine;
    }

    public List<String> providerFailures() {
        return providerFailures;
    }

    public static String version() {
        Properties properties = new Properties();
        try (InputStream in = MorphRuntime.class.getResourceAsStream("build.properties")) {
            if (in != null) {
                properties.load(in);
            }
        } catch (IOException e) {
            // Falls through to the default.
        }
        return properties.getProperty("version", "dev");
    }

    @Override
    public void close() {
        engine.close();
    }
}
