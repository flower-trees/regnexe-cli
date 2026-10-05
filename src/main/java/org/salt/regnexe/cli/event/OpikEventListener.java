package org.salt.regnexe.cli.event;

import org.salt.jlangchain.ai.common.param.AiTokenUsage;
import org.salt.jlangchain.core.agent.AgentTokenUsageEvent;
import org.salt.regnexe.agent.core.event.AgentEvent;
import org.salt.regnexe.agent.core.event.AgentEventListener;
import org.salt.regnexe.agent.core.event.EventType;
import org.salt.regnexe.cli.observe.OtlpSpanExporter;
import org.salt.regnexe.cli.observe.SpanData;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Turns the agent event stream into one trace per task run and hands finished spans to
 * {@link OtlpSpanExporter}:
 *
 * <pre>
 * task                      AGENT_STARTED .. AGENT_COMPLETED
 * ├─ search/plan/execute/reflect R&lt;n&gt;   *_STARTED .. *_COMPLETED
 * │  ├─ llm:*               *_LLM_RESPONDED .. token usage / next step
 * │  └─ tool:&lt;name&gt;         TOOL_CALLED .. TOOL_RESULT (sub-agent / skill steps nest inside)
 * </pre>
 *
 * <p>Events carry no span ids, so nesting is reconstructed from order. That is sound only because
 * the executor runs LLM calls and tool calls of one task strictly sequentially: an open tool span
 * is always the innermost thing running, hence a stack. Note that every {@code *_LLM_RESPONDED}
 * event is emitted <em>before</em> the model is called and carries the prompt, not the response —
 * it opens the LLM span. Plan / Reflect calls then report their raw output via {@code LLM_OUTPUT};
 * the execute loop has no such event, so there the output is filled from what follows (the tool
 * calls it made, or the phase result).
 */
public class OpikEventListener implements AgentEventListener {

    /** Vendor + model used when an LLM call reports no usage of its own (Plan / Reflect). */
    public record ModelRef(String vendor, String model) {}

    private final OtlpSpanExporter exporter;
    private final Supplier<String> sessionId;
    private final int maxTextChars;
    private final ModelRef defaultModel;
    private final ModelRef plannerModel;
    private final ModelRef reflectorModel;

    private final Map<String, TaskTrace> traces = new ConcurrentHashMap<>();

    public OpikEventListener(OtlpSpanExporter exporter, Supplier<String> sessionId, int maxTextChars,
                             ModelRef defaultModel, ModelRef plannerModel, ModelRef reflectorModel) {
        this.exporter = exporter;
        this.sessionId = sessionId;
        this.maxTextChars = maxTextChars;
        this.defaultModel = defaultModel;
        this.plannerModel = plannerModel;
        this.reflectorModel = reflectorModel;
    }

    private static class LlmCall {
        final SpanData span;
        final List<String> toolCalls = new ArrayList<>();
        String rawOutput;
        boolean hasUsage;

        LlmCall(SpanData span) {
            this.span = span;
        }
    }

    private static class TaskTrace {
        final String traceId = SpanData.randomHex(16);
        SpanData root;
        SpanData phase;
        // Innermost running tool on top; a sub-agent / skill invocation is itself a tool call.
        final Deque<SpanData> toolStack = new ArrayDeque<>();
        // The LLM call most recently opened at each nesting depth (depth = toolStack size).
        final Map<Integer, LlmCall> llmByDepth = new HashMap<>();
        String lastExecutionText;
    }

    @Override
    public synchronized void onEvent(AgentEvent event) {
        try {
            handle(event);
        } catch (RuntimeException e) {
            // Tracing must never break a task.
        }
    }

