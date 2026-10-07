package io.agentflow.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentflow.agents.AgentContext;
import io.agentflow.agents.Agents;
import io.agentflow.core.Approvals;
import io.agentflow.core.AuditLog;
import io.agentflow.core.ContextStore;
import io.agentflow.core.Gates;
import io.agentflow.core.Graph;
import io.agentflow.core.Home;
import io.agentflow.core.Json;
import io.agentflow.core.Playbooks;
import io.agentflow.core.Policy;
import io.agentflow.core.Workspace;
import io.agentflow.llm.LlmClient;
import io.agentflow.model.AgentException;
import io.agentflow.model.AgentResult;
import io.agentflow.model.ChangeSet;
import io.agentflow.model.Decision;
import io.agentflow.model.GateResult;
import io.agentflow.model.Impact;
import io.agentflow.model.Node;
import io.agentflow.model.NodeStatus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The orchestrator: runs the delivery graph under governance.
 *
 * <p>Per node: entry gates, then the agent (bounded retries, fallback agent), policy check, apply
 * under the workspace lock, exit gates, human approval if required (after validation), then commit,
 * or roll back and retry with the failure as feedback.
 *
 * <p>Across nodes: ready nodes run in parallel up to maxParallel; join stages synchronise; changed
 * artifacts invalidate their consumers; a changed plan is merged into the live graph (dynamic
 * re-planning); a failed critical node, a STOP file, an interrupt or an exhausted budget triggers a
 * safe stop. State is persisted after every step, so any run can resume after a pause or a crash.
 */
public class Engine {

    private static final Set<String> MUTATING_KINDS = Set.of("task");

    private final RunState state;
    private final Path dir;
    private final AuditLog audit;
    private final Approvals approvals;
    private final Policy policy;
    private final int maxParallel;
    private final LlmClient llm;
    /** The merge queue: one change set is applied and validated at a time. */
    private final ReentrantLock workspaceLock = new ReentrantLock(true);
    /** Guards graph and node bookkeeping shared between the scheduler and worker threads. */
    private final ReentrantLock stateLock = new ReentrantLock();
    private volatile String haltReason;
    private volatile boolean stopRequested;
    private double sessionStarted;

    public Engine(Path runDir, int maxParallel, LlmClient llm, Consumer<JsonNode> onEvent) {
        this.state = new RunState(runDir);
        this.dir = runDir;
        this.audit = new AuditLog(runDir.resolve("audit.jsonl"), state.runId);
        this.audit.setListener(onEvent);
        this.approvals = new Approvals(runDir.resolve("approvals.json"), audit);
        this.policy = new Policy(state.baseline);
        this.maxParallel = maxParallel;
        this.llm = llm;
    }

    public RunState state() {
        return state;
    }

    private Graph graph() {
        return state.graph;
    }

    private ContextStore store() {
        return state.store;
    }

    private Workspace workspace() {
        return state.workspace();
    }

