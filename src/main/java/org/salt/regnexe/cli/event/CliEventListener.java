package org.salt.regnexe.cli.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jline.terminal.Terminal;
import org.salt.regnexe.agent.core.event.AbstractEventListener;
import org.salt.regnexe.agent.core.event.AgentEvent;
import org.salt.regnexe.agent.core.event.EventType;

import java.io.PrintWriter;

/**
 * Real-time CLI output for agent events.
 * Shows tool calls/results as they happen, phase indicators, final answer, and token summary.
 * Does NOT print the final AgentResult.finalText — that is already covered by LLM_RESPONDED.
 */
public class CliEventListener extends AbstractEventListener {

    private static final int MAX_TOOL_RESULT_CHARS = 300;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PrintWriter out;
    private boolean thinkingLineActive = false;  // true while "⟳ Thinking..." is on current line

    public CliEventListener(Terminal terminal) {
        super(false, false);  // base class filtering disabled; we use shouldHandle() whitelist
        this.out = terminal.writer();
    }

    // ── Whitelist: only events that produce visible CLI output ───────────────

    @Override
    public boolean shouldHandle(EventType type) {
        return switch (type) {
            case PLAN_STARTED,
                 PLAN_COMPLETED,
                 EXECUTION_STARTED,
                 TOOL_CALLED,
                 TOOL_RESULT,
                 TASK_TOKEN_SUMMARY -> true;
            // LLM_RESPONDED / *_LLM_RESPONDED contain the full execution context,
            // not just the answer text. Clean answer is printed via AgentResult.getFinalText().
            default -> false;
        };
    }

    // ── Event handlers ───────────────────────────────────────────────────────

    @Override
    public void onEvent(AgentEvent event) {
        switch (event.getType()) {

            case PLAN_STARTED -> {
                out.print("  ⟳ Thinking...");
                out.flush();
                thinkingLineActive = true;
            }

            case PLAN_COMPLETED -> {
                // overwrite "⟳ Thinking..." on the same line
                out.print("\r  ✓ Ready        \n");
                out.flush();
                thinkingLineActive = false;
            }

            case EXECUTION_STARTED -> {
                clearThinkingLine();
                out.println("  ⟳ Executing...");
                out.flush();
            }

            case TOOL_CALLED -> {
                clearThinkingLine();
                out.println("\n  ▶ " + event.getText());
                out.flush();
            }

            case TOOL_RESULT -> {
                out.println("  ◀ " + truncate(event.getText(), MAX_TOOL_RESULT_CHARS));
                out.flush();
            }

            // Per-task token summary, emitted automatically by TokenAggregatingEventListener
            case TASK_TOKEN_SUMMARY -> {
                out.println("  " + "─".repeat(44));
                out.println("  " + formatTokenSummary(event.getText()));
                out.println();
                out.flush();
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void clearThinkingLine() {
        if (thinkingLineActive) {
            out.print("\r" + " ".repeat(20) + "\r");
            out.flush();
            thinkingLineActive = false;
        }
    }

    private String truncate(String text, int max) {
        if (text == null) return "";
        String t = text.trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private String formatTokenSummary(String json) {
        try {
            JsonNode root  = MAPPER.readTree(json);
            JsonNode total = root.path("total");
            // JsonUtil serializes with SNAKE_CASE; Long fields are serialized as strings
            long prompt     = total.path("prompt_tokens").asLong();
            long completion = total.path("completion_tokens").asLong();
            long cached     = total.path("cached_tokens").asLong();
            long toolCalls  = total.path("tool_calls").asLong();
            long elapsedMs  = root.path("elapsed_ms").asLong();  // LongNode (number)
            long llmMs      = root.path("llm_ms").asLong();      // LongNode (number)
            String cachePart = cached > 0 ? String.format(" · cache hit %d", cached) : "";
            return String.format(
                    "Tokens: %d in -> %d out%s · tools %d · %.1fs (LLM %.1fs)",
                    prompt, completion, cachePart, toolCalls, elapsedMs / 1000.0, llmMs / 1000.0);
        } catch (Exception e) {
            return json;
        }
    }
}
