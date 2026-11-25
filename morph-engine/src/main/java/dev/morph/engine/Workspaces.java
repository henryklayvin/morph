package dev.morph.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns every temporary file of this process under one private directory.
 *
 * <p>Temporary files are copies of personal documents, so they must not outlive the process even after a crash: the
 * directory is held by a file lock and the next Morph process erases the ones whose owner is gone.
 */
public final class Workspaces implements AutoCloseable {

    static final String PREFIX = "morph-ws-";
    private static final String LOCK_NAME = "owner.lock";

    /** Sessions of this JVM. POSIX drops a process's locks when any descriptor of the file closes: never probe them. */
    private static final Set<Path> LIVE = ConcurrentHashMap.newKeySet();

    private final Path session;
    private final FileChannel lockChannel;

    public Workspaces(Path parent) throws IOException {
        Files.createDirectories(parent);
        this.session = Files.createTempDirectory(parent, PREFIX).toRealPath();
        LIVE.add(session);
        FileChannel channel = FileChannel.open(session.resolve(LOCK_NAME),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            channel.lock();
        } catch (IOException e) {
            channel.close();
            LIVE.remove(session);
            deleteTree(session);
            throw e;
        }
        this.lockChannel = channel;
        removeStale(parent);
    }

    /** Creates a fresh private directory. */
    public Path create(String kind) throws IOException {
        return Files.createTempDirectory(session, kind + "-");
    }

    /** Best-effort recursive removal that never follows symbolic links. */
    public static void deleteTree(Path root) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    deleteQuietly(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e) {
                    deleteQuietly(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Path session() {
        return session;
    }

    @Override
    public void close() {
        try {
            lockChannel.close();
        } catch (IOException ignored) {
            // The operating system releases the lock when the process ends.
        }
        LIVE.remove(session);
        deleteTree(session);
    }

    private void removeStale(Path parent) throws IOException {
        Object owner = Files.getOwner(session);
        try (DirectoryStream<Path> siblings = Files.newDirectoryStream(parent, PREFIX + "*")) {
            for (Path sibling : siblings) {
                try {
                    // Only directories created by this same user: in a shared /tmp anything else is not ours to touch.
                    if (!LIVE.contains(sibling.toRealPath(LinkOption.NOFOLLOW_LINKS))
                            && Files.isDirectory(sibling, LinkOption.NOFOLLOW_LINKS)
                            && Files.getOwner(sibling, LinkOption.NOFOLLOW_LINKS).equals(owner)
                            && isAbandoned(sibling)) {
                        deleteTree(sibling);
                    }
                } catch (IOException ignored) {
                    // Unreadable or concurrently removed; leave it alone.
                }
            }
        }
    }

    private static boolean isAbandoned(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory.resolve(LOCK_NAME), StandardOpenOption.WRITE);
                FileLock lock = channel.tryLock()) {
            return lock != null;
        } catch (OverlappingFileLockException e) {
            return false; // being created by another engine in this JVM
        } catch (NoSuchFileException e) {
            return false; // another process may be between creating the directory and its lock
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Retried by the stale clean-up of the next process.
        }
    }
}
