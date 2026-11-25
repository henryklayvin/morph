package dev.morph.cli;

import dev.morph.application.MorphRuntime;
import dev.morph.engine.EngineConfig;
import dev.morph.local.LocalServer;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "web", description = "Abre a interface do Morph no navegador, usando o motor deste computador.",
        optionListHeading = "%nOpções:%n")
final class WebCommand implements Callable<Integer> {

    private final Terminal terminal;

    @Spec
    private CommandSpec spec;

    @Option(names = "--port", defaultValue = "0", paramLabel = "PORTA",
            description = "Porta local. Padrão: uma porta livre qualquer.")
    private int port;

    @Option(names = "--no-browser", description = "Não abre o navegador; apenas mostra o link.")
    private boolean noBrowser;

    WebCommand(Terminal terminal) {
        this.terminal = terminal;
    }

    @Override
    public Integer call() throws Exception {
        if (port < 0 || port > 65535) {
            throw MorphCommand.misuse(spec, "--port precisa estar entre 0 e 65535.");
        }
        MorphRuntime runtime = MorphRuntime.start(EngineConfig.withWorkers(EngineConfig.defaultWorkers()));
        LocalServer server;
        try {
            server = LocalServer.start(runtime, LocalServer.Options.defaults().withPort(port), this::announce);
        } catch (IOException e) {
            runtime.close();
            terminal.err().println("Erro: não foi possível usar a porta " + port + ".");
            return ExitCodes.OUTPUT;
        }
        Thread hook = new Thread(() -> stop(server, runtime), "morph-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);

        terminal.out().println("Morph Local em " + server.baseUrl() + " (acessível somente neste computador).");
        URI link = server.launchUrl();
        if (noBrowser || !BrowserLauncher.open(link)) {
            announce(link);
        } else {
            terminal.out().println("Se o navegador não abrir, use este link: " + link);
        }
        terminal.out().println("Pressione Ctrl+C para encerrar.");
        server.awaitStop();
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
            stop(server, runtime);
            terminal.out().println("Morph Local encerrado. Os arquivos temporários foram apagados.");
        } catch (IllegalStateException e) {
            // Already shutting down: the hook is taking care of it.
        }
        return ExitCodes.OK;
    }

    private void announce(URI link) {
        terminal.out().println("Link de uso único para abrir uma janela: " + link);
    }

    private static void stop(LocalServer server, MorphRuntime runtime) {
        server.close();
        runtime.close();
    }
}
