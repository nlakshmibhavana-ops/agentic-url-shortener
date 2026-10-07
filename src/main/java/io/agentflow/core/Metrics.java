package io.agentflow.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/** Reliability metrics, derived from the audit trail (the single source of truth). */
public final class Metrics {

    private Metrics() {
    }

    private static double round(double v, int places) {
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }

    public static Map<String, Object> compute(Path auditPath) {
        List<JsonNode> events = AuditLog.read(auditPath);
        List<JsonNode> attempts = new ArrayList<>();
        int ok = 0;
        int applied = 0;
        int rollbacks = 0;
        int retries = 0;
        Set<String> nodeOk = new HashSet<>();
        Set<String> nodeFailed = new HashSet<>();
        Map<String, Double> firstFailure = new HashMap<>();
        List<Double> recoveries = new ArrayList<>();
        List<Double> sessions = new ArrayList<>();
        Double sessionStart = null;
        List<Double> waits = new ArrayList<>();
        Map<String, Double> stageLatency = new TreeMap<>();
        long tokens = 0;
        int replans = 0;
        int invalidations = 0;
        int reused = 0;
        int blocks = 0;
        for (JsonNode e : events) {
            String ev = e.get("event").asText();
            String node = e.path("node").asText(null);
            JsonNode d = e.get("data");
            switch (ev) {
                case "attempt.end" -> {
                    String result = d.path("result").asText();
                    if (result.equals("succeeded") || result.equals("failed")) {
                        attempts.add(e);
                        if (result.equals("succeeded")) {
                            ok++;
                        }
                        stageLatency.merge(node, d.path("duration_ms").asDouble(), Double::sum);
                        tokens += d.path("tokens").asLong();
                    }
                    if (result.equals("failed")) {
                        firstFailure.putIfAbsent(node, e.get("ts").asDouble());
                    }
                    // Recovered = the first later attempt that passes its gates. Time a verified
                    // change then waits for a human approver is reported separately, not as MTTR.
                    if ((result.equals("succeeded") || result.equals("waiting")) && firstFailure.containsKey(node)) {
                        recoveries.add(e.get("ts").asDouble() - firstFailure.remove(node));
                    }
                }
                case "change.applied" -> applied++;
                case "change.rolled_back" -> rollbacks++;
                case "attempt.retry" -> retries++;
                case "node.succeeded" -> nodeOk.add(node);
                case "node.failed" -> nodeFailed.add(node);
                case "plan.revised" -> replans++;
                case "node.invalidated" -> invalidations++;
                case "node.reused" -> reused++;
                case "policy.blocked" -> blocks++;
                case "approval.decided" -> waits.add(d.path("waited_s").asDouble());
                case "run.session.start" -> sessionStart = e.get("ts").asDouble();
                case "run.session.end" -> {
                    if (sessionStart != null) {
                        sessions.add(e.get("ts").asDouble() - sessionStart);
                        sessionStart = null;
                    }
                }
                default -> {
                }
            }
        }
        Set<String> failedOnly = new HashSet<>(nodeFailed);
        failedOnly.removeAll(nodeOk);
        Set<String> touched = new HashSet<>(nodeOk);
        touched.addAll(nodeFailed);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodes_succeeded", nodeOk.size());
        m.put("nodes_failed", failedOnly.size());
        m.put("node_success_rate", round(nodeOk.size() / (double) Math.max(1, touched.size()), 3));
        m.put("attempts", attempts.size());
        m.put("attempt_success_rate", round(ok / (double) Math.max(1, attempts.size()), 3));
        m.put("retries", retries);
        m.put("changes_applied", applied);
        m.put("rollbacks", rollbacks);
        m.put("rollback_rate", round(rollbacks / (double) Math.max(1, applied), 3));
        m.put("mttr_s", recoveries.isEmpty() ? null
                : round(recoveries.stream().mapToDouble(Double::doubleValue).average().orElse(0), 3));
        m.put("recoveries", recoveries.size());
        m.put("replans", replans);
        m.put("invalidations", invalidations);
        m.put("reused_nodes", reused);
        m.put("policy_blocks", blocks);
        m.put("approvals", waits.size());
        m.put("approval_wait_s", waits.isEmpty() ? null
                : round(waits.stream().mapToDouble(Double::doubleValue).average().orElse(0), 1));
        m.put("active_time_s", round(sessions.stream().mapToDouble(Double::doubleValue).sum(), 3));
        m.put("wall_time_s", events.isEmpty() ? 0
                : round(events.getLast().get("ts").asDouble() - events.getFirst().get("ts").asDouble(), 3));
        m.put("sessions", sessions.size());
        m.put("llm_tokens", tokens);
        Map<String, Double> latency = new TreeMap<>();
        stageLatency.forEach((k, v) -> latency.put(k, round(v, 1)));
        m.put("stage_latency_ms", latency);
        return m;
    }

    public static Map<String, Object> aggregate(Path runsDir) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (Files.isDirectory(runsDir)) {
            try (Stream<Path> dirs = Files.list(runsDir)) {
                for (Path dir : dirs.sorted().toList()) {
                    Path state = dir.resolve("state.json");
                    if (!Files.exists(state)) {
                        continue;
                    }
                    JsonNode s = Json.parse(Files.readString(state));
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("run_id", s.get("runId").asText());
                    row.put("scenario", s.get("scenario").get("id").asText());
                    row.put("status", s.get("status").asText());
                    row.putAll(compute(dir.resolve("audit.jsonl")));
                    rows.add(row);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        List<Map<String, Object>> finished = rows.stream()
                .filter(r -> Set.of("succeeded", "failed", "stopped").contains((String) r.get("status"))).toList();
        long succeeded = finished.stream().filter(r -> r.get("status").equals("succeeded")).count();
        int rollbacks = rows.stream().mapToInt(r -> (int) r.get("rollbacks")).sum();
        int applied = rows.stream().mapToInt(r -> (int) r.get("changes_applied")).sum();
        List<Double> mttrs = rows.stream().filter(r -> r.get("mttr_s") != null).map(r -> (Double) r.get("mttr_s")).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runs", rows.size());
        out.put("run_success_rate", round(succeeded / (double) Math.max(1, finished.size()), 3));
        out.put("mean_active_time_s", rows.isEmpty() ? null
                : round(rows.stream().mapToDouble(r -> (double) r.get("active_time_s")).average().orElse(0), 2));
        out.put("total_retries", rows.stream().mapToInt(r -> (int) r.get("retries")).sum());
        out.put("total_rollbacks", rollbacks);
        out.put("rollback_rate", round(rollbacks / (double) Math.max(1, applied), 3));
        out.put("mean_mttr_s", mttrs.isEmpty() ? null
                : round(mttrs.stream().mapToDouble(Double::doubleValue).average().orElse(0), 3));
        out.put("per_run", rows);
        return out;
    }
}
