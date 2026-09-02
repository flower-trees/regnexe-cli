package org.salt.regnexe.cli.event;

import org.salt.regnexe.agent.core.event.AbstractEventListener;
import org.salt.regnexe.agent.core.event.AgentEvent;
import org.salt.regnexe.agent.core.event.EventType;
import org.salt.regnexe.cli.ui.CliRenderer;

/**
 * Real-time CLI output for agent events.
 * Shows tool calls/results as they happen, phase indicators, final answer, and token summary.
 * Does NOT print the final AgentResult.finalText — that is already covered by LLM_RESPONDED.
 */
public class CliEventListener extends AbstractEventListener {

    private final CliRenderer renderer;

    public CliEventListener(CliRenderer renderer) {
        super(false, false);  // base class filtering disabled; we use shouldHandle() whitelist
        this.renderer = renderer;
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
                renderer.thinking();
            }

            case PLAN_COMPLETED -> {
                renderer.ready();
            }

            case EXECUTION_STARTED -> {
                renderer.executing();
            }

            case TOOL_CALLED -> {
                renderer.toolCalled(event.getText());
            }

            case TOOL_RESULT -> {
                renderer.toolResult(event.getText());
            }

            // Per-task token summary, emitted automatically by TokenAggregatingEventListener
            case TASK_TOKEN_SUMMARY -> {
                renderer.tokenSummary(event.getText());
            }
        }
    }
}
