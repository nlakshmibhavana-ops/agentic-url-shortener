package io.agentflow.agents;

import io.agentflow.core.Maven;
import io.agentflow.core.Playbooks;
import io.agentflow.model.AgentResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Runs the full suite with JaCoCo; reads Surefire XML and the JaCoCo CSV as evidence for the release gate. */
public class TestRunnerAgent implements Agent {

    private static final Pattern SUITE_ALT = Pattern.compile("<testsuite[^>]*>");
    private static final Pattern ATTR = Pattern.compile("(tests|errors|skipped|failures)=\"(\\d+)\"");
    private static final Pattern FAILED_CASE = Pattern.compile(
            "<testcase name=\"([^\"]+)\" classname=\"([^\"]+)\"[^>]*>\\s*<(failure|error)");

    @Override
    public AgentResult run(AgentContext ctx) {
        Path ws = ctx.workspace().root;
        Maven.Result r = ctx.locked(() -> Maven.mvn(ws, 1800, List.of("clean", "test")));
        Map<String, Object> report = new LinkedHashMap<>(readSurefire(ws.resolve("target/surefire-reports")));
        List<String> passedTests = new ArrayList<>();
        io.agentflow.core.SurefireReports.read(ws.resolve("target/surefire-reports")).values()
                .forEach(c -> passedTests.addAll(c.passed()));
        report.put("passed_tests", passedTests.stream().distinct().sorted().toList());
        Double coverage = lineCoverage(ws.resolve("target/site/jacoco/jacoco.csv"),
                Playbooks.load(ctx.playbook()).path("package").asText(""));
        int failed = (int) report.get("failed") + (int) report.get("errors");
        report.put("total", (int) report.get("passed") + failed);
        report.put("ok", r.ok());
        report.put("coverage", coverage);
        report.put("tail", r.ok() ? "" : r.tail(20));
        return new AgentResult().artifact("test_report", report).note(report.get("passed") + " passed, "
                + report.get("failed") + " failed, line coverage " + coverage + "%");
    }

    static Map<String, Object> readSurefire(Path dir) {
        int tests = 0;
        int errors = 0;
        int skipped = 0;
        int failures = 0;
        List<String> failed = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.filter(p -> p.getFileName().toString().startsWith("TEST-")).sorted().toList()) {
                    String xml = Files.readString(f);
                    Matcher suite = SUITE_ALT.matcher(xml);
                    if (suite.find()) {
                        Matcher a = ATTR.matcher(suite.group());
                        while (a.find()) {
                            int v = Integer.parseInt(a.group(2));
                            switch (a.group(1)) {
                                case "tests" -> tests += v;
                                case "errors" -> errors += v;
                                case "skipped" -> skipped += v;
                                case "failures" -> failures += v;
                                default -> {
                                }
                            }
                        }
                    }
                    Matcher c = FAILED_CASE.matcher(xml);
                    while (c.find()) {
                        String cls = c.group(2);
                        failed.add(cls.substring(cls.lastIndexOf('.') + 1) + "." + c.group(1));
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("passed", tests - errors - skipped - failures);
        out.put("failed", failures);
        out.put("errors", errors);
        out.put("skipped", skipped);
        out.put("failures", failed);
        return out;
    }

    /** Line coverage (%) of the production package from JaCoCo's CSV report, or null when absent. */
    static Double lineCoverage(Path csv, String pkg) {
        if (!Files.exists(csv)) {
            return null;
        }
        long missed = 0;
        long covered = 0;
        try {
            List<String> lines = Files.readAllLines(csv);
            List<String> header = List.of(lines.getFirst().split(","));
            int iPkg = header.indexOf("PACKAGE");
            int iMissed = header.indexOf("LINE_MISSED");
            int iCovered = header.indexOf("LINE_COVERED");
            for (String line : lines.subList(1, lines.size())) {
                String[] cols = line.split(",");
                if (pkg.isEmpty() || cols[iPkg].startsWith(pkg)) {
                    missed += Long.parseLong(cols[iMissed]);
                    covered += Long.parseLong(cols[iCovered]);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return missed + covered == 0 ? null : Math.round(1000.0 * covered / (missed + covered)) / 10.0;
    }
}
