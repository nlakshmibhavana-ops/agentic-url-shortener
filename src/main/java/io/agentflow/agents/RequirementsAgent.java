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

    /** Clauses: sentences, further split on semicolons. Every clause gets a disposition. */
    static List<String> clauses(String text) {
        List<String> out = new ArrayList<>();
        for (String s : sentences(text)) {
            for (String part : s.split(";\\s*")) {
                if (!part.isBlank()) {
                    out.add(part.strip());
                }
            }
        }
        return out;
    }

    private static final Pattern CONSTRAINT = Pattern.compile(
            "\\b(constraints?|must keep working|keep working|backward[s]? compatib\\w*|existing clients|no downtime)\\b");

    /** Capabilities whose keywords occur in a clause (before refinement), in vocabulary order. */
    static Set<String> keywordCapabilities(String clause) {
        String lower = clause.toLowerCase(Locale.ROOT);
        Set<String> here = new LinkedHashSet<>();
        Knowledge.capabilities().fields().forEachRemaining(cap -> {
            for (JsonNode k : cap.getValue().path("keywords")) {
                if (lower.contains(k.asText())) {
                    here.add(cap.getKey());
                }
            }
        });
        return here;
    }

    static String unsupportedId(int clauseIndex) {
        return "Q-UNSUPPORTED-" + clauseIndex;
    }

    static final Map<String, String> UNSUPPORTED_OPTIONS = Map.of(
            "descope", "Proceed without it: recorded as out of scope and listed in the release notes",
            "stop", "Stop: this request needs a capability the system cannot deliver yet");

    /**
     * Gives every clause of the request a disposition, so nothing the requester asked for can vanish:
     * {@code supported} (maps to capabilities), {@code ambiguous} (raised as a question),
     * {@code constraint} (a condition on the work, checked by regression gates), or
     * {@code unsupported}, which becomes a blocking question: a human must descope it or stop the run.
     */
    static List<Map<String, Object>> dispose(String text, List<Map<String, Object>> questions) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<String> all = clauses(text);
        for (int i = 0; i < all.size(); i++) {
            String clause = all.get(i);
            Set<String> caps = keywordCapabilities(clause);
            List<String> qs = new ArrayList<>();
            for (Map<String, Object> q : questions) {
                String ev = (String) q.get("evidence");
                if (ev != null && (ev.contains(clause) || clause.contains(ev))) {
                    qs.add((String) q.get("id"));
                }
            }
            String disposition = !caps.isEmpty() ? "supported" : !qs.isEmpty() ? "ambiguous"
                    : CONSTRAINT.matcher(clause.toLowerCase(Locale.ROOT)).find() ? "constraint" : "unsupported";
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", "C" + (i + 1));
            c.put("text", clause);
            c.put("disposition", disposition);
            c.put("capabilities", new ArrayList<>(caps));
            c.put("questions", qs);
            out.add(c);
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
        List<Map<String, Object>> clauses = dispose(text, questions);
        for (Map<String, Object> c : clauses) {
            if (c.get("disposition").equals("unsupported")) {
                Map<String, Object> qq = new LinkedHashMap<>();
                qq.put("id", unsupportedId(Integer.parseInt(((String) c.get("id")).substring(1))));
                qq.put("text", "No supported capability covers this part of the request. Descope it, or stop?");
                qq.put("blocking", true);
                qq.put("evidence", c.get("text"));
                qq.put("default", null);
                qq.put("options", new LinkedHashMap<>(Map.of("descope", UNSUPPORTED_OPTIONS.get("descope"), "stop",
                        UNSUPPORTED_OPTIONS.get("stop"))));
                questions.add(qq);
                c.put("questions", List.of(qq.get("id")));
            }
        }
        List<Decision> decisions = new ArrayList<>();
        List<String> assumptions = new ArrayList<>();
        List<String> outOfScope = new ArrayList<>();
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Map<String, Object> q : questions) {
            String id = (String) q.get("id");
            if (id.startsWith("Q-UNSUPPORTED-")) {
                JsonNode answer = answers == null ? null : answers.get(id);
                if (answer == null || answer.isNull()) {
                    continue; // blocking: nothing proceeds until a human decides
                }
                String option = answer.get("option").asText();
                String source = "human:" + answer.get("by").asText();
                if (option.equals("stop")) {
                    throw new AgentException("stopped by " + source + ": unsupported requirement \"" + q.get("evidence")
                            + "\"");
                }
                if (!option.equals("descope")) {
                    throw new AgentException("answer '" + option + "' is not an option for " + id);
                }
                resolved.put(id, Map.of("option", option, "source", source));
                outOfScope.add("Descoped (unsupported): " + q.get("evidence"));
                decisions.add(new Decision(id + " resolved as 'descope'", "\"" + q.get("evidence")
                        + "\" is not delivered; recorded as out of scope", List.of("stop")).by(source));
                continue;
            }
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
        Set<String> finalCaps = new LinkedHashSet<>(caps.keySet());
        for (Map<String, Object> c : clauses) {
            @SuppressWarnings("unchecked")
            List<String> qs = (List<String>) c.get("questions");
            String disposition = (String) c.get("disposition");
            if (disposition.equals("unsupported") && !qs.isEmpty() && resolved.containsKey(qs.get(0))) {
                c.put("disposition", "descoped");
            }
            if (disposition.equals("supported")) {
                @SuppressWarnings("unchecked")
                List<String> clauseCaps = (List<String>) c.get("capabilities");
                // What the clause maps to after refinement ("POST /shorten" in a security ticket is url_safety).
                List<String> covered = clauseCaps.stream().filter(finalCaps::contains).toList();
                if (covered.isEmpty() && qs.isEmpty()) {
                    covered = matchCapabilities((String) c.get("text")).keySet().stream().filter(finalCaps::contains)
                            .toList();
                }
                c.put("covered_by", covered.isEmpty() ? qs : covered);
                if (covered.isEmpty() && qs.isEmpty()) {
                    c.put("disposition", "ambiguous"); // matched a word but no capability survived: say so
                }
            }
        }
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("title", title);
        req.put("clauses", clauses);
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
