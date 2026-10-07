package io.agentflow.agents;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import io.agentflow.core.Workspace;
import io.agentflow.model.AgentResult;
import io.agentflow.model.Decision;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Codebase reasoning for brownfield work: parses the existing Java sources to find the classes,
 * routes, tables and tests a requirement touches, the blast radius of a change, and latent defects.
 */
public class CodebaseAnalyst implements Agent {

    private static final Set<String> MAPPINGS = Set.of("GetMapping", "PostMapping", "PutMapping", "DeleteMapping",
            "PatchMapping", "RequestMapping");
    private static final Pattern TABLE_REF = Pattern.compile("(?i)\\b(?:FROM|INTO|UPDATE|JOIN)\\s+(\\w+)");
    private static final Pattern UPDATE_SET = Pattern.compile("(?i)UPDATE\\s+\\w+\\s+SET\\s+(\\w+)\\s*=\\s*\\?");
    private static final Pattern CREATE_TABLE = Pattern.compile("(?i)CREATE TABLE(?: IF NOT EXISTS)?\\s+(\\w+)");

    /** Which symbols a capability is likely to touch (matched against names, routes and SQL). */
    static final Map<String, List<String>> HINTS = Map.ofEntries(
            Map.entry("url_safety", List.of("url", "shorten", "validat")),
            Map.entry("expiry", List.of("urls", "create", "lookup", "go", "info", "expires")),
            Map.entry("click_accuracy", List.of("hit", "clicks", "go")),
            Map.entry("analytics", List.of("click", "stats", "info")),
            Map.entry("redirect", List.of("follow", "resolve", "redirect", "go")),
            Map.entry("link_creation", List.of("create", "shorten", "generate")),
            Map.entry("redirect_cache", List.of("resolve", "follow")),
            Map.entry("domain_denylist", List.of("validat")),
            Map.entry("unique_visitors", List.of("recordclick", "stats", "clicks")),
            Map.entry("unique_visitors_raw", List.of("recordclick", "clicks")));

    public record ClassInfo(String path, List<String> methods, List<String> references,
            List<Map<String, String>> routes, List<String> tables, List<Map<String, String>> smells, boolean controller,
            boolean springBootTest) {
    }

