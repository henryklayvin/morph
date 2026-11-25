package io.github.henryklayvin.morph.local;

import io.github.henryklayvin.morph.engine.ConversionJob;
import io.github.henryklayvin.morph.engine.Workspaces;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Conversions requested through the browser and the directories holding their results. */
final class JobStore {

    static final class Entry {
        private final ConversionJob job;
        private final Path directory;
        private volatile Instant lastUse = Instant.now();

        private Entry(ConversionJob job, Path directory) {
            this.job = job;
            this.directory = directory;
        }

        ConversionJob job() {
            return job;
        }
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    void add(ConversionJob job, Path directory) {
        entries.put(job.id(), new Entry(job, directory));
    }

    Entry require(String id) {
        Entry entry = entries.get(id);
        if (entry == null) {
            throw ApiException.notFound();
        }
        entry.lastUse = Instant.now();
        return entry;
    }

    void remove(String id) {
        Entry entry = entries.remove(id);
        if (entry != null) {
            entry.job.cancel();
            Workspaces.deleteTree(entry.directory);
        }
    }

    void removeIdle(Duration limit) {
        Instant threshold = Instant.now().minus(limit);
        entries.values().stream()
                .filter(e -> e.lastUse.isBefore(threshold))
                .map(e -> e.job.id()).toList()
                .forEach(this::remove);
    }
}