    private static Map<String, Object> data(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    // ------------------------------------------------------------------ creation

    public static Path create(ObjectNode scenario, Path runsDir, String runId) {
        String id = runId != null ? runId
                : LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-")) + scenario.get("id").asText();
        Path dir = runsDir.resolve(id);
        if (Files.exists(dir)) {
            throw new IllegalStateException("run " + id + " already exists");
        }
        try {
            for (String sub : List.of("workspace", "changes", "undo", "artifacts")) {
                Files.createDirectories(dir.resolve(sub));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Workspace ws = new Workspace(dir.resolve("workspace"));
        String base = scenario.path("base").asText(null);
        if (base != null && !base.isEmpty()) {
            ws.seed(base.startsWith("playbook:") ? Playbooks.dir(base.substring(9)).resolve("files")
                    : Home.root().resolve(base));
        }
        RunState.File f = new RunState.File();
        f.runId = id;
        f.scenario = scenario;
        f.baseline = ws.files();
        f.createdAt = Json.now();
        f.nodes = Scenario.stageNodes(scenario);
        f.context.put("requirement_text", scenario.get("requirement").asText(), "scenario", Map.of());
        f.context.put("human_answers", Map.of(), "scenario", Map.of());
        new Graph(f.nodes); // validates the template
        RunState.write(dir, f);
        new AuditLog(dir.resolve("audit.jsonl"), id).record("run.created", null, data("scenario",
                scenario.get("id").asText(), "provider", scenario.path("provider").asText("deterministic"),
                "baseline_files", f.baseline.size()));
        return dir;
    }

    // ------------------------------------------------------------------ public API

    /** A human answer is just a new upstream artifact; invalidation does the rest. */
    public void submitAnswer(String question, String option, String by) {
        Map<String, Object> answers = Json.convert(store().get("human_answers"), Map.class);
        answers.put(question, data("option", option, "by", by, "ts", Json.now()));
        store().put("human_answers", answers, "human:" + by, Map.of());
        audit.record("human.answered", "human:" + by, null, null, data("question", question, "option", option));
        store().addDecision(new Decision(question + " answered: " + option, "human clarification", List.of())
                .by("human:" + by).at("requirements"));
        propagate("human_answers");
        state.save();
    }

    public void requestStop(String reason) {
        if (haltReason == null) {
            haltReason = "safe-stop requested (" + reason + ")";
        }
        stopRequested = true;
    }

    public String execute() {
        sessionStarted = Json.now();
        audit.record("run.session.start", null, data("status", state.status));
        recoverInflight();
        for (Node n : graph().all()) {
            if (n.status == NodeStatus.WAITING || n.status == NodeStatus.STOPPED || n.status == NodeStatus.RUNNING) {
                n.status = NodeStatus.PENDING;
                n.waitReason = null;
            }
        }
        state.status = "running";
        state.stopReason = null;
        state.save();
        loop();
        state.status = finalStatus();
        state.save();
        List<String> waiting = graph().all().stream().filter(n -> n.status == NodeStatus.WAITING).map(n -> n.id).toList();
        audit.record("run.session.end", null, data("status", state.status, "stop_reason", state.stopReason,
                "waiting", waiting));
        Report.write(dir);
        return state.status;
    }

    // ------------------------------------------------------------------ scheduling

    private void loop() {
        Map<Future<?>, Node> running = new HashMap<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            while (true) {
                checkStopConditions();
                stateLock.lock();
                try {
                    if (haltReason == null) {
                        for (Node n : graph().ready()) {
                            if (running.size() >= maxParallel) {
                                break;
                            }
                            n.status = NodeStatus.RUNNING;
                            running.put(pool.submit(() -> runNode(n)), n);
                        }
                    }
                    state.save();
                } finally {
                    stateLock.unlock();
                }
                if (running.isEmpty()) {
                    break;
                }
                if (stopRequested) {
                    running.keySet().forEach(f -> f.cancel(true));
                }
                sleep(200);
                for (Future<?> f : new ArrayList<>(running.keySet())) {
                    if (!f.isDone()) {
                        continue;
                    }
                    Node n = running.remove(f);
                    stateLock.lock();
                    try {
                        if (f.isCancelled()) {
                            n.status = NodeStatus.STOPPED;
                            audit.record("node.stopped", n.id, Map.of());
                        } else {
                            try {
                                f.get();
                            } catch (Exception e) {
                                n.status = NodeStatus.FAILED;
                                n.error = String.valueOf(e.getCause() != null ? e.getCause() : e);
                                audit.record("node.failed", n.id, data("error", n.error));
                                onFailure(n);
                            }
                        }
                        state.save();
                    } finally {
                        stateLock.unlock();
                    }
                }
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void checkStopConditions() {
        Path stop = dir.resolve("STOP");
        if (Files.exists(stop)) {
            try {
                Files.delete(stop);
            } catch (IOException ignored) {
                // stopping anyway
            }
            requestStop("STOP file");
        }
        JsonNode budgets = state.scenario.path("budgets");
        int used = graph().all().stream().mapToInt(n -> n.attempts).sum();
        if (used >= budgets.path("max_attempts").asInt(Integer.MAX_VALUE)) {
            requestStop("attempt budget " + budgets.get("max_attempts").asInt() + " exhausted");
        }
        if (Json.now() - sessionStarted > budgets.path("max_active_s").asDouble(Double.MAX_VALUE)) {
            requestStop("time budget " + budgets.get("max_active_s").asInt() + "s exhausted");
        }
        if (haltReason != null && state.stopReason == null) {
            state.stopReason = haltReason;
            audit.record("run.safe_stop", null, data("reason", haltReason));
        }
    }

    private String finalStatus() {
        Set<NodeStatus> statuses = new TreeSet<>();
        graph().all().forEach(n -> statuses.add(n.status));
        if (graph().all().stream().anyMatch(n -> n.status == NodeStatus.FAILED && n.critical)) {
            return "failed";
        }
        if (haltReason != null || statuses.contains(NodeStatus.STOPPED)) {
            return "stopped";
        }
        if (statuses.contains(NodeStatus.WAITING)) {
            return "waiting";
        }
        if (graph().isFinished()) {
            return statuses.contains(NodeStatus.FAILED) ? "degraded" : "succeeded";
        }
        return "stalled";
    }

    // ------------------------------------------------------------------ node execution

    private Gates.Context gctx(Node node) {
        return new Gates.Context(node, store(), workspace(), state.scenario, dir);
    }

    private void runNode(Node node) {
        if (node.startedAt == null) {
            node.startedAt = Json.now();
        }
        for (String spec : node.entryGates) {
            GateResult r = Gates.evaluate(spec, gctx(node));
            audit.record("gate.entry", node.id, data("gate", spec, "passed", r.passed(), "detail", r.detail()));
            if (!r.passed()) {
                withState(() -> {
                    if (r.human()) {
                        waitFor(node, r.detail());
                    } else {
                        node.status = NodeStatus.FAILED;
                        node.error = r.detail();
                        audit.record("node.failed", node.id, data("error", r.detail()));
                        onFailure(node);
                    }
                });
                return;
            }
        }
        if (node.agent.equals("none")) { // a pure human checkpoint whose gate has now opened
            withState(() -> {
                node.attempts++;
                succeed(node, new AgentResult(), Map.of(), List.of());
            });
            return;
        }
        if (node.pending != null && resumePending(node)) {
            return;
        }
        Map<String, Integer> consumed = new LinkedHashMap<>();
        node.inputs.forEach(a -> {
            if (store().has(a)) {
                consumed.put(a, store().version(a));
            }
        });
        String feedback = null;
        boolean useFallback = false;
        while (node.attempts < node.maxAttempts) {
            if (stopRequested || Thread.currentThread().isInterrupted()) {
                withState(() -> node.status = NodeStatus.STOPPED);
                return;
            }
            int attempt = node.attempts + 1;
            boolean last = attempt == node.maxAttempts;
            String agentName = node.fallbackAgent != null && (useFallback || last) ? node.fallbackAgent : node.agent;
            Map<String, Object> outcome = attempt(node, attempt, agentName, consumed, feedback);
            String result = (String) outcome.get("result");
            if (List.of("succeeded", "waiting", "rejected").contains(result)) {
                return;
            }
            node.attempts++;
            feedback = (String) outcome.get("feedback");
            useFallback |= Boolean.TRUE.equals(outcome.get("agent_error"));
            if (node.attempts < node.maxAttempts) {
                audit.record("attempt.retry", node.id, data("next_attempt", node.attempts + 1, "reason", feedback));
                sleep((long) Math.min(50 * Math.pow(2, node.attempts), 2000)); // bounded backoff
            }
        }
        String reason = feedback;
        withState(() -> {
            node.status = NodeStatus.FAILED;
            node.error = reason;
            audit.record("node.failed", node.id, data("error", reason, "attempts", node.attempts));
            onFailure(node);
        });
    }

    private void withState(Runnable r) {
        stateLock.lock();
        try {
            r.run();
        } finally {
            stateLock.unlock();
        }
    }

    private Map<String, Object> attempt(Node node, int attempt, String agentName, Map<String, Integer> consumed,
            String feedback) {
        try (AuditLog.Span span = audit.span("attempt", node.id, data("attempt", attempt, "agent", agentName))) {
            AgentContext ctx = new AgentContext(node, store(), workspace(), state.scenario, attempt, dir, workspaceLock,
                    llm, feedback, state.baseline);
            AgentResult result;
            try {
                result = Agents.create(agentName).run(ctx);
            } catch (AgentException | IllegalArgumentException | IllegalStateException | UncheckedIOException e) {
                String msg = agentName + ": " + e.getMessage();
                span.outcome.put("result", "failed");
                span.outcome.put("reason", msg);
                return data("result", "failed", "feedback", msg, "agent_error", true);
            }
            span.outcome.put("tokens", result.tokens);
            for (String note : result.notes) {
                audit.record("agent.note", "agent:" + agentName, node.id, span.id, data("note", note));
            }
            List<String[]> staged = stage(node, result, consumed);
            Workspace.UndoLog undo = null;
            Map<String, Object> applied = null;
            workspaceLock.lock();
            try {
                if (result.changeset != null) {
                    applied = applyChange(node, result.changeset, attempt, span.id);
                    if (!"applied".equals(applied.get("result"))) {
                        unstage(staged);
                        span.outcome.put("result", applied.get("result"));
                        span.outcome.put("reason", applied.get("feedback"));
                        return applied;
                    }
                    undo = (Workspace.UndoLog) applied.get("undo");
                }
                List<GateResult> gates = new ArrayList<>();
                for (String g : node.exitGates) {
                    GateResult r = Gates.evaluate(g, gctx(node));
                    gates.add(r);
                    audit.record("gate.exit", null, node.id, span.id, data("gate", r.gate(), "passed", r.passed(),
                            "detail", r.detail()));
                }
                List<GateResult> failed = gates.stream().filter(g -> !g.passed()).toList();
                if (!failed.isEmpty()) {
                    if (undo != null) {
                        workspace().rollback(undo);
                        dropUndo(node, "inflight");
                        audit.record("change.rolled_back", null, node.id, span.id, data("paths",
                                new TreeSet<>(undo.before().keySet())));
                    }
                    unstage(staged);
                    String msg = String.join("; ", failed.stream().map(g -> g.gate() + ": " + g.detail()).toList());
                    span.outcome.put("result", "failed");
                    span.outcome.put("reason", msg);
                    return data("result", "failed", "feedback", msg);
                }
                @SuppressWarnings("unchecked")
                List<String> reasons = applied == null ? List.of() : (List<String>) applied.get("reasons");
                if (undo != null && !reasons.isEmpty()) {
                    // Humans approve verified changes: the gates above are the evidence.
                    String evidence = String.join("\n", gates.stream().map(g -> "[" + (g.passed() ? "x" : " ") + "] "
                            + g.gate() + ": " + abbreviate(g.detail(), 200)).toList());
                    String summary = applied.get("summary") + "\nValidation evidence:\n" + evidence;
                    String decision = approvals.check(node.id, (String) applied.get("digest"), summary, reasons);
                    if (decision.equals(Approvals.PENDING)) {
                        Map<String, Object> app = applied;
                        withState(() -> parkForApproval(node, app, summary, consumed, staged, result));
                        span.outcome.put("result", "waiting");
                        return data("result", "waiting");
                    }
                    if (decision.equals(Approvals.REJECTED)) {
                        Workspace.UndoLog u = undo;
                        String digest = (String) applied.get("digest");
                        withState(() -> reject(node, u, staged, digest));
                        span.outcome.put("result", "rejected");
                        return data("result", "rejected");
                    }
                }
                if (undo != null) {
                    commitUndo(node);
                }
            } finally {
                workspaceLock.unlock();
            }
            withState(() -> {
                node.attempts++;
                succeed(node, result, consumed, staged.stream().map(s -> s[0]).toList());
            });
            span.outcome.put("result", "succeeded");
            return data("result", "succeeded");
        }
    }

    private Map<String, Object> applyChange(Node node, ChangeSet cs, int attempt, String span) {
        Map<String, String[]> planned;
        try {
            planned = workspace().preview(cs);
        } catch (Workspace.WorkspaceException e) {
            return data("result", "failed", "feedback", "change does not apply: " + e.getMessage());
        }
        Policy.Verdict verdict = policy.evaluate(node, cs, planned);
        audit.record("policy.evaluated", null, node.id, span, data("digest", cs.digest(), "findings",
                verdict.findings().stream().map(Policy::asMap).toList()));
        if (!verdict.blocking().isEmpty()) {
            String msg = String.join("; ", verdict.blocking().stream()
                    .map(f -> f.rule() + " " + f.path() + ": " + f.message()).toList());
            audit.record("policy.blocked", null, node.id, span, data("reason", msg));
            return data("result", "failed", "feedback", "policy blocked: " + msg);
        }
        List<String> reasons = new ArrayList<>(verdict.approvals().stream()
                .map(f -> f.rule() + " " + f.path() + ": " + f.message()).toList());
        if (node.impact == Impact.HIGH) {
            reasons.addFirst("high-impact stage '" + node.id + "'");
        }
        Map<String, String> before = new LinkedHashMap<>();
        planned.forEach((p, ba) -> before.put(p, ba[0]));
        writeUndo(node, before, "inflight");
        Workspace.Applied applied = workspace().apply(cs);
        try {
            Files.writeString(dir.resolve("changes").resolve(node.id + ".a" + attempt + ".diff"), applied.diff());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int[] stats = Workspace.diffStats(applied.diff());
        audit.record("change.applied", null, node.id, span, data("digest", cs.digest(), "summary", cs.summary(),
                "candidate", cs.candidate(), "paths", cs.paths(), "added", stats[0], "removed", stats[1]));
        return data("result", "applied", "undo", applied.undo(), "reasons", reasons, "digest", cs.digest(),
                "summary", node.title + "\nchange: " + cs.summary() + " (digest " + cs.digest() + ")\nfiles: "
                        + String.join(", ", planned.keySet()) + readinessLines(node));
    }

    private String readinessLines(Node node) {
        if (!node.id.equals("release") || !store().has("release_readiness")) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        store().get("release_readiness").get("checks").forEach(c -> sb.append("\n[").append(
                c.get("passed").asBoolean() ? "x" : " ").append("] ").append(c.get("check").asText()).append(": ")
                .append(c.get("detail").asText()));
        return sb.toString();
    }

    private static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ------------------------------------------------------------------ approvals

    private void parkForApproval(Node node, Map<String, Object> applied, String summary, Map<String, Integer> consumed,
            List<String[]> staged, AgentResult result) {
        moveUndo(node, "inflight", "pending");
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("digest", applied.get("digest"));
        pending.put("summary", summary);
        pending.put("reasons", applied.get("reasons"));
        pending.put("consumed", consumed);
        pending.put("produced", staged.stream().map(s -> s[0]).toList());
        pending.put("decisions", result.decisions);
        node.pending = Json.convert(pending, Map.class);
        @SuppressWarnings("unchecked")
        List<String> reasons = (List<String>) applied.get("reasons");
        waitFor(node, "approval required: " + String.join("; ", reasons));
    }

    private void reject(Node node, Workspace.UndoLog undo, List<String[]> staged, String digest) {
        workspace().rollback(undo);
        dropUndo(node, "inflight");
        dropUndo(node, "pending");
        audit.record("change.rolled_back", node.id, data("paths", new TreeSet<>(undo.before().keySet()), "reason",
                "rejected by human approver"));
        unstage(staged);
        node.status = NodeStatus.FAILED;
        node.error = "rejected by human approver";
        node.pending = null;
        audit.record("node.rejected", node.id, data("digest", digest));
        onFailure(node);
    }

    /** Continues a node parked for approval. Returns true if it was handled. */
    @SuppressWarnings("unchecked")
    private boolean resumePending(Node node) {
        Map<String, Object> pa = node.pending;
        String decision = approvals.check(node.id, (String) pa.get("digest"), (String) pa.get("summary"),
                (List<String>) pa.get("reasons"));
        if (decision.equals(Approvals.PENDING)) {
            withState(() -> waitFor(node, "approval required: " + String.join("; ", (List<String>) pa.get("reasons"))));
            return true;
        }
        Workspace.UndoLog undo = new Workspace.UndoLog(readUndo(node, "pending"));
        if (decision.equals(Approvals.REJECTED)) {
            withState(() -> reject(node, undo, List.of(), (String) pa.get("digest")));
            return true;
        }
        workspaceLock.lock();
        try { // re-check: the evidence must still hold at the moment of approval
            for (String g : node.exitGates) {
                GateResult r = Gates.evaluate(g, gctx(node));
                audit.record("gate.exit", node.id, data("gate", r.gate(), "passed", r.passed(), "detail", r.detail(),
                        "recheck", true));
                if (!r.passed()) {
                    workspace().rollback(undo);
                    dropUndo(node, "pending");
                    audit.record("change.rolled_back", node.id, data("paths", new TreeSet<>(undo.before().keySet()),
                            "reason", "evidence no longer holds after approval"));
                    node.pending = null;
                    return false;
                }
            }
            moveUndo(node, "pending", null);
        } finally {
            workspaceLock.unlock();
        }
        withState(() -> {
            node.attempts++;
            audit.record("approval.applied", node.id, data("digest", pa.get("digest")));
            node.pending = null;
            AgentResult result = new AgentResult();
            for (Object d : (List<Object>) pa.get("decisions")) {
                result.decisions.add(Json.convert(d, Decision.class));
            }
            succeed(node, result, Json.convert(pa.get("consumed"), Map.class), (List<String>) pa.get("produced"));
        });
        return true;
    }

    // ------------------------------------------------------------------ outcomes

    private List<String[]> stage(Node node, AgentResult result, Map<String, Integer> consumed) {
        List<String[]> staged = new ArrayList<>();
        result.artifacts.forEach((name, content) -> {
            boolean changed = store().put(name, content, node.id, consumed);
            staged.add(new String[] {name, Boolean.toString(changed)});
        });
        return staged;
    }

    private void unstage(List<String[]> staged) {
        staged.forEach(s -> {
            if (Boolean.parseBoolean(s[1])) {
                store().dropLatest(s[0]);
            }
        });
    }

    private void succeed(Node node, AgentResult result, Map<String, Integer> consumed, List<String> produced) {
        node.status = NodeStatus.SUCCEEDED;
        node.finishedAt = Json.now();
        node.error = null;
        node.waitReason = null;
        node.consumed = new LinkedHashMap<>(consumed);
        node.produced = new LinkedHashMap<>();
        produced.forEach(a -> node.produced.put(a, store().version(a)));
        node.fingerprint = Json.stableHash(data("spec", node.specHash(), "inputs", consumed));
        node.runs++;
        for (Decision d : result.decisions) {
            if (d.node == null || d.node.isEmpty()) {
                d.node = node.id;
            }
            if (d.inputs == null || d.inputs.isEmpty()) {
                d.inputs = new LinkedHashMap<>(consumed);
            }
            store().addDecision(d);
        }
        audit.record("node.succeeded", node.id, data("attempts", node.attempts, "produced", node.produced,
                "run", node.runs));
        produced.forEach(this::propagate);
        if (produced.contains("plan")) {
            mergePlan();
        }
        if (MUTATING_KINDS.contains(node.kind) || !node.writes.isEmpty()) {
            invalidateDownstream(node, "workspace changed upstream");
        }
    }

    private void waitFor(Node node, String reason) {
        node.status = NodeStatus.WAITING;
        node.waitReason = reason;
        audit.record("node.waiting", node.id, data("reason", reason));
    }

    private void onFailure(Node node) {
        List<String> blocked = graph().blockDownstream(node.id, "upstream " + node.id + " failed");
        if (!blocked.isEmpty()) {
            audit.record("node.blocked_downstream", node.id, data("blocked", blocked));
        }
        if (node.critical && haltReason == null) {
            haltReason = "critical node '" + node.id + "' failed: " + node.error;
            state.stopReason = haltReason;
            audit.record("run.safe_stop", null, data("reason", haltReason));
        }
    }

    // ------------------------------------------------------------------ invalidation and re-planning

    private void propagate(String artifact) {
        int version = store().version(artifact);
        for (Node consumer : graph().consumersOf(artifact)) {
            boolean stale = consumer.consumed.getOrDefault(artifact, 0) < version;
            if (consumer.status == NodeStatus.SUCCEEDED && stale) {
                invalidate(consumer, "input '" + artifact + "' changed to v" + version);
            } else if (consumer.status == NodeStatus.WAITING) {
                consumer.status = NodeStatus.PENDING;
                consumer.waitReason = null;
            }
        }
    }

    private void invalidate(Node node, String reason) {
        if (node.status != NodeStatus.SUCCEEDED) {
            return;
        }
        node.status = NodeStatus.PENDING;
        node.attempts = 0;
        audit.record("node.invalidated", node.id, data("reason", reason));
    }

    private void invalidateDownstream(Node node, String reason) {
        for (String id : graph().downstream(node.id)) {
            Node child = graph().get(id);
            if (!MUTATING_KINDS.contains(child.kind) && !child.kind.equals("checkpoint")) {
                invalidate(child, reason + " (" + node.id + ")");
            }
        }
    }

    private void mergePlan() {
        JsonNode plan = store().get("plan");
        int version = store().version("plan");
        Map<String, Node> fresh = new LinkedHashMap<>();
        plan.get("tasks").forEach(t -> fresh.put(t.get("id").asText(), Scenario.taskNode(t, version)));
        Map<String, Node> existing = new LinkedHashMap<>();
        graph().all().stream().filter(n -> n.kind.equals("task") || n.kind.equals("checkpoint"))
                .forEach(n -> existing.put(n.id, n));
        List<String> added = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> reused = new ArrayList<>();
        fresh.forEach((id, n) -> {
            Node old = existing.get(id);
            if (old != null && old.specHash().equals(n.specHash()) && old.deps.equals(n.deps)
                    && old.entryGates.equals(n.entryGates)) {
                if (old.status == NodeStatus.SKIPPED) {
                    old.status = NodeStatus.PENDING;
                } else if (old.status == NodeStatus.SUCCEEDED && version > 1) {
                    reused.add(id);
                    audit.record("node.reused", id, data("plan_version", version));
                }
                return;
            }
            if (old != null) {
                if (old.status == NodeStatus.SUCCEEDED && !revert(old, "task changed in re-plan")) {
                    return; // kept as-is; a decision record asks a human to review it
                }
                changed.add(id);
            } else {
                added.add(id);
            }
            graph().put(n);
        });
        existing.forEach((id, old) -> {
            if (!fresh.containsKey(id) && old.status != NodeStatus.SKIPPED) {
                if (old.status == NodeStatus.SUCCEEDED && !revert(old, "task removed in re-plan")) {
                    return;
                }
                old.status = NodeStatus.SKIPPED;
                removed.add(id);
                audit.record("node.superseded", id, data("plan_version", version));
            }
        });
        List<String> active = graph().all().stream()
                .filter(n -> (n.kind.equals("task") || n.kind.equals("checkpoint")) && n.status != NodeStatus.SKIPPED)
                .map(n -> n.id).sorted().toList();
        for (Node n : graph().all()) {
            if (n.join()) {
                List<String> deps = new ArrayList<>(List.of("plan"));
                deps.addAll(active);
                n.deps = deps;
                if (!added.isEmpty() || !changed.isEmpty()) {
                    invalidate(n, "plan changed");
                }
            }
        }
        graph().validate();
        if (!added.isEmpty() || !changed.isEmpty()) {
            for (String id : List.of("readiness", "release")) {
                Node n = graph().get(id);
                if (n != null) {
                    invalidate(n, "plan changed");
                }
            }
        }
        audit.record(version == 1 ? "plan.created" : "plan.revised", "plan", data("plan_version", version,
                "added", added, "changed", changed, "removed", removed, "reused", reused));
        if (version > 1) {
            store().addDecision(new Decision("Re-plan to v" + version, "upstream change; added " + added + ", changed "
                    + changed + ", removed " + removed + ", kept " + reused + " without re-running", List.of())
                    .by("orchestrator").at("plan"));
        }
    }

    /** Undoes a committed change. False if it cannot be undone safely. */
    private boolean revert(Node node, String reason) {
        Path path = dir.resolve("undo").resolve(node.id + ".json");
        if (node.status != NodeStatus.SUCCEEDED || !Files.exists(path)) {
            return true;
        }
        Map<String, String> undo = readUndo(node, null);
        List<String> later = new ArrayList<>();
        for (Node other : graph().all()) {
            Path p = dir.resolve("undo").resolve(other.id + ".json");
            if (!other.id.equals(node.id) && other.status == NodeStatus.SUCCEEDED && Files.exists(p)
                    && (other.finishedAt == null ? 0 : other.finishedAt) > (node.finishedAt == null ? 0 : node.finishedAt)) {
                Set<String> overlap = new TreeSet<>(readUndo(other, null).keySet());
                overlap.retainAll(undo.keySet());
                if (!overlap.isEmpty()) {
                    later.add(other.id);
                }
            }
        }
        if (!later.isEmpty()) {
            store().addDecision(new Decision("Could not auto-revert " + node.id, "later changes by " + later
                    + " touch the same files; left in place for human review", List.of()).by("orchestrator").at(node.id));
            audit.record("change.revert_refused", node.id, data("reason", reason, "blocked_by", later));
            return false;
        }
        workspace().rollback(new Workspace.UndoLog(undo));
        try {
            Files.delete(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        audit.record("change.reverted", node.id, data("reason", reason, "paths", new TreeSet<>(undo.keySet())));
        return true;
    }

    // ------------------------------------------------------------------ undo logs (crash safety)

    private Path undoPath(Node node, String kind) {
        return dir.resolve("undo").resolve(node.id + (kind == null ? "" : "." + kind) + ".json");
    }

    private void writeUndo(Node node, Map<String, String> before, String kind) {
        try {
            Files.writeString(undoPath(node, kind), Json.write(before));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> readUndo(Node node, String kind) {
        try {
            return Json.MAPPER.readValue(Files.readString(undoPath(node, kind)), LinkedHashMap.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void moveUndo(Node node, String from, String to) {
        try {
            Files.move(undoPath(node, from), undoPath(node, to), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void commitUndo(Node node) {
        moveUndo(node, "inflight", null);
    }

    private void dropUndo(Node node, String kind) {
        try {
            Files.deleteIfExists(undoPath(node, kind));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A crash between apply and commit leaves an inflight undo log: roll it back. */
    @SuppressWarnings("unchecked")
    public void recoverInflight() {
        try (Stream<Path> files = Files.list(dir.resolve("undo"))) {
            for (Path p : files.filter(f -> f.getFileName().toString().endsWith(".inflight.json")).sorted().toList()) {
                workspace().rollback(new Workspace.UndoLog(Json.MAPPER.readValue(Files.readString(p), LinkedHashMap.class)));
                Files.delete(p);
                audit.record("change.rolled_back", p.getFileName().toString().replace(".inflight.json", ""),
                        data("reason", "crash recovery"));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void touchStop(Path runDir) {
        try {
            Files.writeString(runDir.resolve("STOP"), "");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
