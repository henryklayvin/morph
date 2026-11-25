package io.github.henryklayvin.morph.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspacesTest {

    @TempDir
    Path temp;

    @Test
    void erasesDirectoriesLeftBehindByACrashedProcess() throws Exception {
        Path abandoned = Files.createDirectory(temp.resolve(Workspaces.PREFIX + "crashed"));
        Files.createFile(abandoned.resolve("owner.lock"));
        Path job = Files.createDirectory(abandoned.resolve("job-1"));
        Files.writeString(job.resolve("private.png"), "personal data");
        try (Workspaces ignored = new Workspaces(temp)) {
            assertFalse(Files.exists(abandoned));
        }
    }

    @Test
    void neverTouchesTheWorkspaceOfALiveEngine() throws Exception {
        try (Workspaces first = new Workspaces(temp)) {
            Path file = Files.writeString(first.create("job").resolve("x"), "in use");
            try (Workspaces second = new Workspaces(temp)) {
                assertTrue(Files.exists(file));
                assertFalse(second.session().equals(first.session()));
            }
            assertTrue(Files.exists(file));
            // A third engine still sees the first one as alive after the second one closed.
            try (Workspaces ignored = new Workspaces(temp)) {
                assertTrue(Files.exists(file));
            }
        }
        assertEquals(0, count(temp));
    }

    @Test
    void ignoresUnrelatedDirectories() throws Exception {
        Path unrelated = Files.createDirectory(temp.resolve("morph-photos"));
        Files.writeString(unrelated.resolve("keep.txt"), "keep");
        try (Workspaces ignored = new Workspaces(temp)) {
            assertTrue(Files.exists(unrelated.resolve("keep.txt")));
        }
        assertTrue(Files.exists(unrelated.resolve("keep.txt")));
    }

    @Test
    void deletionDoesNotFollowSymbolicLinks() throws Exception {
        Path precious = Files.createDirectory(temp.resolve("precious"));
        Files.writeString(precious.resolve("keep.txt"), "keep");
        Path doomed = Files.createDirectory(temp.resolve("doomed"));
        try {
            Files.createSymbolicLink(doomed.resolve("link"), precious);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("Symbolic links are not permitted for this user");
        }
        Workspaces.deleteTree(doomed);
        assertFalse(Files.exists(doomed));
        assertTrue(Files.exists(precious.resolve("keep.txt")));
    }

    @Test
    void directoriesArePrivateOnPosix() throws Exception {
        Assumptions.assumeTrue(Files.getFileStore(temp).supportsFileAttributeView("posix"));
        try (Workspaces workspaces = new Workspaces(temp)) {
            assertEquals("rwx------",
                    PosixFilePermissions.toString(Files.getPosixFilePermissions(workspaces.session())));
            assertEquals("rwx------",
                    PosixFilePermissions.toString(Files.getPosixFilePermissions(workspaces.create("job"))));
        }
    }

    private static long count(Path directory) throws IOException {
        try (var list = Files.list(directory)) {
            return list.count();
        }
    }
}
