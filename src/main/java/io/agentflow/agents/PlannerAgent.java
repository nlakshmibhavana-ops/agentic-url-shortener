package io.agentflow.agents;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.core.Knowledge;
import io.agentflow.core.Playbooks;
import io.agentflow.model.AgentException;
import io.agentflow.model.AgentResult;
import io.agentflow.model.Decision;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Task decomposition: requirements + design (+ impact analysis) become an executable task graph.
 * The plan is an artifact like any other; when it changes, the engine merges it into the live graph.
 */
public class PlannerAgent implements Agent {

    public record Planned(Map<String, Object> plan, List<Decision> decisions) {
    }

    @Override
    public AgentResult run(AgentContext ctx) {
        boolean llm = List.of("claude", "ollama").contains(ctx.scenario().path("provider").asText("deterministic"));
        Planned p = plan(ctx.playbook(), ctx.store().get("requirements"), ctx.store().get("impact_analysis"), llm);
        AgentResult r = new AgentResult().artifact("plan", p.plan());
        r.decisions.addAll(p.decisions());
        r.note(((List<?>) p.plan().get("tasks")).size() + " tasks; waiting on " + p.plan().get("waiting_on"));
        return r;
    }

    private static boolean reaches(Map<String, Set<String>> deps, String from, String to) {
        Deque<String> stack = new ArrayDeque<>(List.of(from));
        Set<String> seen = new HashSet<>();
        while (!stack.isEmpty()) {
            String cur = stack.pop();
            if (cur.equals(to)) {
                return true;
            }
            if (seen.add(cur)) {
                stack.addAll(deps.getOrDefault(cur, Set.of()));
            }
        }
        return false;
    }

    /** Existing tests that exercise (transitively) any class this task changes. */
    static Set<String> regressionFor(List<String> writes, JsonNode impact) {
        Map<String, List<String>> graph = new HashMap<>();
        impact.get("import_graph").fields().forEachRemaining(e -> {
            List<String> deps = new ArrayList<>();
            e.getValue().forEach(d -> deps.add(d.asText()));
            graph.put(e.getKey(), deps);
        });
        Set<String> changed = new TreeSet<>();
        for (String w : writes) {
            if (w.startsWith("src/main/java/") && w.endsWith(".java")) {
                String fqn = w.substring("src/main/java/".length(), w.length() - 5).replace('/', '.');
                if (graph.containsKey(fqn)) {
                    changed.add(fqn);
                }
            }
        }
        Set<String> closure = CodebaseAnalyst.reverseClosure(graph, changed);
        Set<String> out = new TreeSet<>();
        impact.path("test_map").fields().forEachRemaining(e -> {
            for (JsonNode m : e.getValue()) {
                if (closure.contains(m.asText())) {
                    out.add(e.getKey());
                }
            }
        });
        return out;
    }

