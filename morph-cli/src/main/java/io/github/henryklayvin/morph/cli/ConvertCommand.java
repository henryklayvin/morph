package io.github.henryklayvin.morph.cli;

import io.github.henryklayvin.morph.application.MorphRuntime;
import io.github.henryklayvin.morph.contracts.ConversionOptions;
import io.github.henryklayvin.morph.contracts.ConversionRequest;
import io.github.henryklayvin.morph.contracts.ConversionRequest.ConflictPolicy;
import io.github.henryklayvin.morph.contracts.ConversionRequest.InputFile;
import io.github.henryklayvin.morph.contracts.ConversionRequest.OutputSpec;
import io.github.henryklayvin.morph.contracts.ConversionResult;
import io.github.henryklayvin.morph.contracts.ConversionResult.OutputFile;
import io.github.henryklayvin.morph.contracts.FormatDescriptor;
import io.github.henryklayvin.morph.contracts.MorphException;
import io.github.henryklayvin.morph.contracts.ResourceLimits;
import io.github.henryklayvin.morph.engine.ConversionJob;
import io.github.henryklayvin.morph.engine.EngineConfig;
import io.github.henryklayvin.morph.engine.FileNames;
import io.github.henryklayvin.morph.engine.JobListener;
import io.github.henryklayvin.morph.engine.MorphEngine;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "convert", description = "Converte um ou mais arquivos.",
        optionListHeading = "%nOpções:%n", parameterListHeading = "%nArquivos:%n", sortOptions = false,
        footerHeading = "%nExemplos:%n",
        footer = {
            "  morph convert foto.png --to webp",
            "  morph convert *.jpg --to png --dir convertidas",
            "  morph convert foto.png --to jpeg -O quality=90 -O background=#000000",
            "  morph convert pagina1.png pagina2.jpg --to pdf --merge -o documento.pdf",
            "  morph convert documento.pdf --to png -O dpi=200"
        })
final class ConvertCommand implements Callable<Integer> {

    private final Terminal terminal;

    @Spec
    private CommandSpec spec;

    @Parameters(arity = "1..*", paramLabel = "ARQUIVO", description = "Arquivos de entrada.")
    private List<Path> files;

    @Option(names = {"-t", "--to"}, required = true, paramLabel = "FORMATO",
            description = "Formato de destino (veja 'morph formats').")
    private String target;

    @Option(names = {"-o", "--output"}, paramLabel = "ARQUIVO", description = "Arquivo de saída.")
    private Path output;

    @Option(names = {"-d", "--dir"}, paramLabel = "PASTA",
            description = "Pasta de saída. Padrão: a mesma do arquivo de entrada.")
    private Path directory;

    @Option(names = {"-O", "--option"}, paramLabel = "CHAVE=VALOR",
            description = "Configuração da conversão; pode ser repetida.")
    private Map<String, String> options = new LinkedHashMap<>();

    @Option(names = "--on-conflict", defaultValue = "fail", paramLabel = "AÇÃO",
            description = "Se o destino já existir: fail (padrão), rename ou overwrite.")
    private ConflictPolicy onConflict;

    @Option(names = "--merge", description = "Combina todos os arquivos em um único resultado (imagens para PDF).")
    private boolean merge;

    @Option(names = "--timeout", paramLabel = "SEGUNDOS", description = "Tempo máximo por conversão. Padrão: 300.")
    private Integer timeoutSeconds;

    @Option(names = "--json", description = "Resultado em JSON (UTF-8) na saída padrão.")
    private boolean json;

    @Option(names = {"-q", "--quiet"}, description = "Mostra apenas erros.")
    private boolean quiet;

    ConvertCommand(Terminal terminal) {
        this.terminal = terminal;
    }

    private record Task(List<InputFile> inputs, OutputSpec output) {
    }

    private record ErrorView(String code, String message) {
    }

    private record OutputView(String path, String format, long size, Map<String, String> properties) {
    }

    private record ResultView(List<String> inputs, boolean ok, String operation, String provider,
            List<OutputView> outputs, List<String> warnings, Long elapsedMillis, ErrorView error) {
    }

