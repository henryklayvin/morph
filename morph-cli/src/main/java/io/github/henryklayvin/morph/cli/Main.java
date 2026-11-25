package io.github.henryklayvin.morph.cli;

import io.github.henryklayvin.morph.contracts.MorphException;
import picocli.CommandLine;

/** Entry point of the {@code morph} executable. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(Terminal.system(), args));
    }

    static int run(Terminal terminal, String... args) {
        CommandLine cli = new CommandLine(new MorphCommand());
        cli.addSubcommand(new ConvertCommand(terminal));
        cli.addSubcommand(new FormatsCommand(terminal));
        cli.addSubcommand(new DoctorCommand(terminal));
        cli.addSubcommand(new WebCommand(terminal));
        cli.setOut(new java.io.PrintWriter(terminal.out(), true));
        cli.setErr(new java.io.PrintWriter(terminal.err(), true));
        cli.setCaseInsensitiveEnumValuesAllowed(true);
        cli.setExecutionExceptionHandler((e, command, parsed) -> {
            if (e instanceof MorphException known) {
                terminal.err().println("Erro: " + known.getMessage());
                return ExitCodes.of(known.code());
            }
            // Unexpected: the type helps a bug report, the text could contain personal paths.
            terminal.err().println("Erro interno (" + e.getClass().getSimpleName() + ").");
            return ExitCodes.INTERNAL;
        });
        return cli.execute(args);
    }
}
