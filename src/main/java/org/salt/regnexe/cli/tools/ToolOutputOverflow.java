package org.salt.regnexe.cli.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared "tool result too long" handling for bash/MCP tool output.
 *
 * Previously each tool head-cut its own output at a fixed char count and the excess was simply
 * gone — same class of information loss as the truncation this session removed from
 * Reflector/ExecutionRecordFormatter (see docs/design/09-context-memory-compaction-design.md).
 * This instead keeps a short inline preview and writes the FULL text to a system-tmp file, with a
 * pointer telling the model exactly how to read the rest if it decides it needs to — nothing is
 * silently dropped while the process is alive. See docs/design/10-tool-output-overflow-design.md
 * for the full design (why system tmp, why {@code CapabilityExecutor} couldn't do this, cleanup).
 *
 * <p>Deliberately NOT wired into {@code CapabilityExecutor.recordToolExecution()}: that hook
 * (j-langchain's {@code onObservation}) is a {@code Consumer<String>} — a passive callback that
 * cannot change what the model actually receives as the tool's result in the live loop. This has
 * to run inside each tool's own {@code .func()}, where the return value IS what both the model
 * and the bookkeeping hook see.
 */
public final class ToolOutputOverflow {

    /** Below this, text is returned unchanged — most tool results never need this at all. */
    public static final int MAX_INLINE_CHARS = 2000;

    private ToolOutputOverflow() {}

    /**
     * @param label short, filesystem-safe identifier for the tmp filename (e.g. "bash",
     *              "tavily_search") — used as-is, callers are expected to pass a safe constant or
     *              pre-sanitized value, not arbitrary user input.
     */
    public static String capOrOffload(String text, String label) {
        if (text == null || text.length() <= MAX_INLINE_CHARS) {
            return text;
        }
        try {
            Path tmp = Files.createTempFile("rex-" + label + "-", ".txt");
            // Best-effort cleanup on a clean process exit; the OS's own periodic tmp-directory
            // reaping is the backstop for kill -9 (see design doc — acceptable now that nothing
            // reads old tool output back across a process restart, since resume was removed).
            tmp.toFile().deleteOnExit();
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            return text.substring(0, MAX_INLINE_CHARS)
                    + "\n\n[... " + (text.length() - MAX_INLINE_CHARS) + " more chars omitted ("
                    + text.length() + " total). Full output saved to " + tmp
                    + " — read_file (paginated via offset/limit) or bash cat/grep it if you need more.]";
        } catch (IOException e) {
            // Can't even write the tmp file (disk full, permissions, ...) — fall back to plain
            // truncation rather than failing the whole tool call over a logging-adjacent problem.
            return text.substring(0, MAX_INLINE_CHARS)
                    + "\n\n[... output truncated at " + MAX_INLINE_CHARS + " chars (tmp file unavailable: "
                    + e.getMessage() + ") ...]";
        }
    }
}
