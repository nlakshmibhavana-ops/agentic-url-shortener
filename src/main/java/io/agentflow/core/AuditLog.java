package io.agentflow.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Append-only, hash-chained audit trail. Each record embeds the previous record's hash, so editing
 * or deleting any line breaks verification from that point on. One trace per run, one span per attempt.
 */
public class AuditLog {

    public static final String GENESIS = "0".repeat(64);

    private final Path path;
    private final String traceId;
    private String prev = GENESIS;
    private long seq;
    private volatile Consumer<JsonNode> listener;

    public AuditLog(Path path, String runId) {
        this.path = path;
        this.traceId = runId;
        for (JsonNode record : read(path)) {
            prev = record.get("hash").asText();
            seq = record.get("seq").asLong();
        }
    }

    public void setListener(Consumer<JsonNode> listener) {
        this.listener = listener;
    }

    public JsonNode record(String event, String actor, String node, String spanId, Map<String, Object> data) {
        ObjectNode record;
        synchronized (this) {
            record = Json.MAPPER.createObjectNode();
            record.put("seq", ++seq);
            record.put("ts", Json.now());
            record.put("trace_id", traceId);
            record.put("span_id", spanId);
            record.put("event", event);
            record.put("actor", actor == null ? "orchestrator" : actor);
            record.put("node", node);
            record.set("data", Json.tree(data == null ? Map.of() : data));
            record.put("prev_hash", prev);
            record.put("hash", digest(record));
            prev = record.get("hash").asText();
            try {
                Files.writeString(path, Json.write(record) + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        Consumer<JsonNode> l = listener;
        if (l != null) {
            l.accept(record);
        }
        return record;
    }

    public JsonNode record(String event, String node, Map<String, Object> data) {
        return record(event, null, node, null, data);
    }

    /** An attempt span: emits name.start now and name.end (with duration and outcome) on close. */
    public final class Span implements AutoCloseable {
        public final String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        public final Map<String, Object> outcome = new LinkedHashMap<>();
        private final String name;
        private final String node;
        private final long started = System.nanoTime();

        Span(String name, String node, Map<String, Object> data) {
            this.name = name;
            this.node = node;
            outcome.put("status", "ok");
            record(name + ".start", null, node, id, data);
        }

        @Override
        public void close() {
            Map<String, Object> end = new LinkedHashMap<>(outcome);
            end.put("duration_ms", Math.round((System.nanoTime() - started) / 100_000.0) / 10.0);
            record(name + ".end", null, node, id, end);
        }
    }

    public Span span(String name, String node, Map<String, Object> data) {
        return new Span(name, node, data);
    }

    static String digest(JsonNode recordWithoutHash) {
        ObjectNode copy = recordWithoutHash.deepCopy();
        copy.remove("hash");
        return Json.sha256(Json.write(Json.MAPPER.convertValue(copy, Object.class)));
    }

    /** Verifies the chain. Returns null when intact, else a description of the first bad record. */
    public static String verify(Path path) {
        String prev = GENESIS;
        int n = 0;
        for (JsonNode record : read(path)) {
            n++;
            if (!record.get("prev_hash").asText().equals(prev)) {
                return "record " + n + ": chain broken (prev_hash mismatch)";
            }
            if (!digest(record).equals(record.get("hash").asText())) {
                return "record " + n + ": content was modified";
            }
            prev = record.get("hash").asText();
        }
        return null;
    }

    public static List<JsonNode> read(Path path) {
        List<JsonNode> out = new ArrayList<>();
        if (!Files.exists(path)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    out.add(Json.parse(line));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }
}
