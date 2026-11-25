package io.github.henryklayvin.morph.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UploadStoreTest {

    @TempDir
    Path temp;

    @Test
    void refusedUploadsDoNotLoosenTheCeiling() throws Exception {
        UploadStore store = new UploadStore(temp, 100, 1000);
        for (int i = 0; i < 5; i++) {
            store.receive("ok", bytes(100));
        }
        for (int i = 0; i < 20; i++) {
            assertThrows(ApiException.class, () -> store.receive("big", bytes(150)));
        }
        for (int i = 0; i < 5; i++) {
            store.receive("ok", bytes(100));
        }
        assertEquals(413, assertThrows(ApiException.class, () -> store.receive("one too many", bytes(1))).status());
        assertEquals(10, count());
    }

    @Test
    void interruptedUploadsAreErasedAndReleased() throws Exception {
        UploadStore store = new UploadStore(temp, 100, 100);
        for (int i = 0; i < 5; i++) {
            assertThrows(IOException.class, () -> store.receive("cut", new InputStream() {
                private int sent;

                @Override
                public int read() throws IOException {
                    if (sent++ >= 80) {
                        throw new IOException("connection reset");
                    }
                    return 1;
                }
            }));
        }
        assertEquals(0, count());
        store.receive("ok", bytes(100));
    }

    @Test
    void removalAndIdleTimeFreeSpace() throws Exception {
        UploadStore store = new UploadStore(temp, 100, 100);
        store.remove(store.receive("a", bytes(100)).id());
        store.receive("b", bytes(100));
        store.removeIdle(Duration.ofSeconds(-1));
        assertEquals(0, count());
        store.receive("c", bytes(100));
    }

    private static InputStream bytes(int count) {
        return new ByteArrayInputStream(new byte[count]);
    }

    private long count() throws IOException {
        try (var list = Files.list(temp)) {
            return list.count();
        }
    }
}
