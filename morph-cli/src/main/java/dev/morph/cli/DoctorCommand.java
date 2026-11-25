package dev.morph.cli;

import dev.morph.application.MorphRuntime;
import dev.morph.contracts.Availability;
import dev.morph.contracts.ConversionProvider;
import dev.morph.engine.EngineConfig;
import dev.morph.engine.MorphEngine;
import dev.morph.local.LocalServer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "doctor", description = "Verifica se tudo o que o Morph precisa está funcionando.",
        optionListHeading = "%nOpções:%n")
final class DoctorCommand implements Callable<Integer> {

    private final Terminal terminal;

    @Option(names = "--json", description = "Diagnóstico em JSON (UTF-8).")
    private boolean json;

    DoctorCommand(Terminal terminal) {
        this.terminal = terminal;
    }

    private record Check(String name, boolean ok, String detail) {
    }

    @Override
    public Integer call() throws Exception {
        List<Check> checks = new ArrayList<>();
        int feature = Runtime.version().feature();
        checks.add(new Check("Java", feature >= 21, "%s (mínimo 21)".formatted(Runtime.version())));
        checks.add(new Check("Sistema", true,
                System.getProperty("os.name") + " " + System.getProperty("os.arch")));
        checks.add(new Check("Memória", true,
                "até " + Terminal.size(Runtime.getRuntime().maxMemory()) + " para o Morph"));
        // Starting the engine also proves that the private temporary area can be created and locked.
        try (MorphRuntime runtime = MorphRuntime.start(EngineConfig.withWorkers(1))) {
            checks.add(new Check("Área temporária", true, "criada, travada e privada"));
            runtime.providerFailures().forEach(f -> checks.add(new Check("Provider", false, "não carregou")));
            MorphEngine engine = runtime.engine();
            for (ConversionProvider provider : engine.registry().providers()) {
                List<String> unavailable = new ArrayList<>();
                engine.registry().offers().stream().filter(o -> o.provider() == provider).forEach(o -> {
                    Availability availability = engine.registry().availability(o);
                    if (!availability.available()) {
                        unavailable.add(o.capability().operationId() + ": " + availability.reason());
                    }
                });
                String summary = provider.capabilities().size() - unavailable.size() + " de "
                        + provider.capabilities().size() + " conversões disponíveis";
                checks.add(new Check("Provider " + provider.descriptor().id(), unavailable.isEmpty(), summary));
                unavailable.forEach(u -> checks.add(new Check("  " + u, false, "")));
                provider.descriptor().dependencies().forEach(d -> checks.add(new Check(
                        "  " + d.name(), true, d.license() + " - " + d.requirement())));
            }
        }
        checks.add(new Check("Interface Web", LocalServer.hasUserInterface(),
                LocalServer.hasUserInterface() ? "incluída" : "não incluída nesta compilação"));

        boolean healthy = checks.stream().allMatch(Check::ok);
        if (json) {
            terminal.json(Map.of("healthy", healthy, "version", MorphRuntime.version(), "checks", checks));
        } else {
            for (Check check : checks) {
                terminal.out().println("[%s] %-26s %s".formatted(check.ok() ? "ok" : "--", check.name(),
                        check.detail()));
            }
        }
        return healthy ? ExitCodes.OK : ExitCodes.UNSUPPORTED;
    }
}
