package dev.morph.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Timeout(60)
class CliTest {

    private static final Path FIXTURES = Path.of("..", "test-fixtures");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temp;

    private Path png;
    private Path jpeg;

    @BeforeEach
    void copyInputs() throws IOException {
        png = Files.copy(FIXTURES.resolve("screenshot.png"), temp.resolve("férias.png"));
        jpeg = Files.copy(FIXTURES.resolve("quadrants.jpg"), temp.resolve("quadrantes.jpg"));
    }

    @Test
    void convertsNextToTheInputByDefault() throws Exception {
        Cli cli = Cli.run("convert", png.toString(), "--to", "webp");
        assertEquals(0, cli.exit(), cli.err());
        assertTrue(cli.out().contains("férias.webp"));
        assertEquals("", cli.err());
        assertEquals(List.of("férias.png", "férias.webp", "quadrantes.jpg"), files());
        assertTrue(Files.size(temp.resolve("férias.webp")) > 0);
    }

    @Test
    void acceptsExtensionsAndAnyCaseAsFormat() {
        assertEquals(0, Cli.run("convert", png.toString(), "--to", "JPG").exit());
        assertTrue(Files.exists(temp.resolve("férias.jpg")));
    }

    @Test
    void outputFileKeepsTheSpellingOfTheExtension() {
        assertEquals(0, Cli.run("convert", png.toString(), "-t", "jpeg", "-o",
                temp.resolve("sub").resolve("X.JPEG").toString()).exit());
        assertTrue(Files.exists(temp.resolve("sub").resolve("X.JPEG")));
        assertEquals(ExitCodes.USAGE, Cli.run("convert", png.toString(), "-t", "jpeg", "-o",
                temp.resolve("x.gif").toString()).exit());
    }

    @Test
    void existingFilesAreProtected() throws Exception {
        Files.writeString(temp.resolve("férias.jpg"), "precious");
        assertEquals(ExitCodes.OUTPUT, Cli.run("convert", png.toString(), "--to", "jpeg").exit());
        assertEquals("precious", Files.readString(temp.resolve("férias.jpg")));
        assertEquals(0, Cli.run("convert", png.toString(), "--to", "jpeg", "--on-conflict", "rename").exit());
        assertEquals("precious", Files.readString(temp.resolve("férias.jpg")));
        assertTrue(Files.exists(temp.resolve("férias (1).jpg")));
        assertEquals(0, Cli.run("convert", png.toString(), "--to", "jpeg", "--on-conflict", "overwrite").exit());
        assertArrayEquals(Files.readAllBytes(temp.resolve("férias (1).jpg")),
                Files.readAllBytes(temp.resolve("férias.jpg")));
    }

    @Test
    void optionsReachTheProvider() throws Exception {
        Cli.run("convert", png.toString(), "--to", "jpeg", "-O", "quality=10", "-o", temp.resolve("a.jpg").toString());
        Cli.run("convert", png.toString(), "--to", "jpeg", "-O", "quality=95", "-o", temp.resolve("b.jpg").toString());
        assertTrue(Files.size(temp.resolve("a.jpg")) < Files.size(temp.resolve("b.jpg")));
        Cli bad = Cli.run("convert", png.toString(), "--to", "jpeg", "-O", "qualidade=10");
        assertEquals(ExitCodes.USAGE, bad.exit());
        assertTrue(bad.err().contains("background, quality"), bad.err());
    }

    @Test
    void batchesReportEachFile() {
        Cli ok = Cli.run("convert", png.toString(), jpeg.toString(), "--to", "webp", "--dir",
                temp.resolve("out").toString());
        assertEquals(0, ok.exit());
        assertTrue(Files.exists(temp.resolve("out/férias.webp")) && Files.exists(temp.resolve("out/quadrantes.webp")));

        Cli partial = Cli.run("convert", png.toString(), temp.resolve("nope.png").toString(), "--to", "jpeg");
        assertEquals(ExitCodes.PARTIAL, partial.exit());
        assertTrue(partial.err().contains("nope.png"));

        assertEquals(ExitCodes.INPUT, Cli.run("convert", temp.resolve("a").toString(),
                temp.resolve("b").toString(), "--to", "jpeg").exit());
    }