    @Override
    public AgentResult run(AgentContext ctx) {
        Map<String, ClassInfo> classes = analyse(ctx.workspace());
        Set<String> baseline = ctx.baselineFiles();
        Map<String, List<String>> graph = new TreeMap<>();
        Map<String, List<String>> tests = new TreeMap<>();
        List<String> controllers = classes.entrySet().stream().filter(e -> e.getValue().controller()).map(Map.Entry::getKey)
                .sorted().toList();
        classes.forEach((name, info) -> {
            List<String> refs = info.references().stream().filter(r -> classes.containsKey(r) && !r.equals(name)).sorted()
                    .toList();
            if (info.path().startsWith("src/test/")) {
                // Regression tests are the existing system's tests, not ones this run added.
                if (baseline.isEmpty() || baseline.contains(info.path())) {
                    List<String> exercised = new ArrayList<>(refs);
                    if (info.springBootTest()) {
                        exercised.addAll(controllers); // black-box tests exercise the app through its controllers
                    }
                    tests.put(info.path(), exercised.stream().filter(r -> !classes.get(r).path().startsWith("src/test/"))
                            .distinct().sorted().toList());
                }
            } else {
                graph.put(name, refs.stream().filter(r -> !classes.get(r).path().startsWith("src/test/")).toList());
            }
        });
        Map<String, Object> impacted = new TreeMap<>();
        Set<String> allSeeds = new TreeSet<>();
        for (com.fasterxml.jackson.databind.JsonNode cap : ctx.store().get("requirements").get("capabilities")) {
            String id = cap.get("id").asText();
            List<String> hints = HINTS.getOrDefault(id, List.of(id));
            Set<String> seeds = new TreeSet<>();
            Set<String> routes = new TreeSet<>();
            Set<String> tables = new TreeSet<>();
            graph.keySet().forEach(name -> {
                ClassInfo info = classes.get(name);
                String haystack = (simple(name) + " " + String.join(" ", info.methods()) + " " + String.join(" ", info.tables())
                        + " " + info.routes().stream().map(r -> r.get("path")).reduce("", String::concat))
                        .toLowerCase(Locale.ROOT);
                if (hints.stream().anyMatch(haystack::contains)) {
                    seeds.add(name);
                    tables.addAll(info.tables());
                    info.routes().forEach(r -> {
                        String rh = (r.get("handler") + r.get("path")).toLowerCase(Locale.ROOT);
                        if (hints.stream().anyMatch(rh::contains)) {
                            routes.add(r.get("method") + " " + r.get("path"));
                        }
                    });
                }
            });
            allSeeds.addAll(seeds);
            impacted.put(id, Map.of("modules", seeds, "routes", routes, "tables", tables));
        }
        Set<String> blast = reverseClosure(graph, allSeeds);
        List<String> regression = tests.entrySet().stream().filter(e -> e.getValue().stream().anyMatch(blast::contains))
                .map(Map.Entry::getKey).toList();
        List<Map<String, String>> smells = new ArrayList<>();
        classes.forEach((name, info) -> info.smells().forEach(s -> {
            Map<String, String> m = new LinkedHashMap<>(s);
            m.put("module", name);
            smells.add(m);
        }));
        Set<String> dataAtRisk = new TreeSet<>();
        impacted.values().forEach(v -> dataAtRisk.addAll(cast(((Map<?, ?>) v).get("tables"))));
        Map<String, Object> modules = new TreeMap<>();
        graph.keySet().forEach(n -> modules.put(n, Map.of("path", classes.get(n).path(), "routes", classes.get(n).routes(),
                "tables", classes.get(n).tables())));
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("modules", modules);
        impact.put("import_graph", graph);
        impact.put("impacted", impacted);
        impact.put("blast_radius", blast);
        impact.put("regression_tests", regression);
        impact.put("test_map", tests);
        impact.put("findings", smells);
        impact.put("data_at_risk", dataAtRisk);
        impact.put("schema_tables", schemaTables(ctx.workspace()));
        AgentResult r = new AgentResult().artifact("impact_analysis", impact);
        if (smells.stream().anyMatch(s -> s.get("kind").equals("enumerable-ids"))) {
            r.decisions.add(new Decision("Sequential/enumerable codes noted but left out of scope",
                    "Changing code generation alters every future URL format and is not requested by the tickets; "
                            + "raised as a follow-up risk instead.",
                    List.of("Switch to random codes now (scope creep, needs product sign-off)")));
        }
        r.note("blast radius " + blast.stream().map(CodebaseAnalyst::simple).toList() + ", " + smells.size()
                + " latent defects, regression tests " + regression.stream().map(io.agentflow.core.Maven::testClass).toList());
        return r;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> cast(Object o) {
        return (Set<String>) o;
    }

    static String simple(String fqn) {
        return fqn.substring(fqn.lastIndexOf('.') + 1);
    }

    public static Map<String, ClassInfo> analyse(Workspace ws) {
        Map<String, ClassInfo> out = new TreeMap<>();
        Map<String, String> simpleToFqn = new TreeMap<>();
        Map<String, CompilationUnit> units = new LinkedHashMap<>();
        for (String path : ws.files()) {
            if (!path.endsWith(".java")) {
                continue;
            }
            try {
                CompilationUnit cu = io.agentflow.core.JavaSource.parse(ws.read(path));
                String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("");
                // Parsed from a string, so the type name comes from the declaration, not the file name.
                if (cu.getTypes().isNonEmpty()) {
                    String t = cu.getType(0).getNameAsString();
                    simpleToFqn.put(t, pkg + t);
                    units.put(path, cu);
                }
            } catch (io.agentflow.core.JavaSource.ParseError e) {
                // unparsable files are reported by policy and review, not here
            }
        }
        units.forEach((path, cu) -> {
            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("");
            String name = pkg + cu.getType(0).getNameAsString();
            Set<String> refs = new TreeSet<>();
            cu.getImports().forEach(i -> refs.add(i.getNameAsString()));
            cu.findAll(ClassOrInterfaceType.class).forEach(t -> {
                String fqn = simpleToFqn.get(t.getNameAsString());
                if (fqn != null) {
                    refs.add(fqn);
                }
            });
            cu.findAll(com.github.javaparser.ast.expr.NameExpr.class).forEach(n -> {
                String fqn = simpleToFqn.get(n.getNameAsString());
                if (fqn != null) {
                    refs.add(fqn); // static calls such as UrlValidator.validate(...)
                }
            });
            List<String> methods = new ArrayList<>();
            List<Map<String, String>> routes = new ArrayList<>();
            List<Map<String, String>> smells = new ArrayList<>();
            Set<String> tables = new TreeSet<>();
            String prefix = "";
            boolean controller = false;
            boolean bootTest = false;
            for (ClassOrInterfaceDeclaration c : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                for (AnnotationExpr a : c.getAnnotations()) {
                    String an = a.getNameAsString();
                    controller |= an.equals("RestController") || an.equals("Controller");
                    bootTest |= an.equals("SpringBootTest");
                    if (an.equals("RequestMapping")) {
                        prefix = annotationPath(a);
                    }
                }
            }
            for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
                methods.add(m.getNameAsString());
                for (AnnotationExpr a : m.getAnnotations()) {
                    if (MAPPINGS.contains(a.getNameAsString())) {
                        String verb = a.getNameAsString().replace("Mapping", "").toUpperCase(Locale.ROOT);
                        routes.add(Map.of("method", verb.equals("REQUEST") ? "ANY" : verb, "path",
                                (prefix + annotationPath(a)).replaceAll("\\{(\\w+):[^}]*}", "{$1}"),
                                "handler", m.getNameAsString()));
                    }
                }
                smells.addAll(smells(m));
            }
            cu.findAll(StringLiteralExpr.class).forEach(s -> {
                Matcher tm = TABLE_REF.matcher(s.getValue());
                while (tm.find()) {
                    tables.add(tm.group(1).toLowerCase(Locale.ROOT));
                }
            });
            out.put(name, new ClassInfo(path, methods, new ArrayList<>(refs), routes, new ArrayList<>(tables), smells,
                    controller, bootTest));
        });
        return out;
    }

