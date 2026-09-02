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

public class TerminalCliRenderer implements CliRenderer {

    private static final int MAX_TOOL_RESULT_CHARS = 300;
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        String display = simplifyToolText(text);
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
        String value = truncate(text, MAX_TOOL_RESULT_CHARS);
        if (theme.theme() == CliTheme.CODEX) {
            out.println("└ " + value);
        } else {
            out.println("  ◀ " + value);
        }
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

    private String truncate(String text, int max) {
        if (text == null) return "";
        String t = text.trim();
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }

    private String simplifyToolText(String text) {
        if (text == null) return "";
        String t = text.trim();
        if (t.startsWith("mcp_tool:bash")) return "bash";
        if (t.startsWith("mcp_tool:")) {
            int space = t.indexOf(' ');
            return space > 0 ? t.substring("mcp_tool:".length(), space) : t.substring("mcp_tool:".length());
        }
        return t;
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
