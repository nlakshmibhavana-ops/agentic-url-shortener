package io.agentflow.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import io.agentflow.core.JavaSource;
import io.agentflow.core.Json;
import io.agentflow.core.Knowledge;
import io.agentflow.core.Workspace;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Feature-completion proof: every acceptance criterion of every delivered capability must trace to
 * production code that was changed for it (or already implements it) and to a test tagged with the
 * criterion ({@code @Tag("AC-<capability>-<n>")}) that actually ran and passed in the final suite.
 */
public final class Traceability {

    private Traceability() {
    }

    public static String criterionId(String capability, int index) {
        return "AC-" + capability + "-" + (index + 1);
    }

    /** "Class.method" for every test method, keyed by each criterion tag it carries. */
    static Map<String, Set<String>> taggedTests(Workspace ws) {
        Map<String, Set<String>> out = new TreeMap<>();
        for (String rel : ws.files()) {
            if (!rel.startsWith("src/test/") || !rel.endsWith(".java")) {
                continue;
            }
            CompilationUnit cu;
            try {
                cu = JavaSource.parse(ws.read(rel));
            } catch (JavaSource.ParseError e) {
                continue; // a test that does not parse also does not compile: the build gate reports it
            }
            for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
                for (MethodDeclaration m : type.getMethods()) {
                    for (String tag : tags(m)) {
                        if (tag.startsWith("AC-")) {
                            out.computeIfAbsent(tag, k -> new TreeSet<>()).add(type.getNameAsString() + "."
                                    + m.getNameAsString());
                        }
                    }
                }
            }
        }
        return out;
    }

    private static List<String> tags(MethodDeclaration m) {
        List<String> out = new ArrayList<>();
        for (AnnotationExpr a : m.getAnnotations()) {
            String name = a.getNameAsString();
            if (name.equals("Tag") || name.endsWith(".Tag")) {
                value(a).forEach(v -> out.add(v));
            } else if (name.equals("Tags") || name.endsWith(".Tags")) {
                for (Expression e : value(a).isEmpty() ? containerValues(a) : List.<Expression>of()) {
                    if (e instanceof AnnotationExpr inner) {
                        out.addAll(value(inner));
                    }
                }
            }
        }
        return out;
    }

    private static List<String> value(AnnotationExpr a) {
        Expression v = a instanceof SingleMemberAnnotationExpr s ? s.getMemberValue()
                : a instanceof NormalAnnotationExpr n ? n.getPairs().stream().filter(p -> p.getNameAsString().equals("value"))
                        .map(p -> p.getValue()).findFirst().orElse(null) : null;
        return v instanceof StringLiteralExpr str ? List.of(str.getValue()) : List.of();
    }

    private static List<Expression> containerValues(AnnotationExpr a) {
        Expression v = a instanceof SingleMemberAnnotationExpr s ? s.getMemberValue() : null;
        return v instanceof ArrayInitializerExpr arr ? arr.getValues() : v == null ? List.of() : List.of(v);
    }

    /** Production paths committed by each task (from its committed undo log). */
    static Map<String, Set<String>> committedPaths(Path runDir, JsonNode plan) {
        Map<String, Set<String>> out = new TreeMap<>();
        for (JsonNode t : plan.path("tasks")) {
            String id = t.get("id").asText();
            Path undo = runDir.resolve("undo").resolve(id + ".json");
            Set<String> paths = new TreeSet<>();
            if (Files.exists(undo)) {
                try {
                    Json.parse(Files.readString(undo)).fieldNames().forEachRemaining(p -> {
                        if (p.startsWith("src/main/")) {
                            paths.add(p);
                        }
                    });
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            out.put(id, paths);
        }
        return out;
    }

    public record Result(List<Map<String, Object>> rows, List<String> unproven) {
    }

    public static Result check(Workspace ws, Path runDir, JsonNode requirements, JsonNode plan, JsonNode testReport) {
        Set<String> passed = new HashSet<>();
        testReport.path("passed_tests").forEach(t -> passed.add(t.asText()));
        Map<String, Set<String>> tagged = taggedTests(ws);
        Map<String, Set<String>> committed = committedPaths(runDir, plan);
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> unproven = new ArrayList<>();
        for (JsonNode cap : requirements.path("capabilities")) {
            String capId = cap.get("id").asText();
            Set<String> code = new TreeSet<>();
            for (JsonNode task : plan.path("traceability").path(capId)) {
                code.addAll(committed.getOrDefault(task.asText(), Set.of()));
            }
            plan.path("satisfied_by_existing").path(capId).forEach(m -> code.add("existing:" + m.asText()));
            List<String> criteria = Knowledge.criteria(capId);
            for (int i = 0; i < criteria.size(); i++) {
                String id = criterionId(capId, i);
                Set<String> tests = tagged.getOrDefault(id, Set.of());
                List<String> proven = tests.stream().filter(passed::contains).toList();
                boolean ok = !proven.isEmpty() && !code.isEmpty();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("criterion", id);
                row.put("text", criteria.get(i));
                row.put("code", new ArrayList<>(code));
                row.put("tests_tagged", new ArrayList<>(tests));
                row.put("tests_passed", proven);
                row.put("proven", ok);
                rows.add(row);
                if (!ok) {
                    unproven.add(id + (code.isEmpty() ? " (no code)" : "") + (proven.isEmpty()
                            ? tests.isEmpty() ? " (no tagged test)" : " (tagged tests did not pass)" : ""));
                }
            }
        }
        return new Result(rows, unproven);
    }
}
