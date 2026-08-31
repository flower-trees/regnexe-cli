package org.salt.regnexe.cli.tools;

import org.salt.jlangchain.rag.tools.Tool;
import org.salt.regnexe.cli.ui.CliRenderer;
import org.salt.regnexe.cli.ui.ConfirmChoice;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * File-system tools for the agent.
 * All paths are validated against WorkspaceContext to prevent escapes.
 */
public class FileTools {

    private static final int MAX_READ_CHARS  = 8_000;
    private static final int MAX_SEARCH_HITS = 50;

    private static final int DEFAULT_READ_LINES = 200;

    /**
     * Builds a minimal JSON Schema {@code Map} for {@link Tool#getParametersSchema()} — same
     * purpose as {@code McpTools.asSchemaMap}, just hand-built here since these are code-first
     * tools with no MCP {@code inputSchema} to pass through. Needed because {@code
     * McpAgentExecutor.buildSchema(String)} (the fallback used when {@code parametersSchema} is
     * unset) marks every param listed in {@link Tool#getParams()} required, with no way to say
     * otherwise — real problem, not hypothetical: {@code read_file}'s {@code offset}/{@code limit}
     * and {@code search_files}'s {@code path} are genuinely optional (both have real defaults
     * right below), but were being forced into every tool call regardless.
     */
    private static Map<String, Object> schema(String[] required, Object... propNameType) {
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        for (int i = 0; i < propNameType.length; i += 2) {
            properties.put((String) propNameType[i],
                    Map.of("type", (String) propNameType[i + 1]));
        }
        return Map.of("type", "object", "properties", properties, "required", List.of(required));
    }

    public static Tool readFile(WorkspaceContext ctx) {
        return Tool.builder()
                .name("read_file")
                .description(
                    "Read the content of a file with line numbers. " +
                    "offset: 1-based line number to start from (default 1). " +
                    "limit: max lines to return (default " + DEFAULT_READ_LINES + "). " +
                    "The header shows total line count so you know whether to paginate. " +
                    "Output is capped at " + MAX_READ_CHARS + " characters as a safety limit.")
                .params("path: String, offset: Integer, limit: Integer")
                .parametersSchema(schema(new String[]{"path"},
                        "path", "string", "offset", "integer", "limit", "integer"))
                .func(raw -> {
                    Map<String, Object> args = toMap(raw);
                    String pathStr = str(args, "path");
                    int startLine = intOpt(args, "offset", 1);
                    int maxLines  = intOpt(args, "limit",  DEFAULT_READ_LINES);
                    if (startLine < 1) startLine = 1;
                    if (maxLines  < 1) maxLines  = DEFAULT_READ_LINES;

                    Path file = ctx.resolveForRead(pathStr);
                    if (!Files.exists(file)) return "Error: file not found: " + pathStr;
                    if (Files.isDirectory(file)) return "Error: path is a directory, use list_files instead";
                    try {
                        String content = Files.readString(file);
                        String[] lines = content.split("\n", -1);
                        int totalLines = lines.length;
                        int from = startLine - 1;                          // inclusive, 0-based
                        int to   = Math.min(totalLines, from + maxLines);  // exclusive

                        StringBuilder sb = new StringBuilder();
                        sb.append("File: ").append(ctx.displayPath(file))
                          .append("  [").append(totalLines).append(" lines total");
                        if (from > 0 || to < totalLines) {
                            sb.append(", showing ").append(from + 1).append("–").append(to);
                        }
                        sb.append("]\n");
                        sb.append("─".repeat(40)).append("\n");

                        int charCount = 0;
                        boolean charTruncated = false;
                        for (int i = from; i < to; i++) {
                            String formatted = String.format("%4d  %s\n", i + 1, lines[i]);
                            if (charCount + formatted.length() > MAX_READ_CHARS) {
                                sb.append("\n[... char limit reached at line ").append(i + 1)
                                  .append(", use offset=").append(i + 1).append(" to continue ...]");
                                charTruncated = true;
                                break;
                            }
                            sb.append(formatted);
                            charCount += formatted.length();
                        }
                        if (!charTruncated && to < totalLines) {
                            sb.append("\n[... ").append(totalLines - to)
                              .append(" more lines, use offset=").append(to + 1).append(" to continue ...]");
                        }
                        return sb.toString();
                    } catch (IOException e) {
                        return "Error reading file: " + e.getMessage();
                    }
                })
                .build();
    }

