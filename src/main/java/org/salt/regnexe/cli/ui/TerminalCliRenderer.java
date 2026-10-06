package org.salt.regnexe.cli.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.salt.regnexe.cli.config.RexConfig;
import org.salt.regnexe.cli.session.SessionContext;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TerminalCliRenderer implements CliRenderer {

    private static final int MAX_TOOL_RESULT_LINES = 20;
    private static final int MAX_LINE_CHARS = 200;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Tools that already print their own dedicated preview (bashCommand/filePreview/editPreview)
    // before this call's TOOL_CALLED line renders — showing their args here too is pure
    // duplication. mcp_tool:* calls are excluded separately below (mcpToolPreview covers all of
    // them). Tools with no dedicated preview (read_file, list_files, search_files, a skill/
    // sub-agent's own direct invocation) still show their args here — it's the only place they
    // appear.
    private static final Set<String> HAS_DEDICATED_PREVIEW = Set.of("bash", "write_file", "edit_file");

    // CapabilityExecutor's event text is a flat, human-readable string by design (AgentEvent has
    // no structured per-tool-call fields), so these are parsed by convention rather than a real
    // schema — see formatToolCallLabel()/formatToolResult() in CapabilityExecutor for the producer
    // side. BashTool is the only producer of the "Exit: N" shape today.
    private static final Pattern BASH_EXIT_HEADER = Pattern.compile("\\AExit: (-?\\d+)\\n(─+\\n)?");

    private final Terminal terminal;
    private final PrintWriter out;
    private final ThemeConfig theme;
    private boolean thinkingLineActive = false;
    // Keys the user picked ALWAYS for — confirm() short-circuits to YES for these without
    // prompting again. Lives for the process's lifetime (survives agent rebuilds within one
    // REPL session, since this renderer instance is constructed once in CliMain.run()); never
    // persisted to disk.
    private final Set<String> alwaysAllowed = ConcurrentHashMap.newKeySet();

    public TerminalCliRenderer(Terminal terminal, ThemeConfig theme) {
        this.terminal = terminal;
        this.out = terminal.writer();
        this.theme = theme;
    }

    @Override
    public void startup(String version, SessionContext ctx, RexConfig config, String missingApiKeyEnv) {
        if (theme.theme() == CliTheme.CODEX) {
            out.printf("rex %s  %s  %s/%s%n",
                    version, ctx.getSessionName(), config.getModel().getVendor(), config.effectiveModel());
            List<Path> roots = ctx.getWorkspace().getRoots();
            if (roots.size() == 1) {
                out.printf("cwd %s%n", roots.get(0));
            } else {
                out.println("roots");
                roots.forEach(r -> out.printf("  %s%n", r));
            }
            if (missingApiKeyEnv != null) warning("No API key - set " + missingApiKeyEnv + " or api_key in ~/.rex/config.yml");
            out.println();
            out.flush();
            return;
        }

        out.printf("rex v%s  (type /help for commands, /exit to quit)%n", version);
        out.printf("Model: %s/%s%n", config.getModel().getVendor(), config.effectiveModel());
        if (missingApiKeyEnv != null) warning("No API key - set " + missingApiKeyEnv + " or api_key in ~/.rex/config.yml");
        ctx.getWorkspace().getRoots().forEach(r -> out.printf("Workspace: %s%n", r));
        out.printf("Session: %s%n%n", ctx.getSessionName());
        out.flush();
    }

    @Override
    public String prompt(SessionContext ctx) {
        if (theme.theme() == CliTheme.CODEX) {
            return theme.icons() ? "› " : "> ";
        }
        return "rex> ";
    }

    @Override
    public void thinking() {
        out.print(theme.theme() == CliTheme.CODEX
                ? (theme.icons() ? "⟳ thinking" : "thinking")
                : "  ⟳ Thinking...");
        out.flush();
        thinkingLineActive = true;
    }

    @Override
    public void ready() {
        if (thinkingLineActive) {
            out.print("\r" + " ".repeat(24) + "\r");
        }
        out.println(theme.theme() == CliTheme.CODEX
                ? (theme.icons() ? "✓ ready" : "ready")
                : "  ✓ Ready");
        out.flush();
        thinkingLineActive = false;
    }

    @Override
    public void executing() {
        clearThinkingLine();
        out.println(theme.theme() == CliTheme.CODEX
                ? (theme.icons() ? "⟳ executing" : "executing")
                : "  ⟳ Executing...");
        out.flush();
    }

    @Override
    public void toolCalled(String text) {
        clearThinkingLine();
        ToolCallParts parts = parseToolCall(text);
        StringBuilder display = new StringBuilder(parts.display());
        if (parts.scope() != null) display.append(' ').append(parts.scope());
        if (parts.showArgs()) display.append(' ').append(parts.args());
        if (theme.theme() == CliTheme.CODEX) {
            out.println();
            out.println("┌ " + display);
        } else {
            out.println("\n  ▶ " + display);
        }
        out.flush();
    }

    @Override
    public void toolResult(String text) {
        String observation = stripResultLabel(text);
        Matcher bashMatch = BASH_EXIT_HEADER.matcher(observation);
        String statusLine;
        String body;
        if (bashMatch.find()) {
            int exitCode = Integer.parseInt(bashMatch.group(1));
            statusLine = (exitCode == 0 ? green("✓") : red("✗")) + " exit " + exitCode;
            body = observation.substring(bashMatch.end());
        } else if (observation.startsWith("Error")) {
            statusLine = red("✗") + " error";
            body = observation;
        } else {
            String trimmed = observation.strip();
            int nl = trimmed.indexOf('\n');
            statusLine = truncateLine(nl < 0 ? trimmed : trimmed.substring(0, nl));
            body = nl < 0 ? "" : trimmed.substring(nl + 1);
        }

        String marker = theme.theme() == CliTheme.CODEX ? "└ " : "  ◀ ";
        String indent = theme.theme() == CliTheme.CODEX ? "│ " : "    ";
        out.println(marker + statusLine);
        printToolResultBody(body, indent);
        out.flush();
    }

    @Override
    public void bashCommand(String command) {
        if (theme.theme() == CliTheme.CODEX) {
            out.println("│ $ " + command);
        } else {
            out.println();
            out.println("  $ " + command);
            out.println("  " + "─".repeat(50));
        }
        out.flush();
    }

    @Override
    public void filePreview(String path, String content, boolean isNew) {
        String[] lines = content.split("\n", -1);
        int preview = Math.min(lines.length, 20);
        String header = path + (isNew ? "  (new file)" : "  (overwrites existing)");
        if (theme.theme() == CliTheme.CODEX) {
            out.println("│ " + header);
            for (int i = 0; i < preview; i++) {
                out.printf("│ %4d  %s%n", i + 1, lines[i]);
            }
            if (lines.length > preview) {
                out.println("│ ... (" + (lines.length - preview) + " more lines)");
            }
        } else {
            out.println();
            out.println("  Write file: " + header);
            out.println("  " + "─".repeat(44));
            for (int i = 0; i < preview; i++) {
                out.printf("  %4d  %s%n", i + 1, lines[i]);
            }
            if (lines.length > preview) {
                out.printf("  ... (%d more lines)%n", lines.length - preview);
            }
            out.println("  " + "─".repeat(44));
        }
        out.flush();
    }

    @Override
    public void editPreview(String path, String oldString, String newString) {
        if (theme.theme() == CliTheme.CODEX) {
            out.println("│ " + path);
            for (String line : oldString.split("\n", -1)) {
                out.println("│ " + red("- " + line));
            }
            for (String line : newString.split("\n", -1)) {
                out.println("│ " + green("+ " + line));
            }
        } else {
            out.println();
            out.println("  Edit file: " + path);
            out.println("  " + "─".repeat(44));
            for (String line : oldString.split("\n", -1)) {
                out.println("  " + red("- " + line));
            }
            for (String line : newString.split("\n", -1)) {
                out.println("  " + green("+ " + line));
            }
            out.println("  " + "─".repeat(44));
        }
        out.flush();
    }

    @Override
    public void mcpToolPreview(String server, String tool, Object args) {
        String argsJson;
        try {
            argsJson = MAPPER.writeValueAsString(args);
        } catch (Exception e) {
            argsJson = String.valueOf(args);
        }
        if (theme.theme() == CliTheme.CODEX) {
            out.println("│ " + server + "_" + tool + " " + argsJson);
        } else {
            out.println();
            out.println("  MCP call: " + server + "_" + tool);
            out.println("  " + "─".repeat(44));
            out.println("  " + argsJson);
            out.println("  " + "─".repeat(44));
        }
        out.flush();
    }

    @Override
    public ConfirmChoice confirm(String verb, String rememberKey) {
        if (rememberKey != null && alwaysAllowed.contains(rememberKey)) {
            return ConfirmChoice.YES;
        }
        ConfirmChoice choice = theme.theme() != CliTheme.CODEX
                ? confirmLine(verb)
                : confirmMenu(verb);
        if (choice == ConfirmChoice.ALWAYS && rememberKey != null) {
            alwaysAllowed.add(rememberKey);
        }
        return choice;
    }

    private ConfirmChoice confirmMenu(String verb) {
        Attributes original = terminal.enterRawMode();
        try {
            return confirmMenuRaw(verb);
        } finally {
            terminal.setAttributes(original);
        }
    }

    private ConfirmChoice confirmMenuRaw(String verb) {
        ConfirmChoice[] choices = {ConfirmChoice.NO, ConfirmChoice.YES, ConfirmChoice.ALWAYS, ConfirmChoice.PAUSE};
        String[] labels = {"no", "yes", "always", "pause"};
        int selected = 0;
        out.println("│ " + verb + "?");
        renderChoices(labels, selected);
        while (true) {
            int ch = readChar();
            if (ch == -1 || ch == '\n' || ch == '\r') {
                clearChoices(labels.length);
                out.println("│ " + labels[selected]);
                out.flush();
                return choices[selected];
            }
            if (ch == 3) {
                clearChoices(labels.length);
                out.println("│ pause");
                out.flush();
                return ConfirmChoice.PAUSE;
            }
            if (ch == 27) {
                int next1 = readChar();
                int next2 = readChar();
                if (next1 == '[' && next2 == 'A') {
                    selected = (selected + choices.length - 1) % choices.length;
                    renderChoices(labels, selected);
                    continue;
                }
                if (next1 == '[' && next2 == 'B') {
                    selected = (selected + 1) % choices.length;
                    renderChoices(labels, selected);
                    continue;
                }
                clearChoices(labels.length);
                out.println("│ no");
                out.flush();
                return ConfirmChoice.NO;
            }
            char c = Character.toLowerCase((char) ch);
            if (c == 'y') {
                clearChoices(labels.length);
                out.println("│ yes");
                out.flush();
                return ConfirmChoice.YES;
            }
            if (c == 'n') {
                clearChoices(labels.length);
                out.println("│ no");
                out.flush();
                return ConfirmChoice.NO;
            }
            if (c == 'a') {
                clearChoices(labels.length);
                out.println("│ always");
                out.flush();
                return ConfirmChoice.ALWAYS;
            }
            if (c == 'p') {
                clearChoices(labels.length);
                out.println("│ pause");
                out.flush();
                return ConfirmChoice.PAUSE;
            }
        }
    }

    @Override
    public void tokenSummary(String json) {
        if (theme.theme() == CliTheme.CODEX) {
            out.println(dim(formatTokenSummary(json, false)));
        } else {
            out.println("  " + "─".repeat(44));
            out.println("  " + formatTokenSummary(json, true));
            out.println();
        }
        out.flush();
    }

    @Override
    public void interruptPausing() {
        out.println();
        out.println(theme.theme() == CliTheme.CODEX ? yellow("pausing current task") : "  Interrupt received. Pausing current task...");
        out.flush();
    }

    @Override
    public void secondInterrupt() {
        out.println();
        out.println(theme.theme() == CliTheme.CODEX ? red("second interrupt, exiting") : "  Second interrupt received. Exiting.");
        out.flush();
    }

    @Override
    public void paused(String sessionName) {
        if (theme.theme() == CliTheme.CODEX) {
            out.println();
            out.printf("%s%n", yellow("paused"));
        } else {
            out.println();
            out.printf("  Task paused.%n");
        }
        out.flush();
    }

    @Override
    public void warning(String message) {
        out.println(theme.theme() == CliTheme.CODEX ? yellow("warn: " + message) : "[warn] " + message);
        out.flush();
    }

    @Override
    public void error(String message) {
        out.println(theme.theme() == CliTheme.CODEX ? red("error: " + message) : "  [error] " + message);
        out.flush();
    }

    @Override
    public void goodbye() {
        out.println(theme.theme() == CliTheme.CODEX ? "bye" : "Goodbye!");
        out.flush();
    }

    private void clearThinkingLine() {
        if (thinkingLineActive) {
            out.print("\r" + " ".repeat(24) + "\r");
            out.flush();
            thinkingLineActive = false;
        }
    }

    private ConfirmChoice confirmLine(String verb) {
        String label = Character.toUpperCase(verb.charAt(0)) + verb.substring(1);
        out.print("  " + label + "? [y/N/pause/always] ");
        out.flush();
        String answer = readLine().trim().toLowerCase();
        out.println();
        out.flush();
        if (answer.equals("y") || answer.equals("yes")) return ConfirmChoice.YES;
        if (answer.equals("a") || answer.equals("always")) return ConfirmChoice.ALWAYS;
        if (answer.equals("pause") || answer.equals("/pause") || answer.equals("p")) return ConfirmChoice.PAUSE;
        return ConfirmChoice.NO;
    }

    private void renderChoices(String[] labels, int selected) {
        out.print("\r");
        for (int i = 0; i < labels.length; i++) {
            out.print("│ " + (i == selected ? "> " : "  ") + labels[i]);
            out.print("\n");
        }
        out.print("\033[" + labels.length + "A");
        out.flush();
    }

    private void clearChoices(int lines) {
        out.print("\r");
        for (int i = 0; i < lines; i++) {
            out.print("\033[2K");
            if (i < lines - 1) out.print("\033[1B");
        }
        out.print("\033[" + Math.max(0, lines - 1) + "A");
        out.print("\r");
        out.flush();
    }

    private String readLine() {
        StringBuilder sb = new StringBuilder();
        int ch;
        while ((ch = readChar()) != -1 && ch != '\n' && ch != '\r') {
            sb.append((char) ch);
        }
        return sb.toString();
    }

    private int readChar() {
        try {
            return terminal.reader().read();
        } catch (IOException e) {
            return -1;
        }
    }

    private String formatTokenSummary(String json, boolean verbose) {
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode total = root.path("total");
            long prompt = total.path("prompt_tokens").asLong();
            long completion = total.path("completion_tokens").asLong();
            long cached = total.path("cached_tokens").asLong();
            long toolCalls = total.path("tool_calls").asLong();
            long elapsedMs = root.path("elapsed_ms").asLong();
            long llmMs = root.path("llm_ms").asLong();

            if (verbose) {
                String cachePart = cached > 0 ? String.format(" · cache hit %d", cached) : "";
                return String.format("Tokens: %d in -> %d out%s · tools %d · %.1fs (LLM %.1fs)",
                        prompt, completion, cachePart, toolCalls, elapsedMs / 1000.0, llmMs / 1000.0);
            }
            String cachePart = cached > 0 ? String.format(" · cache %d", cached) : "";
            return String.format("tokens %d -> %d%s · tools %d · %.1fs · llm %.1fs",
                    prompt, completion, cachePart, toolCalls, elapsedMs / 1000.0, llmMs / 1000.0);
        } catch (Exception e) {
            return json;
        }
    }

    /**
     * One TOOL_CALLED event's text, broken into its pieces. The producer format is
     * {@code "[<scope>] <type>:<name> <args-json>"} (scope/type both optional) — see
     * CapabilityExecutor.labelToolCall(). {@code showArgs} is false when this tool already prints
     * its own dedicated preview (bash command, file write/edit diff, MCP call args), so the args
     * aren't shown twice.
     */
    private record ToolCallParts(String scope, String display, String args, boolean showArgs) {}

    private ToolCallParts parseToolCall(String text) {
        String t = text == null ? "" : text.trim();
        String scope = null;
        if (t.startsWith("[")) {
            int end = t.indexOf(']');
            if (end > 0) {
                scope = t.substring(0, end + 1);
                t = t.substring(end + 1).trim();
            }
        }
        int space = t.indexOf(' ');
        String typeAndName = space > 0 ? t.substring(0, space) : t;
        String args = space > 0 ? t.substring(space + 1).trim() : "";
        boolean isMcpTool = typeAndName.startsWith("mcp_tool:");
        String bareName = isMcpTool ? typeAndName.substring("mcp_tool:".length()) : typeAndName;
        String display = isMcpTool ? bareName : typeAndName;
        boolean showArgs = !args.isBlank() && !isMcpTool && !HAS_DEDICATED_PREVIEW.contains(bareName);
        return new ToolCallParts(scope, display, args, showArgs);
    }

    /**
     * Drops the "[&lt;scope&gt;] &lt;type&gt;:&lt;name&gt; -&gt; " label CapabilityExecutor
     * prepends to every TOOL_RESULT event (formatToolResult()) — toolCalled() just printed the
     * same scope/type/name, so repeating it here is pure duplication. The label itself never
     * contains " -> " (only the observation that follows might, in principle), so splitting on the
     * first occurrence is safe for the real producer format even though it's a string convention,
     * not a real schema.
     */
    private String stripResultLabel(String text) {
        if (text == null) return "";
        int idx = text.indexOf(" -> ");
        return idx >= 0 ? text.substring(idx + 4) : text;
    }

    private void printToolResultBody(String body, String indent) {
        if (body == null) return;
        String trimmed = body.strip();
        if (trimmed.isEmpty()) return;
        String[] lines = trimmed.split("\n", -1);
        int shown = Math.min(lines.length, MAX_TOOL_RESULT_LINES);
        for (int i = 0; i < shown; i++) {
            out.println(indent + truncateLine(lines[i]));
        }
        if (lines.length > shown) {
            out.println(indent + "... (" + (lines.length - shown) + " more lines)");
        }
    }

    private String truncateLine(String line) {
        return line.length() <= MAX_LINE_CHARS ? line : line.substring(0, MAX_LINE_CHARS) + "…";
    }

    private String dim(String text) {
        return theme.colorEnabled() ? Ansi.DIM + text + Ansi.RESET : text;
    }

    private String yellow(String text) {
        return theme.colorEnabled() ? Ansi.YELLOW + text + Ansi.RESET : text;
    }

    private String red(String text) {
        return theme.colorEnabled() ? Ansi.RED + text + Ansi.RESET : text;
    }

    private String green(String text) {
        return theme.colorEnabled() ? Ansi.GREEN + text + Ansi.RESET : text;
    }
}
