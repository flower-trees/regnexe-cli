package org.salt.regnexe.cli.tools;

import org.jline.terminal.Terminal;
import org.salt.jlangchain.rag.tools.Tool;
import org.salt.regnexe.cli.config.RexConfig;
import org.salt.regnexe.cli.ui.ConfirmChoice;
import org.salt.regnexe.cli.ui.CliRenderer;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BashTool {

    // Hard ceiling on what gets captured/saved at all — protects memory/disk from a truly
    // runaway command (a stuck loop spewing gigabytes), not the normal case. Well above
    // ToolOutputOverflow.MAX_INLINE_CHARS, which is the much smaller threshold that actually
    // decides whether output goes inline or to a tmp file — see capOrOffload() below.
    private static final int MAX_CAPTURE_CHARS = 200_000;
    private static final int TIMEOUT_SECONDS  = 30;

    // Commands that start with these prefixes are treated as read-only (no confirmation needed).
    private static final List<String> READ_ONLY_PREFIXES = List.of(
        "ls", "find", "grep", "egrep", "fgrep", "rg",
        "cat", "head", "tail", "wc", "stat", "file",
        "echo", "printf", "pwd", "which", "type", "env",
        "diff", "diff3",
        "git log", "git diff", "git status", "git show", "git blame",
        "git branch", "git remote", "git stash list", "git tag",
        "mvn compile", "mvn test-compile",
        "mvn dependency:tree", "mvn dependency:list",
        "java -version", "javac -version",
        "node --version", "npm list", "yarn list"
    );

    // Signals that a command has side effects even if it starts with a read-only prefix.
    private static final List<String> WRITE_SIGNALS = List.of(
        ">", ">>", "| tee", "|tee", "rm ", "rm\t", "mv ", "mv\t",
        "cp ", "mkdir", "touch ", "chmod", "chown", "kill ", "pkill",
        "curl ", "wget "
    );

    // Matches N>/dev/null and >/dev/null (stderr/stdout suppression) — not a write operation.
    private static final Pattern NULL_REDIRECT = Pattern.compile("\\d*>\\s*/dev/null");

    // Heuristic workspace-boundary check, same spirit/rigor as ALWAYS_BLOCKED/extra_blocked
    // above: a substring/regex check, not a real OS-level sandbox — a determined adversarial
    // prompt could still route around it (e.g. a path hidden inside a Python string literal
    // rather than a bare shell token). It exists to catch an honest-but-confused model wandering
    // outside the project, which is the actual failure mode observed twice in real use: `cd` to
    // an unrelated sibling project directory, and `find /` turning up an unrelated task's leftover
    // files elsewhere on disk. Unlike read_file/list_files (which already enforce this via
    // WorkspaceContext.resolve()), bash had no boundary at all before this.
    //
    // Two real false positives, caught live blocking a Chinese-language HTML-writing skill:
    //  1. Java's \w is ASCII-only by default, so "天工/智身" (Chinese text using "/" as an "or"
    //     separator — completely ordinary Chinese punctuation) reads as "not preceded by a word
    //     char", making the "/" look like a fresh absolute-path start. Same problem for "/" inside
    //     any non-ASCII (Japanese/Korean/etc.) text. Pattern.UNICODE_CHARACTER_CLASS makes \w
    //     Unicode-aware so CJK letters count as word chars in both the lookbehind and the
    //     token-continuation class.
    //  2. "</h2>" (or any HTML/XML closing tag) has its "<" immediately before the "/", and "<"
    //     was never excluded by the lookbehind, so ordinary article HTML ("<h2>...</h2>") reads
    //     as an absolute path "/h2". Added "<" to the excluded set.
    private static final Pattern ABS_PATH_TOKEN =
            Pattern.compile("(?<![\\w./:<-])(/[\\w./-]*|~(?:/[\\w./-]*)?)", Pattern.UNICODE_CHARACTER_CLASS);

    // Absolute paths under these prefixes are left alone even though they're outside the
    // workspace: standard system/tool locations (needed for things like `2>/dev/null` or a shell
    // resolving `/usr/bin/env`) and scratch temp dirs (writing a throwaway file to /tmp is normal
    // and not itself the problem — the problem is treating something found by searching *all* of
    // / as if it were part of this project). Shared with WorkspaceContext.resolveForRead() — same
    // list, so read_file can retrieve a file this list lets bash reference (e.g. a
    // ToolOutputOverflow pointer) without a second, drifting copy of the prefixes.

    /** Returns the first out-of-workspace absolute path token found, or null if the command is clean. */
    private static String findWorkspaceEscape(String command, WorkspaceContext workspace) {
        Matcher m = ABS_PATH_TOKEN.matcher(command);
        while (m.find()) {
            String token = m.group(1);
            // "~" resolves to $HOME (a shell expansion), which WorkspaceContext.resolve() can't
            // see: Path.of("~") isn't absolute, so it would otherwise be silently (and wrongly)
            // treated as a relative path inside the workspace. The workspace root is always some
            // subdirectory of home, never home itself, so any ~-prefixed token is an escape.
            if (token.equals("~") || token.startsWith("~/")) {
                return token;
            }
            if (WorkspaceContext.SAFE_READ_PREFIXES.stream().anyMatch(token::startsWith)) continue;
            try {
                workspace.resolve(token);
            } catch (SecurityException e) {
                return token;
            }
        }
        return null;
    }

    private static final List<String> ALWAYS_BLOCKED = List.of(
        "rm -rf /",
        "rm -fr /",
        ":(){ :|:& };:",
        "mkfs",
        "fdisk",
        "dd if=/dev/",
        "> /dev/sd",
        "chmod -R 777 /",
        "shutdown",
        "reboot",
        "halt",
        "poweroff"
    );

    public static Tool bash(WorkspaceContext workspace,
                            RexConfig.ToolsConfig.BashConfig config,
                            Terminal terminal,
                            CliRenderer renderer,
                            Runnable pauseAction) {
        return Tool.builder()
                .name("bash")
                .description(
                    "Execute a shell command in the workspace directory. " +
                    "stdout and stderr are captured together and returned. " +
                    "The command runs with a " + TIMEOUT_SECONDS + "-second timeout. " +
                    "Output beyond " + ToolOutputOverflow.MAX_INLINE_CHARS + " characters is saved to " +
                    "a tmp file (read_file/bash can read the rest) rather than lost. " +
                    "Destructive system commands are blocked.")
                .params("command: String")
                .func(raw -> {
                    Map<String, Object> args = toMap(raw);
                    String command = str(args, "command");
                    if (command.isBlank()) return "Error: command is required";

                    // Safety check
                    String lower = command.toLowerCase();
                    for (String blocked : ALWAYS_BLOCKED) {
                        if (lower.contains(blocked.toLowerCase())) {
                            return "Error: command blocked (contains \"" + blocked + "\")";
                        }
                    }
                    List<String> extra = config.getExtraBlocked();
                    if (extra != null) {
                        for (String blocked : extra) {
                            if (!blocked.isBlank() && lower.contains(blocked.toLowerCase())) {
                                return "Error: command blocked by config (contains \"" + blocked + "\")";
                            }
                        }
                    }
                    String escapedPath = findWorkspaceEscape(command, workspace);
                    if (escapedPath != null) {
                        return "Error: command references a path outside the workspace (" + escapedPath
                                + "). Stay within " + workspace.primaryRoot()
                                + " — if you genuinely need something outside it, tell the user instead of reaching for it directly.";
                    }

                    PrintWriter out = terminal.writer();
                    renderer.bashCommand(command);

                    boolean needsConfirm = config.isRequireConfirmation() && !isReadOnly(command);
                    if (needsConfirm) {
                        ConfirmChoice choice = renderer.confirm("run", "bash");
                        if (choice == ConfirmChoice.PAUSE) {
                            if (pauseAction != null) pauseAction.run();
                            return "Task paused by user.";
                        }
                        if (choice != ConfirmChoice.YES && choice != ConfirmChoice.ALWAYS) {
                            return "Command cancelled by user.";
                        }
                    } else {
                        out.flush();
                    }

                    try {
                        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", command);
                        pb.directory(workspace.primaryRoot().toFile());
                        pb.redirectErrorStream(true);
                        Process proc = pb.start();

                        StringBuilder output = new StringBuilder();
                        boolean atCaptureCeiling = false;

                        try (InputStream is = proc.getInputStream()) {
                            byte[] buf = new byte[4096];
                            int read;
                            while ((read = is.read(buf)) != -1) {
                                if (!atCaptureCeiling) {
                                    String chunk = new String(buf, 0, read, StandardCharsets.UTF_8);
                                    if (output.length() + chunk.length() <= MAX_CAPTURE_CHARS) {
                                        output.append(chunk);
                                    } else {
                                        int space = MAX_CAPTURE_CHARS - output.length();
                                        if (space > 0) output.append(chunk, 0, space);
                                        atCaptureCeiling = true;
                                    }
                                }
                                // keep draining even past the ceiling so the process doesn't block
                            }
                        }

                        boolean finished = proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        if (!finished) {
                            proc.destroyForcibly();
                            String partial = output.isEmpty() ? "" : "\nOutput so far:\n" + output;
                            return "Error: timed out after " + TIMEOUT_SECONDS + "s" + partial;
                        }

                        int exit = proc.exitValue();
                        String outputText = output.toString();
                        // Beyond MAX_INLINE_CHARS: preview + tmp-file pointer instead of losing the
                        // rest (see ToolOutputOverflow). atCaptureCeiling means even the saved copy
                        // was cut at MAX_CAPTURE_CHARS — the true output was larger still.
                        String shown = ToolOutputOverflow.capOrOffload(outputText, "bash");
                        StringBuilder result = new StringBuilder();
                        result.append("Exit: ").append(exit).append("\n");
                        if (!shown.isEmpty()) {
                            result.append("─".repeat(40)).append("\n");
                            result.append(shown);
                        }
                        if (atCaptureCeiling) {
                            result.append("\n[... command produced more than ").append(MAX_CAPTURE_CHARS)
                                  .append(" chars; even the saved copy was cut off there ...]");
                        }
                        return result.toString();

                    } catch (IOException | InterruptedException e) {
                        return "Error: " + e.getMessage();
                    }
                })
                .build();
    }

    private static boolean isReadOnly(String command) {
        String trimmed = command.trim().toLowerCase();
        boolean safePrefix = READ_ONLY_PREFIXES.stream()
                .anyMatch(p -> trimmed.equals(p) || trimmed.startsWith(p + " ") || trimmed.startsWith(p + "\t"));
        if (!safePrefix) return false;
        // Strip N>/dev/null redirects before checking write signals — they suppress output, not write files.
        String stripped = NULL_REDIRECT.matcher(command).replaceAll("");
        return WRITE_SIGNALS.stream().noneMatch(stripped::contains);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(Object args) {
        return args instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String str(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v != null ? v.toString().trim() : "";
    }
}
