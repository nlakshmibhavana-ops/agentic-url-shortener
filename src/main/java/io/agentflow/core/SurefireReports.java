package io.agentflow.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Reads Surefire XML reports: the execution evidence for which tests actually ran, and how they ended. */
public final class SurefireReports {

    /** One test class's results. {@code passed} lists "Class.method" for every test that ran and passed. */
    public record ClassResult(String className, int tests, int failures, int errors, int skipped, List<String> passed,
            List<String> failed) {
        public boolean green() {
            return tests > 0 && failures == 0 && errors == 0 && tests > skipped;
        }
    }

    private static final Pattern SUITE = Pattern.compile("<testsuite[^>]*>");
    private static final Pattern ATTR = Pattern.compile("\\b(name|tests|errors|skipped|failures)=\"([^\"]*)\"");
    private static final Pattern CASE = Pattern.compile(
            "<testcase name=\"([^\"]+)\" classname=\"([^\"]+)\"[^>]*?(/>|>(.*?)</testcase>)", Pattern.DOTALL);

    private SurefireReports() {
    }

    /** Results keyed by simple class name. */
    public static Map<String, ClassResult> read(Path dir) {
        Map<String, ClassResult> out = new TreeMap<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.getFileName().toString().matches("TEST-.*\\.xml")).sorted().toList()) {
                String xml = Files.readString(f);
                Matcher suite = SUITE.matcher(xml);
                if (!suite.find()) {
                    continue;
                }
                Map<String, String> attrs = new TreeMap<>();
                Matcher a = ATTR.matcher(suite.group());
                while (a.find()) {
                    attrs.putIfAbsent(a.group(1), a.group(2));
                }
                String fqcn = attrs.getOrDefault("name", "");
                String simple = fqcn.substring(fqcn.lastIndexOf('.') + 1);
                List<String> passed = new ArrayList<>();
                List<String> failed = new ArrayList<>();
                Matcher c = CASE.matcher(xml);
                while (c.find()) {
                    String cls = c.group(2).substring(c.group(2).lastIndexOf('.') + 1);
                    String method = c.group(1).replaceAll("[(\\[].*$", ""); // parameterized: name(args)[n]
                    String body = c.group(4) == null ? "" : c.group(4);
                    if (body.contains("<failure") || body.contains("<error")) {
                        failed.add(cls + "." + method);
                    } else if (!body.contains("<skipped")) {
                        passed.add(cls + "." + method);
                    }
                }
                out.put(simple, new ClassResult(simple, num(attrs.get("tests")), num(attrs.get("failures")),
                        num(attrs.get("errors")), num(attrs.get("skipped")), passed, failed));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private static int num(String s) {
        return s == null || s.isEmpty() ? 0 : Integer.parseInt(s);
    }

    public static void clear(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) {
                Files.deleteIfExists(f);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
