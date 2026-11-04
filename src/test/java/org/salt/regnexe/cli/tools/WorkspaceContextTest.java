package org.salt.regnexe.cli.tools;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

public class WorkspaceContextTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private Path directory(String name) throws Exception {
        return Files.createDirectories(temporary.getRoot().toPath().resolve(name)).toRealPath();
    }

    @Test
    public void rejectsRelativeTraversalForExistingAndMissingReadTargets() throws Exception {
        Path root = directory("workspace");
        Path secret = Files.writeString(root.getParent().resolve("secret.txt"), "private");
        WorkspaceContext ctx = new WorkspaceContext(List.of(root));
        assertThrows(SecurityException.class, () -> ctx.resolveForRead("../secret.txt"));
        assertThrows(SecurityException.class, () -> ctx.resolveForRead("../missing.txt"));
        assertThrows(SecurityException.class, () -> ctx.resolve("../secret.txt"));
        assertEquals("private", Files.readString(secret));
    }

    @Test
    public void missingPathsInsideWorkspaceRemainValid() throws Exception {
        Path root = directory("workspace");
        WorkspaceContext ctx = new WorkspaceContext(List.of(root));
        Path expected = root.resolve("new/nested/file.txt");
        assertEquals(expected, ctx.resolve("new/nested/file.txt"));
        assertEquals(expected, ctx.resolveForRead("new/nested/file.txt"));
        assertFalse(ctx.isAccessible(expected));
    }

    @Test
    public void deniesExternalFileAndDirectoryLinksForReadsAndWrites() throws Exception {
        Path root = directory("workspace");
        Path outside = directory("outside");
        Files.writeString(outside.resolve("secret.txt"), "private");
        Files.createSymbolicLink(root.resolve("external"), outside);
        Files.createSymbolicLink(root.resolve("secret.txt"), outside.resolve("secret.txt"));
        WorkspaceContext ctx = new WorkspaceContext(List.of(root));
        for (String path : List.of("external/secret.txt", "secret.txt", "external/new/nested.txt")) {
            assertThrows(path, SecurityException.class, () -> ctx.resolveForRead(path));
            assertThrows(path, SecurityException.class, () -> ctx.resolve(path));
            assertThrows(path, SecurityException.class, () -> ctx.resolveForRead(root.resolve(path).toString()));
        }
        assertFalse(ctx.isAccessible(root.resolve("secret.txt")));
    }

    @Test
    public void permitsLinksWhoseRealTargetsAreWithinWorkspace() throws Exception {
        Path root = directory("workspace");
        Path target = Files.createDirectories(root.resolve("actual"));
        Files.writeString(target.resolve("file.txt"), "allowed");
        Files.createSymbolicLink(root.resolve("alias"), target);
        WorkspaceContext ctx = new WorkspaceContext(List.of(root));
        assertEquals(target.resolve("file.txt"), ctx.resolveForRead("alias/file.txt"));
        assertEquals(target.resolve("new/file.txt"), ctx.resolve("alias/new/file.txt"));
        assertTrue(ctx.isAccessible(Path.of("alias/file.txt")));
    }

    @Test
    public void resolvesDotDotAfterFollowingSymlinks() throws Exception {
        Path root = directory("workspace");
        Path outside = directory("outside/nested");
        Files.writeString(outside.getParent().resolve("secret.txt"), "private");
        Files.createSymbolicLink(root.resolve("alias"), outside);
        WorkspaceContext ctx = new WorkspaceContext(List.of(root));
        assertThrows(SecurityException.class, () -> ctx.resolveForRead("alias/../secret.txt"));
        assertThrows(SecurityException.class, () -> ctx.resolve("alias/../new.txt"));
    }

    @Test
    public void danglingAndCyclicLinksFailClosed() throws Exception {
        Path root = directory("workspace");
        Files.createSymbolicLink(root.resolve("dangling"), root.resolve("missing"));
        Files.createSymbolicLink(root.resolve("cycle"), root.resolve("cycle"));
        WorkspaceContext ctx = new WorkspaceContext(List.of(root));
        for (String path : List.of("dangling", "dangling/new.txt", "cycle", "cycle/new.txt")) {
            assertThrows(path, SecurityException.class, () -> ctx.resolve(path));
            assertThrows(path, SecurityException.class, () -> ctx.resolveForRead(path));
        }
    }

    @Test
    public void supportsMultipleRootsAndRuntimeGrants() throws Exception {
        Path root = directory("primary");
        Path second = directory("secondary");
        Files.writeString(second.resolve("file.txt"), "second");
        WorkspaceContext ctx = new WorkspaceContext(List.of(root));
        assertThrows(SecurityException.class, () -> ctx.resolve(second.resolve("file.txt").toString()));
        ctx.addRoot(second);
        assertEquals(second.resolve("file.txt"), ctx.resolveForRead("file.txt"));
        assertEquals(second.resolve("file.txt"), ctx.resolve("../secondary/file.txt"));
        assertEquals(root.resolve("new.txt"), ctx.resolve("new.txt"));
        Files.createSymbolicLink(root.resolve("second"), second);
        assertEquals(second.resolve("file.txt"), ctx.resolveForRead("second/file.txt"));
    }

    @Test
    public void supportsSymlinkWorkspaceRootAndPinsItsGrant() throws Exception {
        Path root = directory("actual");
        Path outside = directory("outside");
        Path alias = temporary.getRoot().toPath().resolve("alias");
        Files.createSymbolicLink(alias, root);
        WorkspaceContext ctx = new WorkspaceContext(List.of(alias));
        assertEquals(root.resolve("new.txt"), ctx.resolve("new.txt"));
        assertEquals("new.txt", ctx.displayPath(root.resolve("new.txt")));
        Files.delete(alias);
        Files.createSymbolicLink(alias, outside);
        assertThrows(SecurityException.class, () -> ctx.resolve("new.txt"));
        assertThrows(SecurityException.class, () -> ctx.resolveForRead("."));
    }

    @Test
    public void systemTempReadExceptionDoesNotGrantWritesOrRelativeEscapes() throws Exception {
        Path root = directory("workspace");
        Path output = Files.writeString(temporary.getRoot().toPath().resolve("output.txt"), "output");
        WorkspaceContext ctx = new WorkspaceContext(List.of(root));
        // This temp file is an explicit absolute read exception, like overflow output.
        assertEquals(output.toRealPath(), ctx.resolveForRead(output.toString()));
        assertThrows(SecurityException.class, () -> ctx.resolve(output.toString()));
        assertThrows(SecurityException.class, () -> ctx.resolveForRead("../output.txt"));
        assertFalse(ctx.isAccessible(output));
    }

    @Test
    public void absoluteTempReadExceptionCannotFollowLinkToUnpermittedLocation() throws Exception {
        Path root = directory("workspace");
        Path link = temporary.getRoot().toPath().resolve("system-link");
        Files.createSymbolicLink(link, Path.of("/"));
        WorkspaceContext ctx = new WorkspaceContext(List.of(root));
        assertThrows(SecurityException.class, () -> ctx.resolveForRead(link.toString()));
    }

    @Test
    public void rejectsFileAncestorsForNewPaths() throws Exception {
        Path root = directory("workspace");
        Files.writeString(root.resolve("file"), "text");
        WorkspaceContext ctx = new WorkspaceContext(List.of(root));
        assertThrows(SecurityException.class, () -> ctx.resolve("file/child.txt"));
    }
}