    public static Tool listFiles(WorkspaceContext ctx) {
        return Tool.builder()
                .name("list_files")
                .description(
                    "List files and directories at the given path. " +
                    "Pass '.' or empty string to list the workspace root. " +
                    "Shows file sizes and marks directories with '/'.")
                .params("path: String")
                .func(raw -> {
                    Map<String, Object> args = toMap(raw);
                    String pathStr = str(args, "path");
                    Path dir = ctx.resolveForRead(pathStr.isBlank() ? "." : pathStr);
                    if (!Files.exists(dir)) return "Error: path not found: " + pathStr;
                    if (!Files.isDirectory(dir)) return "Error: not a directory, use read_file instead";
                    try {
                        List<String> entries = new ArrayList<>();
                        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                            for (Path entry : stream) {
                                String name = entry.getFileName().toString();
                                if (Files.isDirectory(entry)) {
                                    entries.add("  " + name + "/");
                                } else {
                                    long size = Files.size(entry);
                                    entries.add(String.format("  %-40s %s", name, humanSize(size)));
                                }
                            }
                        }
                        entries.sort(String.CASE_INSENSITIVE_ORDER);
                        StringBuilder sb = new StringBuilder();
                        sb.append("Directory: ").append(ctx.displayPath(dir)).append("\n");
                        sb.append("─".repeat(40)).append("\n");
                        entries.forEach(e -> sb.append(e).append("\n"));
                        sb.append("\n").append(entries.size()).append(" entries");
                        return sb.toString();
                    } catch (IOException e) {
                        return "Error listing directory: " + e.getMessage();
                    }
                })
                .build();
    }

    public static Tool searchFiles(WorkspaceContext ctx) {
        return Tool.builder()
                .name("search_files")
                .description(
                    "Search for a text pattern in files recursively. " +
                    "pattern: the text to search for (case-insensitive). " +
                    "path: the directory to search in (use '.' for workspace root). " +
                    "Returns matching lines with file path and line number. " +
                    "Results are limited to " + MAX_SEARCH_HITS + " matches.")
                .params("pattern: String, path: String")
                .parametersSchema(schema(new String[]{"pattern"},
                        "pattern", "string", "path", "string"))
                .func(raw -> {
                    Map<String, Object> args = toMap(raw);
                    String pattern = str(args, "pattern");
                    String pathStr = str(args, "path");
                    if (pattern.isBlank()) return "Error: pattern is required";
                    Path searchRoot = ctx.resolveForRead(pathStr.isBlank() ? "." : pathStr);
                    if (!Files.exists(searchRoot)) return "Error: path not found: " + pathStr;

                    String lowerPattern = pattern.toLowerCase();
                    List<String> hits = new ArrayList<>();
                    try {
                        Files.walk(searchRoot)
                                .filter(Files::isRegularFile)
                                .filter(p -> isBinaryUnlikely(p))
                                .forEach(file -> {
                                    if (hits.size() >= MAX_SEARCH_HITS) return;
                                    try {
                                        List<String> lines = Files.readAllLines(file);
                                        for (int i = 0; i < lines.size(); i++) {
                                            if (hits.size() >= MAX_SEARCH_HITS) break;
                                            if (lines.get(i).toLowerCase().contains(lowerPattern)) {
                                                hits.add(String.format("%s:%d: %s",
                                                        ctx.displayPath(file), i + 1, lines.get(i).trim()));
                                            }
                                        }
                                    } catch (Exception ignored) {}
                                });
                    } catch (IOException e) {
                        return "Error searching: " + e.getMessage();
                    }
                    if (hits.isEmpty()) return "No matches found for: " + pattern;
                    StringBuilder sb = new StringBuilder();
                    sb.append("Search: \"").append(pattern).append("\" in ").append(ctx.displayPath(searchRoot)).append("\n");
                    sb.append("─".repeat(40)).append("\n");
                    hits.forEach(h -> sb.append(h).append("\n"));
                    if (hits.size() >= MAX_SEARCH_HITS) sb.append("\n[results limited to ").append(MAX_SEARCH_HITS).append(" matches]");
                    return sb.toString();
                })
                .build();
    }

    public static Tool writeFile(WorkspaceContext ctx, CliRenderer renderer, Runnable pauseAction) {
        return Tool.builder()
                .name("write_file")
                .description(
                    "Write content to a file, creating it if it does not exist or overwriting it. " +
                    "Always asks the user for confirmation before writing. " +
                    "Use for creating new files or completely replacing existing ones. " +
                    "For targeted edits to existing files, prefer edit_file instead.")
                .params("path: String, content: String")
                .func(raw -> {
                    Map<String, Object> args = toMap(raw);
                    String pathStr = str(args, "path");
                    String content = str(args, "content");
                    if (pathStr.isBlank()) return "Error: path is required";
                    Path file;
                    try {
                        file = ctx.resolve(pathStr);
                    } catch (SecurityException e) {
                        return "Error: " + e.getMessage();
                    }
                    boolean exists = Files.exists(file);
                    renderer.filePreview(ctx.displayPath(file), content, !exists);
                    ConfirmChoice choice = renderer.confirm("apply", "write_file");
                    if (choice == ConfirmChoice.PAUSE) {
                        if (pauseAction != null) pauseAction.run();
                        return "Task paused by user.";
                    }
                    if (choice != ConfirmChoice.YES && choice != ConfirmChoice.ALWAYS) {
                        return "Write cancelled by user.";
                    }
                    try {
                        Files.createDirectories(file.getParent());
                        Files.writeString(file, content);
                        int lineCount = content.split("\n", -1).length;
                        return "Written: " + ctx.displayPath(file) + " (" + lineCount + " lines)";
                    } catch (IOException e) {
                        return "Error writing file: " + e.getMessage();
                    }
                })
                .build();
    }

    public static Tool editFile(WorkspaceContext ctx, CliRenderer renderer, Runnable pauseAction) {
        return Tool.builder()
                .name("edit_file")
                .description(
                    "Make a targeted edit to an existing file by replacing an exact string with new text. " +
                    "old_string must match exactly (including whitespace and indentation) and must be unique in the file. " +
                    "Shows a diff preview and asks for confirmation before applying. " +
                    "For replacing the entire file content use write_file instead.")
                .params("path: String, old_string: String, new_string: String")
                .func(raw -> {
                    Map<String, Object> args = toMap(raw);
                    String pathStr   = str(args, "path");
                    String oldString = str(args, "old_string");
                    String newString = str(args, "new_string");
                    if (pathStr.isBlank())   return "Error: path is required";
                    if (oldString.isEmpty()) return "Error: old_string is required";
                    Path file;
                    try {
                        file = ctx.resolve(pathStr);
                    } catch (SecurityException e) {
                        return "Error: " + e.getMessage();
                    }
                    if (!Files.exists(file)) return "Error: file not found: " + pathStr;
                    if (Files.isDirectory(file)) return "Error: path is a directory";
                    String current;
                    try {
                        current = Files.readString(file);
                    } catch (IOException e) {
                        return "Error reading file: " + e.getMessage();
                    }
                    int idx = current.indexOf(oldString);
                    if (idx < 0) return "Error: old_string not found in " + ctx.displayPath(file);
                    if (current.indexOf(oldString, idx + 1) >= 0) {
                        return "Error: old_string matches multiple locations — make it more specific";
                    }
                    String updated = current.substring(0, idx) + newString + current.substring(idx + oldString.length());
                    renderer.editPreview(ctx.displayPath(file), oldString, newString);
                    ConfirmChoice choice = renderer.confirm("apply", "edit_file");
                    if (choice == ConfirmChoice.PAUSE) {
                        if (pauseAction != null) pauseAction.run();
                        return "Task paused by user.";
                    }
                    if (choice != ConfirmChoice.YES && choice != ConfirmChoice.ALWAYS) {
                        return "Edit cancelled by user.";
                    }
                    try {
                        Files.writeString(file, updated);
                        return "Applied edit to " + ctx.displayPath(file);
                    } catch (IOException e) {
                        return "Error writing file: " + e.getMessage();
                    }
                })
                .build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(Object args) {
        return args instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String str(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v != null ? v.toString().trim() : "";
    }

    private static int intOpt(Map<String, Object> args, String key, int defaultValue) {
        Object v = args.get(key);
        if (v == null) return defaultValue;
        try { return Integer.parseInt(v.toString().trim()); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / (1024.0 * 1024));
    }

    /** Heuristic: skip likely binary files by extension. */
    private static boolean isBinaryUnlikely(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return !name.endsWith(".class") && !name.endsWith(".jar") && !name.endsWith(".png")
                && !name.endsWith(".jpg") && !name.endsWith(".gif") && !name.endsWith(".zip")
                && !name.endsWith(".gz")  && !name.endsWith(".pdf") && !name.endsWith(".exe")
                && !name.endsWith(".so")  && !name.endsWith(".dylib");
    }
}
