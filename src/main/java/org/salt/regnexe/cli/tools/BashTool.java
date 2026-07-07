package org.salt.regnexe.cli.tools;

import org.jline.terminal.Terminal;
import org.salt.jlangchain.rag.tools.Tool;
import org.salt.regnexe.cli.config.RexConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class BashTool {

    private static final int MAX_OUTPUT_CHARS = 10_000;
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
                            Terminal terminal) {
        return Tool.builder()
                .name("bash")
                .description(
                    "Execute a shell command in the workspace directory. " +
                    "stdout and stderr are captured together and returned. " +
                    "The command runs with a " + TIMEOUT_SECONDS + "-second timeout. " +
                    "Output is capped at " + MAX_OUTPUT_CHARS + " characters. " +
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

                    PrintWriter out = terminal.writer();
                    out.println();
                    out.println("  $ " + command);
                    out.println("  " + "─".repeat(50));

                    boolean needsConfirm = config.isRequireConfirmation() && !isReadOnly(command);
                    if (needsConfirm) {
                        out.print("  Execute? [y/N] ");
                        out.flush();
                        try {
                            int ch = terminal.reader().read();
                            out.println();
                            out.flush();
                            if (ch != 'y' && ch != 'Y') return "Command cancelled by user.";
                        } catch (IOException e) {
                            return "Error reading confirmation: " + e.getMessage();
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
                        boolean truncated = false;

                        try (InputStream is = proc.getInputStream()) {
                            byte[] buf = new byte[4096];
                            int read;
                            while ((read = is.read(buf)) != -1) {
                                if (!truncated) {
                                    String chunk = new String(buf, 0, read, StandardCharsets.UTF_8);
                                    if (output.length() + chunk.length() <= MAX_OUTPUT_CHARS) {
                                        output.append(chunk);
                                    } else {
                                        int space = MAX_OUTPUT_CHARS - output.length();
                                        if (space > 0) output.append(chunk, 0, space);
                                        truncated = true;
                                    }
                                }
                                // keep draining even after truncation so the process doesn't block
                            }
                        }

                        boolean finished = proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        if (!finished) {
                            proc.destroyForcibly();
                            String partial = output.isEmpty() ? "" : "\nOutput so far:\n" + output;
                            return "Error: timed out after " + TIMEOUT_SECONDS + "s" + partial;
                        }

                        int exit = proc.exitValue();
                        StringBuilder result = new StringBuilder();
                        result.append("Exit: ").append(exit).append("\n");
                        if (!output.isEmpty()) {
                            result.append("─".repeat(40)).append("\n");
                            result.append(output);
                        }
                        if (truncated) {
                            result.append("\n[... output truncated at ").append(MAX_OUTPUT_CHARS).append(" chars ...]");
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
        // Reject if any write signal is present
        return WRITE_SIGNALS.stream().noneMatch(command::contains);
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
