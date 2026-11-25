package dev.morph.cli;

import dev.morph.application.MorphRuntime;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;
import picocli.CommandLine.Spec;

@Command(name = "morph",
        description = "Conversor universal de arquivos. Tudo acontece neste computador; nada é enviado.",
        versionProvider = MorphCommand.Version.class,
        synopsisSubcommandLabel = "COMANDO",
        descriptionHeading = "%n",
        optionListHeading = "%nOpções:%n",
        commandListHeading = "%nComandos:%n",
        footerHeading = "%nCódigos de saída:%n",
        footer = {
            "  0  sucesso                      5  limite ou tempo excedido",
            "  1  erro interno                 6  não foi possível gravar (ou já existe)",
            "  2  uso incorreto                7  conversão ou verificação falhou",
            "  3  problema no arquivo          8  lote concluído parcialmente",
            "  4  conversão não disponível   130  cancelado"
        })
final class MorphCommand implements Runnable {

    @Spec
    private CommandSpec spec;

    @Option(names = {"-h", "--help"}, usageHelp = true, scope = ScopeType.INHERIT, description = "Mostra esta ajuda.")
    private boolean help;

    @Option(names = {"-V", "--version"}, versionHelp = true, description = "Mostra a versão.")
    private boolean version;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    static final class Version implements IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] {"morph " + MorphRuntime.version() + " (Java " + Runtime.version() + ")"};
        }
    }

    /** Shared by subcommands that report misuse themselves. */
    static CommandLine.ParameterException misuse(CommandSpec spec, String message) {
        return new CommandLine.ParameterException(spec.commandLine(), message);
    }
}
