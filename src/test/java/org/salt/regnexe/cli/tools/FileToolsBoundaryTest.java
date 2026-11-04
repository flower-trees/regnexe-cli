package org.salt.regnexe.cli.tools;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.salt.jlangchain.rag.tools.Tool;
import org.salt.regnexe.cli.config.RexConfig;
import org.salt.regnexe.cli.ui.CliRenderer;
import org.salt.regnexe.cli.ui.ConfirmChoice;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class FileToolsBoundaryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private String call(Tool tool, Map<String, Object> args) {
        return String.valueOf(tool.getFunc().apply(args));
    }

    private CliRenderer renderer(Runnable onConfirm) {
        return (CliRenderer) Proxy.newProxyInstance(CliRenderer.class.getClassLoader(),
                new Class<?>[]{CliRenderer.class}, (proxy, method, args) -> {
                    if (method.getName().equals("confirm")) {
                        onConfirm.run();
                        return ConfirmChoice.YES;
                    }
                    return null;
                });
    }

    @Test
    public void readListAndSearchReturnBoundaryErrors() throws Exception {
        Path root = temporary.newFolder("workspace").toPath();
        Files.writeString(root.getParent().resolve("secret.txt"), "private");
        WorkspaceContext ctx = new WorkspaceContext(List.of(root));
        assertTrue(call(FileTools.readFile(ctx), Map.of("path", "../secret.txt")).startsWith("Error:"));
        assertTrue(call(FileTools.listFiles(ctx), Map.of("path", "..")).startsWith("Error:"));
        assertTrue(call(FileTools.searchFiles(ctx), Map.of("path", "..", "pattern", "private"))
                .startsWith("Error:"));
    }

    @Test
    public void recursiveSearchSkipsExternalFileSymlinks() throws Exception {
        Path root = temporary.newFolder("workspace").toPath();
        Path outside = temporary.newFolder("outside").toPath();
        Path secret = Files.writeString(outside.resolve("secret.txt"), "private-marker");
        Files.createSymbolicLink(root.resolve("linked.txt"), secret);
        Files.createSymbolicLink(root.resolve("external"), outside);
        Files.writeString(root.resolve("local.txt"), "private-marker local");
        WorkspaceContext ctx = new WorkspaceContext(List.of(root));
        String result = call(FileTools.searchFiles(ctx), Map.of("pattern", "private-marker"));
        assertTrue(result, result.contains("local.txt"));
        assertFalse(result, result.contains("linked.txt"));
        assertFalse(result, result.contains("secret.txt"));
        assertTrue(call(FileTools.listFiles(ctx), Map.of("path", ".")).contains("linked.txt@"));
    }

    @Test
    public void writesAndEditsRejectExternalLinksWithoutChangingTarget() throws Exception {
        Path root = temporary.newFolder("workspace").toPath();
        Path target = Files.writeString(root.getParent().resolve("secret.txt"), "private");
        Files.createSymbolicLink(root.resolve("linked.txt"), target);
        WorkspaceContext ctx = new WorkspaceContext(List.of(root));
        CliRenderer renderer = renderer(() -> fail("must reject before preview confirmation"));
        RexConfig.ToolsConfig.FsConfig config = new RexConfig.ToolsConfig.FsConfig();
        assertTrue(call(FileTools.writeFile(ctx, renderer, null, config),
                Map.of("path", "linked.txt", "content", "changed")).startsWith("Error:"));
        assertTrue(call(FileTools.editFile(ctx, renderer, null, config),
                Map.of("path", "linked.txt", "old_string", "private", "new_string", "changed"))
                .startsWith("Error:"));
        assertEquals("private", Files.readString(target));
    }

    @Test
    public void writeRevalidatesParentAfterConfirmation() throws Exception {
        Path root = temporary.newFolder("workspace").toPath();
        Path target = Files.createDirectories(root.resolve("target"));
        Path outside = temporary.newFolder("outside").toPath();
        Path alias = Files.createSymbolicLink(root.resolve("alias"), target);
        WorkspaceContext ctx = new WorkspaceContext(List.of(root));
        CliRenderer renderer = renderer(() -> {
            try {
                Files.delete(alias);
                Files.createSymbolicLink(alias, outside);
            } catch (Exception e) { throw new AssertionError(e); }
        });
        String result = call(FileTools.writeFile(ctx, renderer, null, new RexConfig.ToolsConfig.FsConfig()),
                Map.of("path", "alias/new/nested.txt", "content", "changed"));
        assertTrue(result, result.startsWith("Error writing file: Path resolves outside workspace:"));
        assertFalse(Files.exists(outside.resolve("new")));
        assertFalse(Files.exists(target.resolve("new")));
    }

    @Test
    public void editRejectsTargetChangedToAnotherPermittedFileDuringConfirmation() throws Exception {
        Path root = temporary.newFolder("workspace").toPath();
        Path first = Files.writeString(root.resolve("first.txt"), "original");
        Path second = Files.writeString(root.resolve("second.txt"), "other");
        Path alias = Files.createSymbolicLink(root.resolve("alias.txt"), first);
        WorkspaceContext ctx = new WorkspaceContext(List.of(root));
        CliRenderer renderer = renderer(() -> {
            try {
                Files.delete(alias);
                Files.createSymbolicLink(alias, second);
            } catch (Exception e) { throw new AssertionError(e); }
        });
        String result = call(FileTools.editFile(ctx, renderer, null, new RexConfig.ToolsConfig.FsConfig()),
                Map.of("path", "alias.txt", "old_string", "original", "new_string", "changed"));
        assertTrue(result, result.contains("target changed"));
        assertEquals("original", Files.readString(first));
        assertEquals("other", Files.readString(second));
    }

    @Test
    public void permitsNormalCreateAndEditThroughInternalLink() throws Exception {
        Path root = temporary.newFolder("workspace").toPath();
        Path target = Files.createDirectories(root.resolve("target"));
        Files.createSymbolicLink(root.resolve("alias"), target);
        WorkspaceContext ctx = new WorkspaceContext(List.of(root));
        CliRenderer renderer = renderer(() -> {});
        RexConfig.ToolsConfig.FsConfig config = new RexConfig.ToolsConfig.FsConfig();
        assertTrue(call(FileTools.writeFile(ctx, renderer, null, config),
                Map.of("path", "alias/new/file.txt", "content", "original")).startsWith("Written:"));
        assertTrue(call(FileTools.editFile(ctx, renderer, null, config),
                Map.of("path", "alias/new/file.txt", "old_string", "original", "new_string", "changed"))
                .startsWith("Applied edit"));
        assertEquals("changed", Files.readString(target.resolve("new/file.txt")));
    }
}