    @Override
    public Integer call() throws Exception {
        checkArguments();
        int workers = merge ? 1 : Math.min(files.size(), EngineConfig.defaultWorkers());
        MorphRuntime runtime = MorphRuntime.start(EngineConfig.withWorkers(workers));
        // Ctrl+C: cancels running conversions and erases temporary files before the process ends.
        Thread hook = new Thread(runtime::close, "morph-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            return convert(runtime.engine());
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
                runtime.close();
            } catch (IllegalStateException e) {
                // Already shutting down: the hook is taking care of it.
            }
        }
    }

    private void checkArguments() {
        if (output != null && directory != null) {
            throw MorphCommand.misuse(spec, "Use --output ou --dir, não os dois.");
        }
        if (output != null && files.size() > 1 && !merge) {
            throw MorphCommand.misuse(spec, "--output aceita um único arquivo de entrada (ou use --merge).");
        }
        if (timeoutSeconds != null && timeoutSeconds < 1) {
            throw MorphCommand.misuse(spec, "--timeout precisa ser maior que zero.");
        }
    }

    private int convert(MorphEngine engine) throws MorphException, InterruptedException {
        FormatDescriptor format = engine.formats().resolve(target);
        ResourceLimits limits = timeoutSeconds == null ? engine.config().limits()
                : engine.config().limits().withTimeout(Duration.ofSeconds(timeoutSeconds));
        List<Task> tasks = plan();
        Progress progress = new Progress(tasks.size());

        List<ConversionJob> jobs = new ArrayList<>();
        for (int i = 0; i < tasks.size(); i++) {
            jobs.add(engine.submit(new ConversionRequest(tasks.get(i).inputs(), format.id(),
                    new ConversionOptions(options), tasks.get(i).output(), limits), progress.listener(i)));
        }
        List<ResultView> results = new ArrayList<>();
        List<Integer> failures = new ArrayList<>();
        for (int i = 0; i < jobs.size(); i++) {
            try {
                ConversionResult result = jobs.get(i).await();
                progress.clear();
                results.add(success(tasks.get(i), result));
            } catch (MorphException e) {
                progress.clear();
                failures.add(ExitCodes.of(e.code()));
                results.add(failure(tasks.get(i), e));
            }
        }
        if (json) {
            terminal.json(Map.of("results", results));
        }
        return ExitCodes.ofBatch(tasks.size(), failures);
    }

    private List<Task> plan() {
        List<InputFile> inputs = files.stream().map(InputFile::of).toList();
        if (merge) {
            return List.of(new Task(inputs, outputFor(inputs.getFirst())));
        }
        return inputs.stream().map(i -> new Task(List.of(i), outputFor(i))).toList();
    }

    private OutputSpec outputFor(InputFile input) {
        if (output != null) {
            Path absolute = output.toAbsolutePath();
            String name = absolute.getFileName().toString();
            String extension = FileNames.extension(name);
            // Keep the spelling the user chose (.JPG, .jpeg) as long as it belongs to the format.
            String spelled = extension.isEmpty() ? null : name.substring(name.length() - extension.length());
            return new OutputSpec(absolute.getParent(), FileNames.base(name), spelled, onConflict);
        }
        Path parent = input.path().toAbsolutePath().getParent();
        return new OutputSpec(directory == null ? parent : directory,
                FileNames.sanitize(FileNames.base(input.displayName())), null, onConflict);
    }

    private ResultView success(Task task, ConversionResult result) {
        if (!json && !quiet) {
            for (OutputFile file : result.outputs()) {
                terminal.out().println("%s -> %s (%s)".formatted(label(task), file.path(),
                        Terminal.size(file.size())));
            }
        }
        if (!json) {
            result.warnings().forEach(w -> terminal.err().println("Aviso: " + label(task) + ": " + w));
        }
        List<OutputView> outputs = result.outputs().stream()
                .map(f -> new OutputView(f.path().toString(), f.format().value(), f.size(), f.properties())).toList();
        return new ResultView(inputs(task), true, result.operationId(), result.providerId(), outputs,
                result.warnings(), result.elapsed().toMillis(), null);
    }

    private ResultView failure(Task task, MorphException e) {
        if (!json) {
            terminal.err().println("Erro: " + label(task) + ": " + e.getMessage());
        }
        return new ResultView(inputs(task), false, null, null, List.of(), List.of(), null,
                new ErrorView(e.code().name(), e.getMessage()));
    }

    private static String label(Task task) {
        String first = task.inputs().getFirst().displayName();
        return task.inputs().size() == 1 ? first : first + " (+" + (task.inputs().size() - 1) + ")";
    }

    private static List<String> inputs(Task task) {
        return task.inputs().stream().map(i -> i.path().toString()).toList();
    }

    /** One line on the standard error, redrawn in place; silent when nobody is watching. */
    private final class Progress {
        private final boolean enabled = terminal.interactive() && !json && !quiet;
        private final double[] fractions;

        Progress(int tasks) {
            fractions = new double[tasks];
        }

        JobListener listener(int index) {
            return new JobListener() {
                @Override
                public void onProgress(double fraction) {
                    draw(index, fraction);
                }
            };
        }

        private synchronized void draw(int index, double fraction) {
            fractions[index] = fraction;
            if (enabled) {
                double total = 0;
                for (double f : fractions) {
                    total += f;
                }
                terminal.err().print("\rConvertendo... %3d%%".formatted(Math.round(100 * total / fractions.length)));
                terminal.err().flush();
            }
        }

        synchronized void clear() {
            if (enabled) {
                terminal.err().print("\r                    \r");
                terminal.err().flush();
            }
        }
    }
}