    @Test
    void mergesImagesIntoOnePdfAndSplitsItBack() {
        Path pdf = temp.resolve("album.pdf");
        Cli merged = Cli.run("convert", png.toString(), jpeg.toString(), "--to", "pdf", "--merge", "-o",
                pdf.toString());
        assertEquals(0, merged.exit(), merged.err());
        Cli split = Cli.run("convert", pdf.toString(), "--to", "png", "-O", "dpi=72");
        assertEquals(0, split.exit(), split.err());
        assertTrue(Files.exists(temp.resolve("album-1.png")) && Files.exists(temp.resolve("album-2.png")));
        assertEquals(ExitCodes.UNSUPPORTED,
                Cli.run("convert", png.toString(), jpeg.toString(), "--to", "webp", "--merge").exit());
    }

    @Test
    void docxToPdfIsAnUnderstoodRequestWithAClearAnswer() throws Exception {
        Path docx = Files.write(temp.resolve("documento.docx"), new byte[] {'P', 'K', 3, 4, 20, 0, 6, 0});
        Cli cli = Cli.run("convert", docx.toString(), "--to", "pdf");
        assertEquals(ExitCodes.UNSUPPORTED, cli.exit());
        assertTrue(cli.err().contains("DOCX") && cli.err().contains("PDF"), cli.err());
        assertFalse(Files.exists(temp.resolve("documento.pdf")));
    }

    @Test
    void jsonOutputIsMachineReadable() {
        JsonNode root = JSON.readTree(Cli.run("convert", png.toString(), temp.resolve("nope").toString(),
                "--to", "webp", "--json").out());
        JsonNode ok = root.get("results").get(0);
        assertTrue(ok.get("ok").asBoolean());
        assertEquals("png-to-webp", ok.get("operation").asString());
        assertEquals("image", ok.get("provider").asString());
        assertTrue(ok.get("outputs").get(0).get("path").asString().endsWith("férias.webp"));
        JsonNode failed = root.get("results").get(1);
        assertFalse(failed.get("ok").asBoolean());
        assertEquals("INPUT_NOT_FOUND", failed.get("error").get("code").asString());
    }

    @Test
    void quietPrintsNothingOnSuccess() {
        Cli cli = Cli.run("convert", png.toString(), "--to", "jpeg", "--quiet");
        assertEquals("", cli.out() + cli.err());
    }

    @Test
    void misuseHasItsOwnExitCode() {
        assertEquals(ExitCodes.USAGE, Cli.run("convert", png.toString()).exit());
        assertEquals(ExitCodes.USAGE, Cli.run("convert", png.toString(), "--to", "banana").exit());
        assertEquals(ExitCodes.USAGE, Cli.run("convert", "--to", "png").exit());
        assertEquals(ExitCodes.USAGE, Cli.run("convert", png.toString(), jpeg.toString(), "--to", "webp", "-o",
                temp.resolve("x.webp").toString()).exit());
        assertEquals(ExitCodes.USAGE, Cli.run("convert", png.toString(), "--to", "webp", "--timeout", "0").exit());
        assertEquals(ExitCodes.USAGE, Cli.run("dance").exit());
        assertEquals(ExitCodes.USAGE, Cli.run("web", "--port", "70000").exit());
    }

    @Test
    void helpVersionFormatsAndDoctor() {
        assertTrue(Cli.run("--help").out().contains("convert"));
        assertTrue(Cli.run("convert", "--help").out().contains("--merge"));
        assertTrue(Cli.run().out().contains("COMANDO"));
        assertTrue(Cli.run("--version").out().startsWith("morph "));
        assertTrue(Cli.run("formats", "--verbose").out().contains("quality"));
        JsonNode matrix = JSON.readTree(Cli.run("formats", "--json").out());
        assertEquals(8, matrix.get("operations").size());
        Cli doctor = Cli.run("doctor", "--json");
        assertTrue(JSON.readTree(doctor.out()).get("checks").size() > 5);
    }

    @Test
    void webStartsServesAndStops() throws Exception {
        try (WebSession web = new WebSession()) {
            byte[] webp = web.convert("a.png", Files.readAllBytes(png), "webp", java.util.Map.of()).getFirst();
            assertEquals('W', webp[8]);
            assertEquals(0, web.shutdown());
            assertTrue(web.output().contains("encerrado"));
        }
    }

    @Test
    void browserCommandsNeverUseAShell() {
        URI link = URI.create("http://127.0.0.1:1234/#code=a&b");
        assertEquals(List.of("rundll32", "url.dll,FileProtocolHandler", link.toString()),
                BrowserLauncher.command("Windows 11", link));
        assertEquals(List.of("open", link.toString()), BrowserLauncher.command("Mac OS X", link));
        assertEquals(List.of("xdg-open", link.toString()), BrowserLauncher.command("Linux", link));
    }

    private List<String> files() throws IOException {
        try (Stream<Path> list = Files.list(temp)) {
            return list.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }
}
