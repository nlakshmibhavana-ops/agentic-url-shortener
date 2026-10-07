package io.agentflow.core;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import io.agentflow.model.ChangeSet;
import io.agentflow.model.Node;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guardrails evaluated on every change set before it touches the workspace.
 * Severity: block (refused; counts as a failed attempt), approve (needs a named human, bound to
 * the change's digest), warn (recorded only).
 */
public class Policy {

    public record Finding(String rule, String severity, String path, String message) {
    }

    public record Verdict(List<Finding> findings) {
        public List<Finding> blocking() {
            return findings.stream().filter(f -> f.severity().equals("block")).toList();
        }

        public List<Finding> approvals() {
            return findings.stream().filter(f -> f.severity().equals("approve")).toList();
        }
    }

    static final List<String> FORBIDDEN = List.of(".env", ".env.*", "**/*.pem", "*.pem", "**/*.key", "*.key",
            ".git/**", "secrets/**");
    static final List<String> PROTECTED = List.of("pom.xml", ".github/**", "deploy/**", "Dockerfile");
    static final List<String> GENERATED = List.of("openapi.json");
    /** Runtime libraries and build tooling (plugin dependencies are dependencies too). */
    static final List<String> DEPENDENCY_ALLOWLIST = List.of("org.springframework.boot:*", "com.h2database:h2",
            "org.springdoc:*", "org.flywaydb:*", "org.junit*:*", "org.assertj:*", "org.postgresql:postgresql",
            "com.puppycrawl.tools:checkstyle", "org.jacoco:*");
    static final int MAX_CHANGED_LINES = 800;

    private static final List<Object[]> SECRETS = List.of(
            new Object[] {Pattern.compile("AKIA[0-9A-Z]{16}"), "AWS access key id"},
            new Object[] {Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"), "private key"},
            new Object[] {Pattern.compile("sk-ant-[A-Za-z0-9_-]{20,}"), "Anthropic API key"},
            new Object[] {Pattern.compile("gh[pousr]_[A-Za-z0-9]{30,}"), "GitHub token"},
            new Object[] {Pattern.compile("github_pat_[A-Za-z0-9_]{30,}"), "GitHub token"},
            new Object[] {Pattern.compile(
                    "(?i)\\b(password|passwd|secret|api_?key|token)\\s*[=:]\\s*\"[^\"\\s]{8,}\""),
                "hard-coded credential"});
    /** Values that are obviously fixtures, not credentials (the usual scanner allowlist). */
    private static final Pattern PLACEHOLDER = Pattern.compile("(?i)test|example|dummy|fake|placeholder|change-?me|"
            + "smoke|dev-key|xxx");
    private static final Pattern PII_COLUMN = Pattern.compile(
            "(?i)\\b(ip|ip_address|remote_addr|client_ip|email)\\s+(VARCHAR|TEXT|CHAR|CLOB|INET)");
    private static final Pattern MIGRATION = Pattern.compile("(^|/)db/migration/V[^/]+\\.sql$");
    private static final Pattern DEPENDENCY = Pattern.compile(
            "<dependency>\\s*<groupId>([^<]+)</groupId>\\s*<artifactId>([^<]+)</artifactId>");
    private static final Set<String> SQL_METHODS = Set.of("execute", "executeQuery", "executeUpdate", "query",
            "queryForObject", "queryForList", "queryForMap", "update", "sql", "prepareStatement", "batchUpdate");

    private final Set<String> baseline;

    public Policy(Set<String> baseline) {
        this.baseline = baseline;
    }

    public Verdict evaluate(Node node, ChangeSet cs, Map<String, String[]> planned) {
        List<Finding> out = new ArrayList<>();
        int changed = 0;
        boolean baselineHasMigrations = baseline.stream().anyMatch(p -> MIGRATION.matcher(p).find());
        for (Map.Entry<String, String[]> e : planned.entrySet()) {
            String path = e.getKey();
            String before = e.getValue()[0];
            String after = e.getValue()[1];
            List<String> added = addedLines(before, after);
            if (!matches(path, GENERATED)) {
                changed += added.size() + addedLines(after, before).size();
            }
            if (matches(path, FORBIDDEN)) {
                out.add(new Finding("CHG-001", "block", path, "path is forbidden for agents"));
            }
            if (!node.writes.isEmpty() && !matches(path, node.writes)) {
                out.add(new Finding("CHG-003", "block", path, "outside the task's declared write set"));
            }
            if (before != null && baseline.contains(path) && matches(path, PROTECTED)) {
                out.add(new Finding("CHG-002", "approve", path, "modifies a protected file"));
            }
            if (MIGRATION.matcher(path).find()) {
                if (baseline.contains(path) && !java.util.Objects.equals(before, after)) {
                    out.add(new Finding("DATA-002", "block", path,
                            "edits a released migration (Flyway checksums; add a new V<n>__ file instead)"));
                } else if (before == null && baselineHasMigrations) {
                    out.add(new Finding("DATA-001", "approve", path, "schema change against an existing database"));
                }
            }
            if (added.stream().anyMatch(l -> PII_COLUMN.matcher(l).find())) {
                out.add(new Finding("PII-001", "block", path, "persists a raw personal identifier (IP/email)"));
            }
            if (path.endsWith(".java") && after != null) {
                out.addAll(scanJava(path, after));
            }
            out.addAll(scanText(path, added));
            if (path.endsWith("pom.xml") && after != null) {
                Set<String> newDeps = dependencies(after);
                newDeps.removeAll(dependencies(before));
                for (String dep : newDeps) {
                    if (!matches(dep.replace(':', '/'), DEPENDENCY_ALLOWLIST.stream().map(a -> a.replace(':', '/'))
                            .toList())) {
                        out.add(new Finding("DEP-001", "block", path, "dependency '" + dep + "' is not on the allowlist"));
                    } else if (before != null && baseline.contains(path)) {
                        out.add(new Finding("DEP-002", "approve", path, "adds dependency '" + dep + "'"));
                    }
                }
            }
        }
        if (changed > MAX_CHANGED_LINES) {
            out.add(new Finding("CHG-004", "approve", "*", changed + " changed lines exceeds the review budget of "
                    + MAX_CHANGED_LINES));
        }
        return new Verdict(out);
    }

    /** Dangerous APIs, found on the syntax tree (not by grep), so comments and strings don't trip it. */
    public static List<Finding> scanJava(String path, String source) {
        List<Finding> out = new ArrayList<>();
        CompilationUnit cu;
        try {
            cu = JavaSource.parse(source);
        } catch (JavaSource.ParseError e) {
            out.add(new Finding("SEC-000", "block", path, "does not parse: " + e.getMessage()));
            return out;
        }
        cu.findAll(MethodCallExpr.class).forEach(call -> {
            String name = call.getNameAsString();
            String scope = call.getScope().map(Expression::toString).orElse("");
            int line = call.getBegin().map(p -> p.line).orElse(0);
            if (name.equals("exec") && scope.contains("Runtime")) {
                out.add(new Finding("SEC-002", "block", path, "line " + line + ": Runtime.exec (command execution)"));
            }
            if (name.equals("readObject") || name.equals("readUnshared")) {
                out.add(new Finding("SEC-002", "block", path, "line " + line + ": Java deserialization (readObject)"));
            }
            if (name.equals("eval") && scope.toLowerCase().contains("engine")) {
                out.add(new Finding("SEC-002", "block", path, "line " + line + ": script engine eval"));
            }
            if (SQL_METHODS.contains(name) && call.getArguments().isNonEmpty() && dynamicSql(call.getArgument(0))) {
                out.add(new Finding("SEC-002", "block", path,
                        "line " + line + ": SQL built by string concatenation (injection risk); use bind parameters"));
            }
        });
        cu.findAll(ObjectCreationExpr.class).forEach(n -> {
            String type = n.getType().getNameAsString();
            int line = n.getBegin().map(p -> p.line).orElse(0);
            if (type.equals("ProcessBuilder")) {
                out.add(new Finding("SEC-002", "block", path, "line " + line + ": ProcessBuilder (command execution)"));
            }
            if (type.equals("ObjectInputStream")) {
                out.add(new Finding("SEC-002", "block", path, "line " + line + ": ObjectInputStream (deserialization)"));
            }
        });
        return out;
    }

    /** A SQL argument is dynamic if it concatenates anything other than string literals. */
    private static boolean dynamicSql(Expression arg) {
        if (!(arg instanceof BinaryExpr bin) || bin.getOperator() != BinaryExpr.Operator.PLUS) {
            return false;
        }
        List<Expression> leaves = new ArrayList<>();
        collect(bin, leaves);
        boolean looksLikeSql = leaves.stream().anyMatch(l -> l instanceof StringLiteralExpr s
                && s.getValue().toUpperCase().matches(".*\\b(SELECT|INSERT|UPDATE|DELETE|WHERE|FROM)\\b.*"));
        boolean hasNonLiteral = leaves.stream()
                .anyMatch(l -> !(l instanceof StringLiteralExpr) && !(l instanceof TextBlockLiteralExpr));
        return looksLikeSql && hasNonLiteral;
    }

    private static void collect(Expression e, List<Expression> out) {
        if (e instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.PLUS) {
            collect(b.getLeft(), out);
            collect(b.getRight(), out);
        } else if (e.isEnclosedExpr()) {
            collect(e.asEnclosedExpr().getInner(), out);
        } else {
            out.add(e);
        }
    }

    public static List<Finding> scanText(String path, List<String> lines) {
        List<Finding> out = new ArrayList<>();
        for (String line : lines) {
            for (Object[] s : SECRETS) {
                Matcher m = ((Pattern) s[0]).matcher(line);
                if (m.find()) {
                    if (s[1].equals("hard-coded credential") && PLACEHOLDER.matcher(m.group()).find()) {
                        continue;
                    }
                    out.add(new Finding("SEC-001", "block", path, "possible " + s[1] + " committed"));
                }
            }
        }
        return out;
    }

    static Set<String> dependencies(String pom) {
        Set<String> deps = new TreeSet<>();
        if (pom == null) {
            return deps;
        }
        Matcher m = DEPENDENCY.matcher(pom);
        while (m.find()) {
            deps.add(m.group(1).trim() + ":" + m.group(2).trim());
        }
        return deps;
    }

    static List<String> addedLines(String before, String after) {
        Set<String> old = new HashSet<>(before == null ? List.of() : List.of(before.split("\n", -1)));
        List<String> out = new ArrayList<>();
        if (after != null) {
            for (String line : after.split("\n", -1)) {
                if (!old.contains(line)) {
                    out.add(line);
                }
            }
        }
        return out;
    }

    static boolean matches(String path, List<String> globs) {
        for (String glob : globs) {
            if (glob.equals(path)) {
                return true;
            }
            PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + glob);
            if (m.matches(Path.of(path))) {
                return true;
            }
        }
        return false;
    }

    public static Map<String, Object> asMap(Finding f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rule", f.rule());
        m.put("severity", f.severity());
        m.put("path", f.path());
        m.put("message", f.message());
        return m;
    }
}
