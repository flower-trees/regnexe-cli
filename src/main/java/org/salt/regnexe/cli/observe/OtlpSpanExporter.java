package org.salt.regnexe.cli.observe;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.salt.regnexe.cli.config.RexConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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

    private final URI tracesUri;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final BlockingQueue<SpanData> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile boolean failureReported;

    public OtlpSpanExporter(RexConfig.OpikConfig config) {
        String base = config.getEndpoint().replaceAll("/+$", "");
        this.tracesUri = URI.create(base + "/v1/traces");
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
        inFlight.incrementAndGet();
        if (!queue.offer(span)) {
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
        List<SpanData> batch = new ArrayList<>();
        while (true) {
            try {
                SpanData first = queue.poll(1, TimeUnit.SECONDS);
                if (first == null) continue;
                batch.add(first);
                queue.drainTo(batch, MAX_BATCH - 1);
                send(batch);
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

    private void send(List<SpanData> batch) throws Exception {
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
