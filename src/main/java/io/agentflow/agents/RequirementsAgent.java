package io.agentflow.agents;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.core.Knowledge;
import io.agentflow.model.AgentException;
import io.agentflow.model.AgentResult;
import io.agentflow.model.Decision;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Requirement understanding: normalise prose into capabilities with acceptance criteria and surface
 * ambiguity as explicit questions with governed options, instead of silently guessing.
 */
public class RequirementsAgent implements Agent {

    private static final Pattern SENTENCE = Pattern.compile("(?<=[.!?\\n])\\s+");
    private static final Pattern DIGIT = Pattern.compile("\\d");

    public record Built(Map<String, Object> requirements, List<Decision> decisions) {
    }

    @Override
    public AgentResult run(AgentContext ctx) {
        Built b = build(ctx.store().get("requirement_text").asText(), ctx.store().get("human_answers"),
                ctx.scenario().get("title").asText());
        AgentResult r = new AgentResult().artifact("requirements", b.requirements());
        r.decisions.addAll(b.decisions());
        @SuppressWarnings("unchecked")
        List<Object> caps = (List<Object>) b.requirements().get("capabilities");
        r.note(caps.size() + " capabilities, " + ((List<?>) b.requirements().get("questions")).size()
                + " ambiguities, " + ((List<?>) b.requirements().get("blocking_open")).size() + " blocking");
        return r;
    }

    static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        for (String s : SENTENCE.split(text)) {
            String t = s.strip().replaceAll("^[-*\\s]+", "");
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** capability -> the sentence that evidences it (traceability back to the request). */
    static Map<String, String> matchCapabilities(String text) {
        Map<String, String> found = new LinkedHashMap<>();
        for (String sentence : sentences(text)) {
            String lower = sentence.toLowerCase(Locale.ROOT);
            Set<String> here = new LinkedHashSet<>();
            Iterator<Map.Entry<String, JsonNode>> caps = Knowledge.capabilities().fields();
            while (caps.hasNext()) {
                Map.Entry<String, JsonNode> cap = caps.next();
                for (JsonNode k : cap.getValue().path("keywords")) {
                    if (lower.contains(k.asText())) {
                        here.add(cap.getKey());
                    }
                }
            }
            Knowledge.refines().fields().forEachRemaining(e -> {
                if (here.contains(e.getKey())) {
                    e.getValue().forEach(g -> here.remove(g.asText()));
                }
            });
            for (String cap : new java.util.TreeSet<>(here)) {
                found.putIfAbsent(cap, sentence);
            }
        }
        return found;
    }

    static List<Map<String, Object>> questions(String text, Map<String, String> caps) {
        List<Map<String, Object>> out = new ArrayList<>();
        String lowerAll = text.toLowerCase(Locale.ROOT);
        for (JsonNode q : Knowledge.questions()) {
            String needed = q.path("requires_capability").isNull() ? null : q.path("requires_capability").asText(null);
            if (needed != null && !caps.containsKey(needed)) {
                continue;
            }
            boolean suppressed = false;
            for (JsonNode u : q.path("unless")) {
                suppressed |= lowerAll.contains(u.asText());
            }
            if (suppressed) {
                continue;
            }
            String evidence = null;
            for (String s : sentences(text)) {
                String lower = s.toLowerCase(Locale.ROOT);
                boolean triggered = false;
                for (JsonNode t : q.get("triggers")) {
                    triggered |= Pattern.compile("\\b" + Pattern.quote(t.asText()) + "\\b").matcher(lower).find();
                }
                // A number in the sentence means it states a measurable target.
                if (triggered && !DIGIT.matcher(s).find()) {
                    evidence = s;
                    break;
                }
            }
            if (evidence != null) {
                Map<String, Object> qq = new LinkedHashMap<>();
                qq.put("id", q.get("id").asText());
                qq.put("text", q.get("text").asText());
                qq.put("blocking", q.get("blocking").asBoolean());
                qq.put("evidence", evidence);
                qq.put("default", q.get("default").isNull() ? null : q.get("default").asText());
                Map<String, String> options = new LinkedHashMap<>();
                q.get("options").fields().forEachRemaining(o -> options.put(o.getKey(), o.getValue().get("label").asText()));
                qq.put("options", options);
                out.add(qq);
            }
        }
        return out;
    }

    public static Built build(String text, JsonNode answers, String title) {
        Map<String, String> caps = matchCapabilities(text);
        if (caps.isEmpty()) {
            throw new AgentException("no recognisable capability in the requirement");
        }
        List<Map<String, Object>> questions = questions(text, caps);
        List<Decision> decisions = new ArrayList<>();
        List<String> assumptions = new ArrayList<>();
        List<String> outOfScope = new ArrayList<>();
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Map<String, Object> q : questions) {
            String id = (String) q.get("id");
            JsonNode spec = Knowledge.question(id);
            JsonNode answer = answers == null ? null : answers.get(id);
            String option;
            String source;
            if (answer != null && !answer.isNull()) {
                option = answer.get("option").asText();
                source = "human:" + answer.get("by").asText();
            } else if (!(Boolean) q.get("blocking")) {
                option = (String) q.get("default");
                source = "assumption";
                assumptions.add(id + ": assumed '" + spec.get("options").get(option).get("label").asText()
                        + "' (non-blocking; confirm or override)");
            } else {
                continue; // stays open; the planner gates dependent work on it
            }
            JsonNode chosen = spec.get("options").get(option);
            if (chosen == null) {
                throw new AgentException("answer '" + option + "' is not an option for " + id);
            }
            resolved.put(id, Map.of("option", option, "source", source));
            chosen.get("adds").forEach(c -> caps.putIfAbsent(c.asText(), id + " -> " + option));
            if (chosen.has("out_of_scope")) {
                outOfScope.add(chosen.get("out_of_scope").asText());
            }
            List<String> alternatives = new ArrayList<>();
            spec.get("options").fieldNames().forEachRemaining(n -> {
                if (!n.equals(option)) {
                    alternatives.add(n);
                }
            });
            decisions.add(new Decision(id + " resolved as '" + option + "'", chosen.get("label").asText(), alternatives)
                    .by(source));
        }
        if (caps.containsKey("performance") && !caps.containsKey("redirect_cache")) {
            caps.remove("performance"); // vague without a target; kept only once a target is agreed
        }
        List<Map<String, Object>> capabilities = new ArrayList<>();
        new TreeMap<>(caps).forEach((cap, src) -> {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", cap);
            c.put("source", src);
            c.put("acceptance_criteria", Knowledge.criteria(cap));
            capabilities.add(c);
        });
        List<String> open = new ArrayList<>();
        List<String> blockingOpen = new ArrayList<>();
        for (Map<String, Object> q : questions) {
            if (!resolved.containsKey((String) q.get("id"))) {
                open.add((String) q.get("id"));
                if ((Boolean) q.get("blocking")) {
                    blockingOpen.add((String) q.get("id"));
                }
            }
        }
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("title", title);
        req.put("capabilities", capabilities);
        req.put("questions", questions);
        req.put("resolved", resolved);
        req.put("open_questions", open);
        req.put("blocking_open", blockingOpen);
        req.put("assumptions", assumptions);
        req.put("out_of_scope", outOfScope);
        return new Built(req, decisions);
    }
}
