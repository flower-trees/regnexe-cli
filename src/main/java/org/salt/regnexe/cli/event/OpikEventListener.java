package org.salt.regnexe.cli.event;

import org.salt.jlangchain.ai.common.param.AiTokenUsage;
import org.salt.jlangchain.core.agent.AgentTokenUsageEvent;
import org.salt.regnexe.agent.core.event.AgentEvent;
import org.salt.regnexe.agent.core.event.AgentEventListener;
import org.salt.regnexe.agent.core.event.EventType;
import org.salt.regnexe.agent.core.event.SpanIds;
import org.salt.regnexe.cli.observe.OtlpSpanExporter;
import org.salt.regnexe.cli.observe.SpanData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Turns the agent event stream into one trace per task and hands finished spans to
 * {@link OtlpSpanExporter}:
 *
 * <pre>
 * task                      AGENT_STARTED .. AGENT_COMPLETED
 * ├─ search/plan/execute/reflect R&lt;n&gt;   *_STARTED .. *_COMPLETED
 * │  ├─ llm:*               *_LLM_RESPONDED .. LLM_OUTPUT / token usage
 * │  └─ &lt;tool&gt;              TOOL_CALLED .. TOOL_RESULT (sub-agent steps nest inside)
 * └─ resume                 a later resume() of the same task, with its own phases
 * </pre>
 *
 * <p>The task (or resume) span is exported the moment the run starts, as a zero-length marker, so
 * the trace shows up in Opik while the task is still running and survives the process being
 * killed; every other span is exported when it ends. The run's result is then written onto the
 * trace itself (see {@link OtlpSpanExporter.TraceUpdate}), since a sent span cannot be amended.
 *
 * <p>Structure comes from the events themselves: each carries the span it belongs to and, when
 * it opens one, that span's parent (see regnexe-agent docs/design/13-event-span-model-design.md).
 * Nothing here infers nesting from event order.
 *
 * <p>Every {@code *_LLM_RESPONDED} event is emitted <em>before</em> the model is called and
 * carries the prompt. Plan / Reflect calls then report their raw output via {@code LLM_OUTPUT};
 * the execute loop has no such event, so there the output shown is the tool calls that followed
 * the call, or the result of the step it ran in.
 */
public class OpikEventListener implements AgentEventListener {

    /** Vendor + model shown on an LLM call until its own usage report says otherwise. */
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

