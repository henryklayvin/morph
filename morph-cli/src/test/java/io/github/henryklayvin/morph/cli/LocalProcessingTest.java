package io.github.henryklayvin.morph.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Privacy as a test: while real conversions run, the JVM's own flight recorder lists every socket operation and a
 * custom resolver lists every name lookup. Third-party code is observed exactly like ours.
 */
@Timeout(120)
class LocalProcessingTest {

    private static final Path FIXTURES = Path.of("..", "test-fixtures");

    @TempDir
    Path temp;

    @BeforeEach
    void forgetLookups() {
        DnsRecorder.LOOKUPS.clear();
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    @Test
    void theInstrumentsDetectNetworkActivity() throws Exception {
        assertThrows(UnknownHostException.class, () -> InetAddress.getByName("example.invalid"));
        assertEquals(List.of("example.invalid"), DnsRecorder.LOOKUPS);
        List<RecordedEvent> events = record(() -> {
            try (WebSession web = new WebSession()) {
                web.shutdown();
            }
        });
        assertFalse(events.isEmpty(), "loopback HTTP traffic must be visible to the recorder");
    }

    @Test
    void theLocalityRuleIsNotTooPermissive() throws Exception {
        assertTrue(isLocal("127.0.0.1", "127.0.0.1"));
        assertTrue(isLocal("localhost", "0:0:0:0:0:0:0:1"));
        assertTrue(isLocal("Unix domain socket", "[]"));
        assertFalse(isLocal("printer", "192.168.0.10"));
        assertFalse(isLocal("example.com", "93.184.215.14"));
        assertFalse(isLocal("localhost", "2606:2800:21f:cb07:6820:80da:af6b:8b2c"));
    }

    @Test
    void theCliOpensNoSocketAtAll() throws Exception {
        List<RecordedEvent> events = record(() -> {
            assertEquals(0, Cli.run("convert", FIXTURES.resolve("screenshot.png").toString(),
                    FIXTURES.resolve("quadrants.jpg").toString(), "--to", "webp", "--dir", temp.toString()).exit());
            assertEquals(0, Cli.run("convert", FIXTURES.resolve("screenshot.png").toString(), "--to", "pdf", "--dir",
                    temp.toString()).exit());
            assertEquals(0, Cli.run("convert", FIXTURES.resolve("document-3-pages.pdf").toString(), "--to", "png",
                    "--dir", temp.toString(), "-O", "dpi=50").exit());
            Cli.run("formats");
            Cli.run("doctor");
        });
        assertEquals(List.of(), events.stream().map(LocalProcessingTest::remote).toList());
        assertEquals(List.of(), DnsRecorder.LOOKUPS);
    }

    @Test
    void morphLocalTalksToLoopbackOnly() throws Exception {
        List<RecordedEvent> events = record(() -> {
            try (WebSession web = new WebSession()) {
                web.convert("a.png", Files.readAllBytes(FIXTURES.resolve("screenshot.png")), "webp", Map.of());
                web.convert("a.png", Files.readAllBytes(FIXTURES.resolve("screenshot.png")), "pdf", Map.of());
                web.convert("a.pdf", Files.readAllBytes(FIXTURES.resolve("document-3-pages.pdf")), "jpeg",
                        Map.of("dpi", "50"));
                web.shutdown();
            }
        });
        assertFalse(events.isEmpty());
        for (RecordedEvent event : events) {
            assertTrue(isLocal(event.getString("host"), event.getString("address")), remote(event));
        }
        assertEquals(List.of(), DnsRecorder.LOOKUPS);
    }

    private List<RecordedEvent> record(Action action) throws Exception {
        Path file = temp.resolve("network.jfr");
        try (Recording recording = new Recording()) {
            recording.enable("jdk.SocketRead").withThreshold(Duration.ZERO);
            recording.enable("jdk.SocketWrite").withThreshold(Duration.ZERO);
            recording.start();
            action.run();
            recording.stop();
            recording.dump(file);
        }
        List<RecordedEvent> events = RecordingFile.readAllEvents(file).stream()
                .filter(e -> e.getEventType().getName().startsWith("jdk.Socket"))
                .filter(e -> e.hasField("address") && e.getString("address") != null)
                .toList();
        Files.delete(file);
        return events;
    }

    /**
     * On Windows the JDK wakes its selectors up through a Unix domain socket, which the recorder names as such.
     * Those never leave the machine either.
     */
    private static boolean isLocal(String host, String address) throws UnknownHostException {
        return "Unix domain socket".equals(host) || InetAddress.getByName(address).isLoopbackAddress();
    }

    private static String remote(RecordedEvent event) {
        return event.getEventType().getName() + " " + event.getString("address") + ":" + event.getInt("port");
    }
}