    private void handle(AgentEvent event) {
        String taskId = event.getTaskId() != null ? event.getTaskId() : "";
        long ts = event.getTimestamp();
        String text = event.getText();

        if (event.getType() == EventType.AGENT_STARTED) {
            TaskTrace t = new TaskTrace();
            t.root = new SpanData(t.traceId, null, "task", ts)
                    .attr("input.value", cut(text))
                    .attr("thread_id", sessionId.get())
                    .attr("metadata.task_id", taskId);
            traces.put(taskId, t);
            return;
        }

        TaskTrace t = traces.get(taskId);
        if (t == null) return;

        switch (event.getType()) {
            case SEARCH_STARTED -> openPhase(t, "search", event);
            case PLAN_STARTED -> openPhase(t, "plan", event);
            case EXECUTION_STARTED -> openPhase(t, "execute", event);
            case REFLECTION_STARTED -> openPhase(t, "reflect", event);

            case SEARCH_COMPLETED, PLAN_COMPLETED, REFLECTION_COMPLETED -> closePhase(t, ts, text, null);
            case EXECUTION_COMPLETED -> {
                t.lastExecutionText = text;
                closePhase(t, ts, text, text != null && text.startsWith("FAILED") ? text : null);
            }

            case PLAN_LLM_RESPONDED -> openLlm(t, "llm:plan", plannerModel, event);
            case REFLECT_LLM_RESPONDED -> openLlm(t, "llm:reflect", reflectorModel, event);
            case LLM_RESPONDED -> openLlm(t, "llm:execute", defaultModel, event);
            case SKILL_LLM_RESPONDED -> openLlm(t, "llm:skill", defaultModel, event);
            case AGENT_LLM_RESPONDED -> openLlm(t, "llm:sub_agent", defaultModel, event);

            case LLM_OUTPUT -> {
                LlmCall call = t.llmByDepth.get(t.toolStack.size());
                if (call != null) call.rawOutput = text;
            }
            case TOKEN_USAGE, CAPABILITY_TOKEN_USAGE -> recordUsage(t, event);

            case TOOL_CALLED -> openTool(t, ts, text);
            case TOOL_RESULT -> closeTool(t, ts, text);

            case TASK_TOKEN_SUMMARY -> t.root.attr("metadata.token_summary", cut(text));

            case AGENT_COMPLETED -> {
                closePhase(t, ts, null, null);
                unwind(t, 0, ts, null);
                String output = t.lastExecutionText == null ? text : text + "\n\n" + t.lastExecutionText;
                t.root.attr("output.value", cut(output));
                if (text != null && (text.contains("FAILED") || text.contains("TIMEOUT"))) {
                    t.root.setError(text);
                }
                end(t.root, ts);
                traces.remove(taskId);
            }
            default -> { }
        }
    }

    // ── Phases ───────────────────────────────────────────────────────────────

    private void openPhase(TaskTrace t, String name, AgentEvent event) {
        closePhase(t, event.getTimestamp(), null, null);
        t.phase = new SpanData(t.traceId, t.root.getSpanId(), name + " R" + event.getRound(), event.getTimestamp())
                .attr("input.value", cut(event.getText()))
                .attr("metadata.round", (long) event.getRound());
    }

    private void closePhase(TaskTrace t, long ts, String output, String error) {
        unwind(t, 0, ts, output);
        if (t.phase == null) return;
        t.phase.attr("output.value", cut(output));
        t.phase.setError(error);
        end(t.phase, ts);
        t.phase = null;
    }

    /**
     * Closes everything still open at nesting depth >= {@code depth}: tool spans above it and the
     * LLM calls opened inside them. {@code result} is what the enclosing step produced, used as
     * the output of an LLM call that made no tool calls (i.e. its turn was the final answer).
     */
    private void unwind(TaskTrace t, int depth, long ts, String result) {
        while (t.toolStack.size() > depth) {
            finishLlm(t, t.toolStack.size(), ts, null);
            end(t.toolStack.pop(), ts);
        }
        finishLlm(t, depth, ts, result);
    }

    // ── LLM calls ────────────────────────────────────────────────────────────

    private void openLlm(TaskTrace t, String name, ModelRef model, AgentEvent event) {
        int depth = t.toolStack.size();
        finishLlm(t, depth, event.getTimestamp(), null);
        SpanData span = new SpanData(t.traceId, parentId(t), name, event.getTimestamp())
                .attr("gen_ai.operation.name", "chat")
                .attr("gen_ai.prompt", cut(event.getText()));
        if (model != null) {
            span.attr("gen_ai.system", model.vendor()).attr("gen_ai.request.model", model.model());
        }
        t.llmByDepth.put(depth, new LlmCall(span));
    }