        LlmCall(SpanData span) {
            this.span = span;
        }
    }

    private static class TaskTrace {
        final String traceId;
        /** The root span, or the "resume" span when this run continues an earlier one. */
        SpanData run;
        boolean resumed;
        String input;
        String tokenSummary;
        /** Phase and tool spans not yet closed, by span id. */
        final Map<String, SpanData> open = new HashMap<>();
        /** LLM calls not yet exported, by span id. */
        final Map<String, LlmCall> llms = new HashMap<>();
        /** The latest LLM call under each parent — the one a following tool call was issued by. */
        final Map<String, LlmCall> lastLlmByParent = new HashMap<>();
        String lastExecutionText;

        TaskTrace(String traceId) {
            this.traceId = traceId;
        }
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
            TaskTrace t = new TaskTrace(SpanIds.traceId(taskId));
            t.resumed = event.getParentSpanId() != null;
            t.run = new SpanData(t.traceId, event.getSpanId(), event.getParentSpanId(),
                    t.resumed ? "resume" : "task", ts)
                    .attr("input.value", cut(text))
                    .attr("thread_id", sessionId.get())
                    .attr("metadata.task_id", taskId);
            t.input = cut(text);
            traces.put(taskId, t);
            end(t.run, ts);
            return;
        }

        TaskTrace t = traces.get(taskId);
        if (t == null) return;

        switch (event.getType()) {
            case SEARCH_STARTED -> openPhase(t, "search", event);
            case PLAN_STARTED -> openPhase(t, "plan", event);
            case EXECUTION_STARTED -> openPhase(t, "execute", event);
            case REFLECTION_STARTED -> openPhase(t, "reflect", event);

            case SEARCH_COMPLETED -> closePhase(t, "search", event, null);
            case PLAN_COMPLETED -> closePhase(t, "plan", event, null);
            case REFLECTION_COMPLETED -> closePhase(t, "reflect", event, null);
            case EXECUTION_COMPLETED -> {
                t.lastExecutionText = text;
                closePhase(t, "execute", event, text != null && text.startsWith("FAILED") ? text : null);
            }

            case PLAN_LLM_RESPONDED -> openLlm(t, "llm:plan", plannerModel, event);
            case REFLECT_LLM_RESPONDED -> openLlm(t, "llm:reflect", reflectorModel, event);
            case LLM_RESPONDED -> openLlm(t, "llm:execute", defaultModel, event);
            case SKILL_LLM_RESPONDED -> openLlm(t, "llm:skill", defaultModel, event);
            case AGENT_LLM_RESPONDED -> openLlm(t, "llm:sub_agent", defaultModel, event);

            case LLM_OUTPUT -> {
                LlmCall call = t.llms.get(event.getSpanId());
                if (call != null) call.rawOutput = text;
            }
            case TOKEN_USAGE, CAPABILITY_TOKEN_USAGE -> recordUsage(t, event);

            case TOOL_CALLED -> openTool(t, event);
            case TOOL_RESULT -> closeTool(t, event);

            case TASK_TOKEN_SUMMARY -> t.tokenSummary = text;

            case AGENT_COMPLETED -> {
                closeRun(t, taskId, ts, text);
                traces.remove(taskId);
            }
            default -> { }
        }
    }

    // ── Task run ─────────────────────────────────────────────────────────────

    private void closeRun(TaskTrace t, String taskId, long ts, String status) {
        // Anything still open was cut short (pause, failure): close it where the run ended.
        for (LlmCall call : new ArrayList<>(t.llms.values())) finishLlm(t, call, ts, null);
        for (SpanData span : t.open.values()) end(span, ts);
        t.open.clear();

        String output = t.lastExecutionText == null ? status : status + "\n\n" + t.lastExecutionText;
        output = cut(output);
        String error = status != null && (status.contains("FAILED") || status.contains("TIMEOUT")) ? status : null;

        // The run span already went out when the run started; record how it ended on the trace.
        SpanData root = new SpanData(t.traceId, SpanIds.rootSpanId(taskId), null, "task", t.run.getStartMs())
                .attr("input.value", t.resumed ? "(earlier run of this task was not traced)" : t.input)
                .attr("output.value", output)
                .attr("thread_id", sessionId.get())
                .attr("metadata.task_id", taskId)
                .attr("metadata.token_summary", t.tokenSummary);
        root.setError(error);
        root.setEndMs(ts);
        exporter.updateTrace(new OtlpSpanExporter.TraceUpdate(taskId, output, ts, error, t.tokenSummary, root));
    }

    // ── Phases ───────────────────────────────────────────────────────────────

    private void openPhase(TaskTrace t, String name, AgentEvent event) {
        t.open.put(event.getSpanId(), phaseSpan(t, name, event).attr("input.value", cut(event.getText())));
    }

    private void closePhase(TaskTrace t, String name, AgentEvent event, String error) {
        long ts = event.getTimestamp();
        String output = event.getText();
        // An LLM call that made no tool calls produced the phase's result.
        finishLlm(t, t.lastLlmByParent.get(event.getSpanId()), ts, output);
        SpanData span = t.open.remove(event.getSpanId());
        if (span == null) {
            // Completed without a start event (search reusing cached results): zero-length span.
            span = phaseSpan(t, name, event);
        }
        span.attr("output.value", cut(output));
        span.setError(error);
        end(span, ts);
    }

    private SpanData phaseSpan(TaskTrace t, String name, AgentEvent event) {
        String parent = event.getParentSpanId() != null ? event.getParentSpanId() : t.run.getSpanId();
        return new SpanData(t.traceId, event.getSpanId(), parent, name + " R" + event.getRound(), event.getTimestamp())
                .attr("metadata.round", (long) event.getRound());
    }

    // ── LLM calls ────────────────────────────────────────────────────────────

    private void openLlm(TaskTrace t, String name, ModelRef model, AgentEvent event) {
        String parent = event.getParentSpanId() != null ? event.getParentSpanId() : t.run.getSpanId();
        // The previous call under the same parent is over once the next one starts.
        finishLlm(t, t.lastLlmByParent.get(parent), event.getTimestamp(), null);
        SpanData span = new SpanData(t.traceId, event.getSpanId(), parent, name, event.getTimestamp())
                .attr("gen_ai.operation.name", "chat")
                .attr("gen_ai.prompt", cut(event.getText()));
        if (model != null) {
            span.attr("gen_ai.system", model.vendor()).attr("gen_ai.request.model", model.model());
        }
        LlmCall call = new LlmCall(span);
        t.llms.put(span.getSpanId(), call);
        t.lastLlmByParent.put(parent, call);
    }

    private void recordUsage(TaskTrace t, AgentEvent event) {
        AgentTokenUsageEvent usageEvent = event.getTokenUsage();
        AiTokenUsage delta = usageEvent != null ? usageEvent.getDeltaUsage() : null;
        if (delta == null) return;
        long ts = event.getTimestamp();

        LlmCall call = event.getSpanId() == null ? null : t.llms.get(event.getSpanId());
        boolean standalone = call == null;
        if (standalone) {
            // No prompt event preceded it (e.g. a verbose sub-agent): keep the usage anyway.
            long start = ts - Math.max(0, usageEvent.getDeltaDurationMs());
            call = new LlmCall(new SpanData(t.traceId, null, t.run.getSpanId(), "llm", start)
                    .attr("gen_ai.operation.name", "chat"));
        }
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
        if (standalone) exporter.export(call.span);
    }

    /** Exports the call; {@code result} is its output when nothing more specific is known. */
    private void finishLlm(TaskTrace t, LlmCall call, long ts, String result) {
        if (call == null || t.llms.remove(call.span.getSpanId()) == null) return;
        t.lastLlmByParent.remove(call.span.getParentSpanId(), call);
        String output = call.rawOutput != null ? call.rawOutput
                : call.toolCalls.isEmpty() ? result : "tool_calls:\n" + String.join("\n", call.toolCalls);
        call.span.attr("gen_ai.completion", cut(output));
        end(call.span, call.span.getEndMs() > 0 ? call.span.getEndMs() : ts);
    }

    // ── Tool calls ───────────────────────────────────────────────────────────

    private void openTool(TaskTrace t, AgentEvent event) {
        long ts = event.getTimestamp();
        String call = event.getText() == null ? "" : event.getText();
        int brace = call.indexOf('{');
        String label = (brace >= 0 ? call.substring(0, brace) : call).trim();
        String args = brace >= 0 ? call.substring(brace) : "";
        // label is "[scope] type:name" — the last token is what was actually invoked.
        String name = label.substring(label.lastIndexOf(' ') + 1);
        String parent = event.getParentSpanId() != null ? event.getParentSpanId() : t.run.getSpanId();

        LlmCall llm = t.lastLlmByParent.get(parent);
        if (llm != null) {
            llm.toolCalls.add(call);
            if (llm.span.getEndMs() == 0) llm.span.setEndMs(ts);
        }
        SpanData span = new SpanData(t.traceId, event.getSpanId(), parent, name.isEmpty() ? "tool" : name, ts)
                .attr("gen_ai.operation.name", "execute_tool")
                .attr("gen_ai.tool.name", name)
                .attr("gen_ai.tool.call.arguments", cut(args));
        t.open.put(span.getSpanId(), span);
    }

    private void closeTool(TaskTrace t, AgentEvent event) {
        SpanData span = t.open.remove(event.getSpanId());
        if (span == null) return;
        long ts = event.getTimestamp();
        String text = event.getText();
        // text is "<label> -> <observation>"
        int arrow = text == null ? -1 : text.indexOf(" -> ");
        String observation = arrow >= 0 ? text.substring(arrow + 4) : text;
        // A sub-agent's last LLM turn made no tool calls: its answer is this observation.
        finishLlm(t, t.lastLlmByParent.get(span.getSpanId()), ts, observation);
        span.attr("output.value", cut(observation));
        end(span, ts);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void end(SpanData span, long ts) {
        span.setEndMs(ts);
        exporter.export(span);
    }

    private String cut(String text) {
        if (text == null || text.length() <= maxTextChars) return text;
        return text.substring(0, maxTextChars) + "\n… [truncated, " + text.length() + " chars total]";
    }
}
