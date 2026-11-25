package dev.morph.local;

import dev.morph.contracts.ConversionRequest.InputFile;
import dev.morph.contracts.ErrorCode;
import dev.morph.contracts.FormatDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Files handed over by the browser, kept in the engine's private temporary area. They are stored under random
 * names; the original name is only ever used as a label.
 */
final class UploadStore {

    static final class Upload {
        private final String id = UUID.randomUUID().toString();
        private final Path path;
        private final String name;
        private volatile long size;
        private volatile FormatDescriptor format;
        private volatile Instant lastUse = Instant.now();

        private Upload(Path directory, String name) {
            this.path = directory.resolve(id);
            this.name = name;
        }

        String id() {
            return id;
        }

        String name() {
            return name;
        }

        long size() {
            return size;
        }

        FormatDescriptor format() {
            return format;
        }

        void format(FormatDescriptor value) {
            format = value;
        }

        InputFile asInput() {
            lastUse = Instant.now();
            return new InputFile(path, name);
        }
    }

    private final Path directory;
    private final long maxUploadBytes;
    private final long maxStoredBytes;
    private final AtomicLong stored = new AtomicLong();
    private final Map<String, Upload> uploads = new ConcurrentHashMap<>();

    UploadStore(Path directory, long maxUploadBytes, long maxStoredBytes) {
        this.directory = directory;
        this.maxUploadBytes = maxUploadBytes;
        this.maxStoredBytes = maxStoredBytes;
    }

    /** Streams to disk; the size is enforced on the bytes received, not on what the client announces. */
    Upload receive(String name, InputStream body) throws IOException {
        Upload upload = new Upload(directory, name);
        long received = 0;
        long reserved = 0;
        try (OutputStream out = Files.newOutputStream(upload.path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = body.read(buffer)) != -1) {
                received += read;
                if (received > maxUploadBytes) {
                    throw tooLarge("O arquivo excede o limite de %d MiB.".formatted(maxUploadBytes >> 20));
                }
                reserved += read;
                if (stored.addAndGet(read) > maxStoredBytes) {
                    throw tooLarge("A área temporária está cheia. Remova arquivos da lista e tente novamente.");
                }
                out.write(buffer, 0, read);
            }
        } catch (IOException | RuntimeException e) {
            stored.addAndGet(-reserved);
            Files.deleteIfExists(upload.path);
            throw e;
        }
        upload.size = received;
        uploads.put(upload.id, upload);
        return upload;
    }

    Upload require(String id) {
        Upload upload = uploads.get(id);
        if (upload == null) {
            throw ApiException.notFound();
        }
        return upload;
    }

    void remove(String id) {
        Upload upload = uploads.remove(id);
        if (upload != null) {
            stored.addAndGet(-upload.size);
            try {
                Files.deleteIfExists(upload.path);
            } catch (IOException e) {
                // In use by a running conversion (Windows); erased with the whole area when the server stops.
            }
        }
    }

    void removeIdle(Duration limit) {
        Instant threshold = Instant.now().minus(limit);
        uploads.values().stream().filter(u -> u.lastUse.isBefore(threshold)).map(Upload::id).toList()
                .forEach(this::remove);
    }

    private static ApiException tooLarge(String message) {
        return new ApiException(413, ErrorCode.RESOURCE_LIMIT_EXCEEDED.name(), message);
    }
}
