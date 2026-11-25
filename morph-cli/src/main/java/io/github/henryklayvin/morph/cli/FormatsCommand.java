package io.github.henryklayvin.morph.cli;

import io.github.henryklayvin.morph.application.MorphRuntime;
import io.github.henryklayvin.morph.contracts.Availability;
import io.github.henryklayvin.morph.contracts.Capability;
import io.github.henryklayvin.morph.contracts.FormatId;
import io.github.henryklayvin.morph.contracts.Loss;
import io.github.henryklayvin.morph.contracts.OptionSpec;
import io.github.henryklayvin.morph.contracts.ProviderDescriptor.Dependency;
import io.github.henryklayvin.morph.engine.EngineConfig;
import io.github.henryklayvin.morph.engine.MorphEngine;
import io.github.henryklayvin.morph.engine.ProviderRegistry.Offer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "formats", description = "Lista as conversões disponíveis neste computador.",
        optionListHeading = "%nOpções:%n")
final class FormatsCommand implements Callable<Integer> {

    private final Terminal terminal;

    @Option(names = {"-v", "--verbose"}, description = "Mostra opções, perdas e limitações.")
    private boolean verbose;

    @Option(names = "--json", description = "Matriz de compatibilidade em JSON (UTF-8).")
    private boolean json;

    FormatsCommand(Terminal terminal) {
        this.terminal = terminal;
    }

    private record OperationView(String id, String source, String target, String provider, boolean available,
            String reason, String cardinality, List<String> options, List<String> losses, List<String> limitations,
            List<String> dependencies) {
    }

    @Override
    public Integer call() throws Exception {
        try (MorphRuntime runtime = MorphRuntime.start(EngineConfig.withWorkers(1))) {
            MorphEngine engine = runtime.engine();
            List<OperationView> operations = engine.registry().offers().stream()
                    .map(o -> view(o, engine.registry().availability(o)))
                    .sorted((a, b) -> a.id().compareTo(b.id())).toList();
            if (json) {
                terminal.json(Map.of("operations", operations));
            } else {
                print(operations);
            }
            return ExitCodes.OK;
        }
    }

    private void print(List<OperationView> operations) {
        terminal.out().println("%-8s %-8s %-10s %s".formatted("ORIGEM", "DESTINO", "PROVIDER", "SITUAÇÃO"));
        for (OperationView o : operations) {
            terminal.out().println("%-8s %-8s %-10s %s".formatted(o.source(), o.target(), o.provider(),
                    o.available() ? "disponível" : "indisponível: " + o.reason()));
            if (verbose) {
                o.options().forEach(line -> terminal.out().println("    opção      " + line));
                o.losses().forEach(line -> terminal.out().println("    perda      " + line));
                o.limitations().forEach(line -> terminal.out().println("    limitação  " + line));
            }
        }
    }

    private static OperationView view(Offer offer, Availability availability) {
        Capability c = offer.capability();
        FormatId source = c.source();
        return new OperationView(c.operationId(), source.value(), c.target().value(),
                offer.provider().descriptor().id(), availability.available(), availability.reason(),
                c.cardinality().name(), c.options().stream().map(FormatsCommand::describe).toList(),
                c.losses().stream().map(Loss::description).toList(), c.limitations(),
                offer.provider().descriptor().dependencies().stream().map(Dependency::name)
                        .collect(Collectors.toList()));
    }

    private static String describe(OptionSpec<?> spec) {
        String accepted = switch (spec) {
            case OptionSpec.IntegerOption o -> o.min() + " a " + o.max();
            case OptionSpec.BooleanOption o -> "true ou false";
            case OptionSpec.ChoiceOption o -> String.join(", ", o.choices());
            case OptionSpec.ColorOption o -> "#rrggbb";
        };
        return "%s (%s; padrão %s): %s".formatted(spec.key(), accepted, spec.defaultValue(), spec.description());
    }
}
