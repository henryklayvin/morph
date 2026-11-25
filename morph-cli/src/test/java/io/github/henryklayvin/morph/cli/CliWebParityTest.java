package io.github.henryklayvin.morph.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The central promise of the architecture: the terminal and the browser use the same engine, so the same input and
 * settings give exactly the same bytes. Covers every operation the CLI announces, present and future.
 */
class CliWebParityTest {

    private static final Path FIXTURES = Path.of("..", "test-fixtures");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, String> SAMPLES = Map.of(
            "png", "screenshot.png", "jpeg", "quadrants-exif-orientation-6.jpg", "pdf", "document-3-pages.pdf");
    /** Non-default settings, so that a front end that dropped them would be noticed. */
    private static final Map<String, Map<String, String>> OPTIONS = Map.of(
            "png-to-jpeg", Map.of("quality", "61", "background", "#102030"),
            "png-to-webp", Map.of("quality", "42"),
            "jpeg-to-webp", Map.of("lossless", "true"),
            "png-to-pdf", Map.of("page", "a4"),
            "pdf-to-png", Map.of("dpi", "50"),
            "pdf-to-jpeg", Map.of("dpi", "50", "quality", "33"));

    @TempDir
    Path temp;

    @TestFactory
    Stream<DynamicTest> bothFrontEndsProduceIdenticalFiles() throws Exception {
        JsonNode operations = JSON.readTree(Cli.run("formats", "--json").out()).get("operations");
        WebSession web = new WebSession();
        return StreamSupport.stream(operations.spliterator(), false)
                .map(operation -> dynamicTest(operation.get("id").asString(), () -> compare(web, operation)))
                .onClose(() -> {
                    try {
                        web.close();
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                });
    }

    private void compare(WebSession web, JsonNode operation) throws Exception {
        String id = operation.get("id").asString();
        String target = operation.get("target").asString();
        Path sample = FIXTURES.resolve(SAMPLES.get(operation.get("source").asString()));
        Map<String, String> options = OPTIONS.getOrDefault(id, Map.of());

        List<String> args = new ArrayList<>(List.of("convert", sample.toString(), "--to", target, "--dir",
                temp.resolve(id).toString(), "--json"));
        options.forEach((key, value) -> args.addAll(List.of("-O", key + "=" + value)));
        Cli cli = Cli.run(args.toArray(String[]::new));
        assertEquals(0, cli.exit(), cli.out() + cli.err());
        JsonNode outputs = JSON.readTree(cli.out()).get("results").get(0).get("outputs");

        List<byte[]> fromWeb = web.convert(sample.getFileName().toString(), Files.readAllBytes(sample), target,
                options);
        assertEquals(outputs.size(), fromWeb.size());
        assertFalse(fromWeb.isEmpty());
        for (int i = 0; i < fromWeb.size(); i++) {
            assertArrayEquals(Files.readAllBytes(Path.of(outputs.get(i).get("path").asString())), fromWeb.get(i));
        }
    }
}