    public static Planned plan(String playbook, JsonNode req, JsonNode impact, boolean llmImplement) {
        JsonNode book = Playbooks.load(playbook);
        Set<String> caps = new TreeSet<>();
        req.get("capabilities").forEach(c -> caps.add(c.get("id").asText()));
        Set<String> wanted = new HashSet<>(caps);
        wanted.add("platform");
        List<JsonNode> selected = new ArrayList<>();
        for (JsonNode t : book.get("tasks")) {
            for (JsonNode c : t.path("capabilities")) {
                if (wanted.contains(c.asText())) {
                    selected.add(t);
                    break;
                }
            }
        }
        Set<String> ids = new HashSet<>();
        selected.forEach(t -> ids.add(t.get("id").asText()));
        Map<String, Set<String>> deps = new LinkedHashMap<>();
        for (JsonNode t : selected) {
            Set<String> d = new TreeSet<>();
            t.path("deps").forEach(x -> {
                if (ids.contains(x.asText())) {
                    d.add(x.asText());
                }
            });
            deps.put(t.get("id").asText(), d);
        }
        List<Decision> decisions = new ArrayList<>();
        // Serialise tasks whose write sets overlap and that are not already ordered (a merge queue).
        for (int i = 0; i < selected.size(); i++) {
            for (int j = i + 1; j < selected.size(); j++) {
                JsonNode a = selected.get(i);
                JsonNode b = selected.get(j);
                Set<String> overlap = new TreeSet<>(strings(a.path("writes")));
                overlap.retainAll(strings(b.path("writes")));
                String ai = a.get("id").asText();
                String bi = b.get("id").asText();
                if (!overlap.isEmpty() && !reaches(deps, bi, ai) && !reaches(deps, ai, bi)) {
                    deps.get(bi).add(ai);
                    decisions.add(new Decision("Serialise " + bi + " after " + ai, "Both change "
                            + overlap.stream().map(p -> p.substring(p.lastIndexOf('/') + 1)).toList()
                            + "; running them in parallel could produce conflicting edits, so they are ordered "
                            + "like a merge queue.", List.of("Run in parallel and merge (conflict-prone)")));
                }
            }
        }
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (JsonNode t : selected) {
            String id = t.get("id").asText();
            List<String> verify = strings(t.path("verify"));
            if (!impact.isMissingNode() && !verify.isEmpty()) {
                Set<String> v = new TreeSet<>(verify);
                v.addAll(regressionFor(strings(t.path("writes")), impact));
                verify = new ArrayList<>(v);
            }
            String agent = t.path("agent").asText("implement");
            if (llmImplement && agent.equals("implement")) {
                agent = "implement.llm";
            }
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("id", id);
            task.put("title", t.get("title").asText());
            task.put("agent", agent);
            task.put("capabilities", strings(t.path("capabilities")));
            task.put("deps", new ArrayList<>(deps.get(id)));
            task.put("writes", strings(t.path("writes")));
            task.put("verify", verify);
            task.put("impact", t.path("impact").asText("low"));
            task.put("candidates", Playbooks.candidateCount(playbook, id));
            tasks.add(task);
        }
        // Work that depends on an unanswered blocking question becomes an explicit human gate.
        for (JsonNode q : req.get("blocking_open")) {
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("id", "clarify." + q.asText());
            task.put("title", "Await human decision on " + q.asText());
            task.put("agent", "none");
            task.put("capabilities", List.of());
            task.put("deps", List.of());
            task.put("writes", List.of());
            task.put("verify", List.of());
            task.put("impact", "low");
            task.put("gate", "clarified:" + q.asText());
            task.put("candidates", 1);
            tasks.add(task);
        }
        Map<String, List<String>> trace = new TreeMap<>();
        for (String cap : caps) {
            List<String> covering = new ArrayList<>();
            for (Map<String, Object> t : tasks) {
                if (((List<?>) t.get("capabilities")).contains(cap)) {
                    covering.add((String) t.get("id"));
                }
            }
            trace.put(cap, covering);
        }
        Map<String, Object> existing = new TreeMap<>();
        if (!impact.isMissingNode()) { // brownfield: a capability the code already provides needs no task
            trace.forEach((cap, ts) -> {
                List<String> modules = strings(impact.path("impacted").path(cap).path("modules"));
                if (ts.isEmpty() && !modules.isEmpty()) {
                    existing.put(cap, modules);
                    modules.forEach(m -> ts.add("existing:" + m.substring(m.lastIndexOf('.') + 1)));
                }
            });
        }
        Set<String> pendingCaps = new HashSet<>();
        req.get("blocking_open").forEach(q -> Knowledge.question(q.asText()).get("options")
                .forEach(o -> o.get("adds").forEach(a -> pendingCaps.add(a.asText()))));
        List<String> uncovered = trace.entrySet().stream()
                .filter(e -> e.getValue().isEmpty() && !e.getKey().equals("performance") && !pendingCaps.contains(e.getKey()))
                .map(Map.Entry::getKey).toList();
        if (!uncovered.isEmpty()) {
            throw new AgentException("no task covers capabilities " + uncovered);
        }
        if (!existing.isEmpty()) {
            decisions.add(new Decision("No new work for " + existing.keySet(),
                    "Static analysis shows the existing code already implements these: " + existing,
                    List.of("Re-implement (churn without a requirement)")));
        }
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("playbook", playbook);
        plan.put("tasks", tasks);
        plan.put("traceability", trace);
        plan.put("satisfied_by_existing", existing);
        plan.put("uncovered", uncovered);
        plan.put("waiting_on", strings(req.get("blocking_open")));
        return new Planned(plan, decisions);
    }

    static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null) {
            array.forEach(v -> out.add(v.asText()));
        }
        return out;
    }
}