    private static String annotationPath(AnnotationExpr a) {
        if (a instanceof SingleMemberAnnotationExpr s && s.getMemberValue() instanceof StringLiteralExpr lit) {
            return lit.getValue();
        }
        if (a instanceof NormalAnnotationExpr n) {
            for (MemberValuePair p : n.getPairs()) {
                if ((p.getNameAsString().equals("value") || p.getNameAsString().equals("path"))
                        && p.getValue() instanceof StringLiteralExpr lit) {
                    return lit.getValue();
                }
            }
        }
        return "";
    }

    /** Heuristics for defects reviewers look for. Reported as evidence, never auto-fixed. */
    static List<Map<String, String>> smells(MethodDeclaration m) {
        List<Map<String, String>> found = new ArrayList<>();
        List<String> strings = m.findAll(StringLiteralExpr.class).stream().map(StringLiteralExpr::getValue).toList();
        for (String s : strings) {
            Matcher u = UPDATE_SET.matcher(s);
            if (u.find()) {
                String col = u.group(1);
                if (strings.stream().anyMatch(x -> x.toUpperCase(Locale.ROOT).startsWith("SELECT") && x.contains(col))) {
                    found.add(smell(m, "lost-update", "reads then writes '" + col + "' non-atomically"));
                }
            }
        }
        if (m.findAll(FieldAccessExpr.class).stream().anyMatch(f -> f.getNameAsString().equals("MOVED_PERMANENTLY"))) {
            found.add(smell(m, "cacheable-redirect", "301 is cached by browsers; repeat clicks never reach the server"));
        }
        boolean generatedKeys = m.findAll(ClassOrInterfaceType.class).stream()
                .anyMatch(t -> t.getNameAsString().equals("GeneratedKeyHolder"));
        boolean encodes = m.findAll(MethodCallExpr.class).stream().anyMatch(c -> c.getNameAsString().equals("encode"));
        if (generatedKeys && encodes) {
            found.add(smell(m, "enumerable-ids", "codes derived from sequential row ids can be enumerated"));
        }
        return found;
    }

    private static Map<String, String> smell(MethodDeclaration m, String kind, String detail) {
        return Map.of("function", m.getNameAsString(), "kind", kind, "detail", detail);
    }

    static List<String> schemaTables(Workspace ws) {
        Set<String> out = new TreeSet<>();
        for (String path : ws.files()) {
            if (path.contains("db/migration/") && path.endsWith(".sql")) {
                Matcher m = CREATE_TABLE.matcher(ws.read(path));
                while (m.find()) {
                    out.add(m.group(1).toLowerCase(Locale.ROOT));
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** Every class that (transitively) references one of the seeds. */
    public static Set<String> reverseClosure(Map<String, List<String>> graph, Set<String> seeds) {
        Set<String> out = new TreeSet<>(seeds);
        Deque<String> frontier = new ArrayDeque<>(seeds);
        while (!frontier.isEmpty()) {
            String cur = frontier.pop();
            graph.forEach((cls, deps) -> {
                if (deps.contains(cur) && out.add(cls)) {
                    frontier.push(cls);
                }
            });
        }
        return out;
    }
}
