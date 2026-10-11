package org.salt.regnexe.cli.observe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.salt.regnexe.cli.config.RexConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ships finished spans to an OTLP/HTTP endpoint (JSON encoding) from a background daemon thread.
 * Uses only the JDK HttpClient + Jackson, so enabling tracing adds no dependency to the CLI jar.
 *
 * <p>Never blocks or throws into the agent loop: {@link #export} only enqueues, a full queue drops
 * the span, and a failing endpoint is reported once on stderr and then retried silently.
 */
public class OtlpSpanExporter {

    private static final int QUEUE_CAPACITY = 4096;
    private static final int MAX_BATCH = 128;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Brings an already-exported trace up to date when a run ends. A trace's root span is sent
     * when the task starts so the trace is visible while it runs, and OTLP cannot amend it
     * afterwards (Opik rejects a second root span for a trace), so the result goes through
     * Opik's REST API. {@code fallbackRoot} is exported instead when Opik has no such trace —
     * it was unreachable when the task started, or the original run of a resumed task was not
     * traced.
     */
    public record TraceUpdate(String taskId, String output, long endMs, String error,
                              String tokenSummaryJson, SpanData fallbackRoot) {}

    private final URI tracesUri;
    /** Opik REST base ({@code .../v1/private}), or null when the endpoint is not Opik-shaped. */
    private final String restBase;
    private final String project;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    // SpanData or TraceUpdate, processed strictly in order.
    private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile boolean failureReported;

    public OtlpSpanExporter(RexConfig.OpikConfig config) {
        String base = config.getEndpoint().replaceAll("/+$", "");
        this.tracesUri = URI.create(base + "/v1/traces");
        this.restBase = base.endsWith("/otel") ? base.substring(0, base.length() - "/otel".length()) : null;
        this.project = config.getProject();
        headers.put("projectName", config.getProject());
        if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
            headers.put("Authorization", config.getApiKey());
        }
        if (config.getWorkspace() != null && !config.getWorkspace().isBlank()) {
            headers.put("Comet-Workspace", config.getWorkspace());
        }
        Thread worker = new Thread(this::run, "rex-opik-export");
        worker.setDaemon(true);
        worker.start();
    }

    public void export(SpanData span) {
        enqueue(span);
    }

    public void updateTrace(TraceUpdate update) {
        enqueue(update);
    }

    private void enqueue(Object item) {
        inFlight.incrementAndGet();
        if (!queue.offer(item)) {
            inFlight.decrementAndGet();
        }
    }

    /** Waits until everything enqueued so far has been sent (or dropped), up to the timeout. */
    public void flush(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (inFlight.get() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void run() {
        List<Object> batch = new ArrayList<>();
        while (true) {
            try {
                Object first = queue.poll(1, TimeUnit.SECONDS);
                if (first == null) continue;
                batch.add(first);
                queue.drainTo(batch, MAX_BATCH - 1);
                List<SpanData> spans = new ArrayList<>();
                for (Object item : batch) {
                    if (item instanceof SpanData span) {
                        spans.add(span);
                    } else if (item instanceof TraceUpdate update) {
                        // Spans queued before the update must be visible to it.
                        send(spans);
                        spans.clear();
                        apply(update);
                    }
                }
                send(spans);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                reportFailure(e.getClass().getSimpleName()
                        + (e.getMessage() != null ? ": " + e.getMessage() : "") + " (" + tracesUri + ")");
            } finally {
                inFlight.addAndGet(-batch.size());
                batch.clear();
            }
        }
    }

    private void apply(TraceUpdate update) throws Exception {
        JsonNode trace = restBase == null ? null : findTrace(update.taskId());
        String traceId = trace == null ? null : trace.path("id").asText(null);
        if (traceId == null) {
            send(List.of(update.fallbackRoot()));
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("project_name", project);
        body.put("output", Map.of("value", update.output() == null ? "" : update.output()));
        body.put("end_time", Instant.ofEpochMilli(update.endMs()).toString());
        if (update.tokenSummaryJson() != null && trace.path("metadata").isObject()) {
            // PATCH replaces metadata wholesale, so send back what is there plus the summary.
            ObjectNode metadata = trace.path("metadata").deepCopy();
            try {
                metadata.set("token_summary", MAPPER.readTree(update.tokenSummaryJson()));
            } catch (Exception e) {
                metadata.put("token_summary", update.tokenSummaryJson());
            }
            body.put("metadata", metadata);
        }
        if (update.error() != null) {
            body.put("error_info", Map.of(
                    "exception_type", "TaskError", "message", update.error(), "traceback", update.error()));
        }
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(restBase + "/traces/" + traceId))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        restHeaders(req);
        HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300) {
            reportFailure("HTTP " + resp.statusCode() + " updating trace " + traceId);
        }
    }

    private JsonNode findTrace(String taskId) throws Exception {
        String filters = MAPPER.writeValueAsString(List.of(Map.of(
                "field", "metadata", "key", "task_id", "operator", "=", "value", taskId)));
        URI uri = URI.create(restBase + "/traces?size=1"
                + "&project_name=" + URLEncoder.encode(project, StandardCharsets.UTF_8)
                + "&filters=" + URLEncoder.encode(filters, StandardCharsets.UTF_8));
        HttpRequest.Builder req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET();
        restHeaders(req);
        HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300) return null;
        JsonNode first = MAPPER.readTree(resp.body()).path("content").path(0);
        return first.path("id").isTextual() ? first : null;
    }

    private void restHeaders(HttpRequest.Builder req) {
        headers.forEach((k, v) -> {
            if (!k.equals("projectName")) req.header(k, v);
        });
    }

    private void send(List<SpanData> batch) throws Exception {
        if (batch.isEmpty()) return;
        HttpRequest.Builder req = HttpRequest.newBuilder(tracesUri)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(toOtlp(batch))));
        headers.forEach(req::header);
        HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300) {
            reportFailure("HTTP " + resp.statusCode() + " from " + tracesUri);
        } else {
            failureReported = false;
        }
    }

    private void reportFailure(String message) {
        if (failureReported) return;
        failureReported = true;
        System.err.println("[warn] Opik trace export failed (traces are dropped until it recovers): " + message);
    }

    private static Map<String, Object> toOtlp(List<SpanData> batch) {
        List<Map<String, Object>> spans = new ArrayList<>(batch.size());
        for (SpanData s : batch) {
            Map<String, Object> span = new LinkedHashMap<>();
            span.put("traceId", s.getTraceId());
            span.put("spanId", s.getSpanId());
            if (s.getParentSpanId() != null) span.put("parentSpanId", s.getParentSpanId());
            span.put("name", s.getName());
            span.put("kind", 1);
            span.put("startTimeUnixNano", Long.toString(s.getStartMs() * 1_000_000L));
            span.put("endTimeUnixNano", Long.toString(Math.max(s.getEndMs(), s.getStartMs()) * 1_000_000L));
            List<Map<String, Object>> attrs = new ArrayList<>();
            s.getAttributes().forEach((k, v) -> attrs.add(Map.of("key", k, "value", otlpValue(v))));
            span.put("attributes", attrs);
            if (s.getError() != null) {
                span.put("status", Map.of("code", 2, "message", s.getError()));
            }
            spans.add(span);
        }
        return Map.of("resourceSpans", List.of(Map.of(
                "resource", Map.of("attributes", List.of(
                        Map.of("key", "service.name", "value", Map.of("stringValue", "regnexe-cli")))),
                "scopeSpans", List.of(Map.of(
                        "scope", Map.of("name", "regnexe-cli"),
                        "spans", spans)))));
    }

    private static Map<String, Object> otlpValue(Object v) {
        if (v instanceof Long || v instanceof Integer) return Map.of("intValue", v.toString());
        if (v instanceof Boolean b) return Map.of("boolValue", b);
        return Map.of("stringValue", String.valueOf(v));
    }
}