    private void recordUsage(TaskTrace t, AgentEvent event) {
        AgentTokenUsageEvent usageEvent = event.getTokenUsage();
        AiTokenUsage delta = usageEvent != null ? usageEvent.getDeltaUsage() : null;
        if (delta == null) return;
        long ts = event.getTimestamp();

        // The call this usage belongs to is the innermost one still waiting for it.
        LlmCall call = null;
        for (int depth = t.toolStack.size(); depth >= 0 && call == null; depth--) {
            LlmCall candidate = t.llmByDepth.get(depth);
            if (candidate != null && !candidate.hasUsage) call = candidate;
        }
        if (call == null) {
            // No prompt event preceded it (e.g. a verbose sub-agent): keep the usage anyway.
            long start = ts - Math.max(0, usageEvent.getDeltaDurationMs());
            call = new LlmCall(new SpanData(t.traceId, parentId(t), "llm", start)
                    .attr("gen_ai.operation.name", "chat"));
            t.llmByDepth.put(t.toolStack.size(), call);
        }
        call.hasUsage = true;
        call.span.setEndMs(ts);
        call.span.attr("gen_ai.system", delta.getProvider())
                .attr("gen_ai.request.model", delta.getModel())
                .attr("gen_ai.usage.input_tokens", delta.getPromptTokens())
                .attr("gen_ai.usage.output_tokens", delta.getCompletionTokens());
        if (delta.getCachedTokens() > 0) {
            call.span.attr("gen_ai.usage.cache_read_input_tokens", delta.getCachedTokens());
        }
        if (delta.getReasoningTokens() > 0) {
            call.span.attr("gen_ai.usage.reasoning_tokens", delta.getReasoningTokens());
        }
        if (delta.isEstimated()) {
            call.span.attr("metadata.usage_estimated", true);
        }
    }

    private void finishLlm(TaskTrace t, int depth, long ts, String result) {
        LlmCall call = t.llmByDepth.remove(depth);
        if (call == null) return;
        String output = call.rawOutput != null ? call.rawOutput
                : call.toolCalls.isEmpty() ? result : "tool_calls:\n" + String.join("\n", call.toolCalls);
        call.span.attr("gen_ai.completion", cut(output));
        end(call.span, call.span.getEndMs() > 0 ? call.span.getEndMs() : ts);
    }

    // ── Tool calls ───────────────────────────────────────────────────────────

    private void openTool(TaskTrace t, long ts, String text) {
        String call = text == null ? "" : text;
        int brace = call.indexOf('{');
        String label = (brace >= 0 ? call.substring(0, brace) : call).trim();
        String args = brace >= 0 ? call.substring(brace) : "";
        // label is "[scope] type:name" — the last token is what was actually invoked.
        String name = label.substring(label.lastIndexOf(' ') + 1);

        LlmCall llm = t.llmByDepth.get(t.toolStack.size());
        if (llm != null) {
            llm.toolCalls.add(call);
            if (llm.span.getEndMs() == 0) llm.span.setEndMs(ts);
        }
        SpanData span = new SpanData(t.traceId, parentId(t), name.isEmpty() ? "tool" : name, ts)
                .attr("gen_ai.operation.name", "execute_tool")
                .attr("gen_ai.tool.name", name)
                .attr("gen_ai.tool.call.arguments", cut(args));
        t.toolStack.push(span);
    }

    private void closeTool(TaskTrace t, long ts, String text) {
        if (t.toolStack.isEmpty()) return;
        // text is "<label> -> <observation>"
        int arrow = text == null ? -1 : text.indexOf(" -> ");
        String observation = arrow >= 0 ? text.substring(arrow + 4) : text;
        // A sub-agent's last LLM turn made no tool calls: its answer is this observation.
        finishLlm(t, t.toolStack.size(), ts, observation);
        SpanData span = t.toolStack.pop();
        span.attr("output.value", cut(observation));
        end(span, ts);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String parentId(TaskTrace t) {
        if (!t.toolStack.isEmpty()) return t.toolStack.peek().getSpanId();
        return t.phase != null ? t.phase.getSpanId() : t.root.getSpanId();
    }

    private void end(SpanData span, long ts) {
        span.setEndMs(ts);
        exporter.export(span);
    }

    private String cut(String text) {
        if (text == null || text.length() <= maxTextChars) return text;
        return text.substring(0, maxTextChars) + "\n… [truncated, " + text.length() + " chars total]";
    }
}
