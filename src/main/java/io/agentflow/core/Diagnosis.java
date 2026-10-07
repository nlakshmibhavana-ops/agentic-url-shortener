package io.agentflow.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A structured diagnosis of a failed attempt, derived from the gate and policy evidence: which phase
 * failed, which tests, where in the code, and which rules. Repairs are chosen against this, never by
 * attempt number.
 *
 * <p>Phases: {@code compile}, {@code lint}, {@code tests}, {@code missing_tests}, {@code no_evidence},
 * {@code timeout}, {@code build}, {@code policy}, {@code apply}, {@code agent}, {@code gate}.
 */
public record Diagnosis(String phase, List<String> failingTests, List<String> locations, List<String> rules,
        String summary) {

    private static final Pattern FAILING = Pattern.compile("failing tests: \\[([^\\]]*)\\]");
    private static final Pattern TEST_METHOD = Pattern.compile("\\b([A-Z]\\w*Tests?)\\.(\\w+)");
    private static final Pattern LOCATION = Pattern.compile("([\\w/.-]+\\.java):\\[(\\d+)");
    private static final Pattern RULE = Pattern.compile("\\b((?:SEC|DATA|PII|CHG|DEP)-\\d{3})\\b");

    public static Diagnosis of(String feedback) {
        String f = feedback == null ? "" : feedback;
        String phase = f.startsWith("policy blocked") ? "policy"
                : f.startsWith("change does not apply") || f.startsWith("change could not be applied") ? "apply"
                : f.contains("expected tests are missing") ? "missing_tests"
                : f.contains("no execution evidence") ? "no_evidence"
                : f.contains("compile failed") ? "compile"
                : f.contains("lint (checkstyle) failed") ? "lint"
                : f.contains("tests failed") ? "tests"
                : f.contains("build timed out") ? "timeout"
                : f.contains("build failed") ? "build"
                : f.contains("build:") || f.contains(": ") && f.contains("gate") ? "gate" : "agent";
        Set<String> tests = new LinkedHashSet<>();
        Matcher m = FAILING.matcher(f);
        if (m.find()) {
            for (String t : m.group(1).split(",\\s*")) {
                if (!t.isBlank()) {
                    tests.add(t.strip());
                }
            }
        } else if (phase.equals("tests")) {
            Matcher t = TEST_METHOD.matcher(f);
            while (t.find()) {
                tests.add(t.group(1) + "." + t.group(2));
            }
        }
        Set<String> locations = new LinkedHashSet<>();
        Matcher l = LOCATION.matcher(f);
        while (l.find()) {
            String file = l.group(1);
            locations.add(file.substring(file.lastIndexOf('/') + 1) + ":" + l.group(2));
        }
        Set<String> rules = new LinkedHashSet<>();
        Matcher r = RULE.matcher(f);
        while (r.find()) {
            rules.add(r.group(1));
        }
        String one = f.replace('\n', ' ');
        return new Diagnosis(phase, new ArrayList<>(tests), new ArrayList<>(locations), new ArrayList<>(rules),
                one.length() > 300 ? one.substring(0, 300) + "..." : one);
    }

    /**
     * Whether a reviewed repair declaration addresses this diagnosis. A declaration names the phase and,
     * optionally, a test ({@code Class} or {@code Class.method}), a rule, or a file.
     */
    public boolean matches(Map<String, Object> repair) {
        if (!phase.equals(repair.get("phase"))) {
            return false;
        }
        Object test = repair.get("test");
        if (test != null && failingTests.stream().noneMatch(t -> t.equals(test) || t.startsWith(test + "."))) {
            return false;
        }
        Object rule = repair.get("rule");
        if (rule != null && !rules.contains(rule)) {
            return false;
        }
        Object file = repair.get("file");
        return file == null || locations.stream().anyMatch(loc -> loc.startsWith(file + ":"));
    }

    public Map<String, Object> toMap() {
        return Map.of("phase", phase, "failing_tests", failingTests, "locations", locations, "rules", rules,
                "summary", summary);
    }
}
