package org.salt.regnexe.cli.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jline.terminal.Terminal;
import org.salt.regnexe.cli.config.RexConfig;
import org.salt.regnexe.cli.session.SessionContext;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;

public class TerminalCliRenderer implements CliRenderer {

    private static final int MAX_TOOL_RESULT_CHARS = 300;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PrintWriter out;
    private final ThemeConfig theme;
    private boolean thinkingLineActive = false;

    public TerminalCliRenderer(Terminal terminal, ThemeConfig theme) {
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
    public void bashConfirmPrompt() {
        out.print(theme.theme() == CliTheme.CODEX ? "│ run? [y/N/pause] " : "  Execute? [y/N/pause] ");
        out.flush();
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
            out.printf("resume: /resume  or  rex --resume %s%n", sessionName);
        } else {
            out.println();
            out.printf("  Task paused. Resume with: /resume or rex --resume %s%n", sessionName);
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
}
