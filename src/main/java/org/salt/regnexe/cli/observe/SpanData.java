package org.salt.regnexe.cli.observe;

import lombok.Data;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/** One span under construction; exported once {@link #endMs} is set. */
@Data
public class SpanData {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String traceId;
    private final String spanId = randomHex(8);
    private final String parentSpanId;
    private final String name;
    private final long startMs;
    private long endMs;
    private String error;
    private final Map<String, Object> attributes = new LinkedHashMap<>();

    public SpanData(String traceId, String parentSpanId, String name, long startMs) {
        this.traceId = traceId;
        this.parentSpanId = parentSpanId;
        this.name = name;
        this.startMs = startMs;
    }

    public SpanData attr(String key, Object value) {
        if (value != null) attributes.put(key, value);
        return this;
    }

    public static String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte b : buf) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
