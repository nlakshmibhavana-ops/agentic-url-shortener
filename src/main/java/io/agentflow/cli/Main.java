package io.agentflow.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentflow.core.AppRunner;
import io.agentflow.core.Approvals;
import io.agentflow.core.AuditLog;
import io.agentflow.core.Home;
import io.agentflow.core.Json;
import io.agentflow.core.Metrics;
import io.agentflow.core.Playbooks;
import io.agentflow.engine.Engine;
import io.agentflow.engine.Report;
import io.agentflow.engine.RunState;
import io.agentflow.engine.Scenario;
import io.agentflow.llm.ClaudeClient;
import io.agentflow.llm.LlmClient;
import io.agentflow.llm.OllamaClient;
import io.agentflow.model.Node;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Command-line interface. In a terminal, {@code run} is interactive: it shows each step live, stops to
 * ask when a human decision is needed (a clarifying question or an approval), then carries on. With
 * --no-input (or without a terminal) it pauses instead; continue with answer/approve + resume.
 */
@Command(name = "agentflow", mixinStandardHelpOptions = true, version = "agentflow 0.1.0",
        description = "Governed agentic SDLC orchestrator.",
        subcommands = {Main.Run.class, Main.Resume.class, Main.Approve.class, Main.Answer.class, Main.Status.class,
            Main.Stop.class, Main.Verify.class, Main.ReportCmd.class, Main.MetricsCmd.class, Main.ListCmd.class,
            Main.Serve.class, Main.Demo.class})
public class Main implements Runnable {

    static final boolean COLOR = System.console() != null && System.getenv("NO_COLOR") == null;
    static final String AF = "./agentflow";
    private static final BufferedReader STDIN = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }

    static String c(String text, String code) {
        return COLOR ? "\033[" + code + "m" + text + "\033[0m" : text;
    }

    static String bold(String t) {
        return c(t, "1");
    }

    static String green(String t) {
        return c(t, "32");
    }

    static String red(String t) {
        return c(t, "31");
    }

    static String yellow(String t) {
        return c(t, "33");
    }

    static String dim(String t) {
        return c(t, "2");
    }

    /** True when attached to an interactive terminal (Console.isTerminal on JDK 22+, else console presence). */
    static boolean interactive() {
        if (System.console() == null) {
            return false;
        }
        try {
            return (Boolean) java.io.Console.class.getMethod("isTerminal").invoke(System.console());
        } catch (ReflectiveOperationException e) {
            return true;
        }
    }

    static Path runDir(String run) {
        Path runs = Home.runs();
        Path p = runs.resolve(run);
        if (Files.isDirectory(p)) {
            return p;
        }
        try (Stream<Path> s = Files.list(runs)) {
            List<Path> matches = s.filter(d -> d.getFileName().toString().contains(run)).toList();
            if (matches.size() == 1) {
                return matches.getFirst();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        throw new CommandLine.ParameterException(new CommandLine(new Main()), "run '" + run + "' not found (or ambiguous)");
    }

    static LlmClient llm(String provider) {
        return switch (provider) {
            case "claude" -> new ClaudeClient();
            case "ollama" -> {
                OllamaClient client = new OllamaClient();
                try {
                    client.check();
                } catch (RuntimeException e) {
                    System.out.println(red("Ollama unavailable (" + e.getMessage()
                            + "); LLM agents will fall back to deterministic ones."));
                }
                System.out.println(dim("LLM provider: " + client.describe()));
                yield client;
            }
            default -> null;
        };
    }

    // ------------------------------------------------------------------ live progress

    static final class Progress implements java.util.function.Consumer<JsonNode> {
        private final long t0 = System.nanoTime();

        @Override
        public void accept(JsonNode e) {
            String ev = e.get("event").asText();
            String node = e.path("node").asText("");
            JsonNode d = e.get("data");
            String line = switch (ev) {
                case "attempt.start" -> dim("->") + " " + node + (d.get("attempt").asInt() > 1
                        ? " (attempt " + d.get("attempt").asInt() + ")" : "") + " " + dim("agent=" + d.get("agent").asText());
                case "agent.note" -> "   " + dim(node + ": " + cut(d.get("note").asText(), 110));
                case "change.applied" -> "   " + node + ": applied change to " + cut(String.join(", ",
                        names(d.get("paths"))), 110) + " (+" + d.get("added").asInt() + "/-" + d.get("removed").asInt() + ")";
                case "gate.exit" -> d.get("passed").asBoolean() ? null : "   " + red("x") + " " + node + ": check '"
                        + d.get("gate").asText() + "' failed: " + cut(d.get("detail").asText(), 140);
                case "change.rolled_back" -> "   " + yellow("<-") + " " + node + ": change rolled back "
                        + d.path("reason").asText("");
                case "attempt.retry" -> "   " + yellow("retry") + " " + node + ": trying again (attempt "
                        + d.get("next_attempt").asInt() + ")";
                case "policy.blocked" -> "   " + red("BLOCKED by policy") + " " + node + ": " + cut(d.get("reason").asText(), 140);
                case "node.succeeded" -> green("ok") + " " + node;
                case "node.failed" -> red("FAILED") + " " + node + ": " + cut(d.path("error").asText(), 140);
                case "node.waiting" -> yellow("..") + " " + node + " is waiting for you";
                case "plan.created" -> "   plan v1: " + d.get("added").size() + " tasks: " + String.join(", ",
                        names(d.get("added")));
                case "plan.revised" -> "   " + bold("re-plan") + " v" + d.get("plan_version").asInt() + ": added "
                        + d.get("added") + ", removed " + d.get("removed") + ", kept without re-running " + d.get("reused");
                case "node.invalidated" -> "   " + dim(node + " must re-run: " + d.get("reason").asText());
                case "run.safe_stop" -> red("SAFE STOP: " + d.get("reason").asText());
                default -> null;
            };
            if (line != null) {
                System.out.printf("%s %s%n", dim(String.format("[%5.1fs]", (System.nanoTime() - t0) / 1e9)), line);
                System.out.flush();
            }
        }

        private static List<String> names(JsonNode array) {
            List<String> out = new ArrayList<>();
            array.forEach(v -> {
                String s = v.asText();
                out.add(s.substring(s.lastIndexOf('/') + 1));
            });
            return out;
        }
    }

    static String cut(String s, int max) {
        String one = s.replace('\n', ' ');
        return one.length() <= max ? one : one.substring(0, max) + "...";
    }

    // ------------------------------------------------------------------ human prompts

    static String ask(String prompt, List<String> choices) throws IOException {
        while (true) {
            System.out.print(prompt);
            System.out.flush();
            String line = STDIN.readLine();
            if (line == null) {
                throw new IOException("no input");
            }
            String answer = line.strip().toLowerCase();
            if (choices.contains(answer)) {
                return answer;
            }
            System.out.println("  please type one of: " + String.join(", ", choices));
        }
    }

    /** Prompts for every open question and pending approval. Returns true if anything was decided. */
    static boolean askHumans(Path dir, String who) throws IOException {
        RunState st = new RunState(dir);
        boolean acted = false;
        JsonNode req = st.store.get("requirements");
        for (JsonNode qid : req.path("blocking_open")) {
            JsonNode q = null;
            for (JsonNode x : req.get("questions")) {
                if (x.get("id").asText().equals(qid.asText())) {
                    q = x;
                }
            }
            System.out.println(bold("\n=== QUESTION " + qid.asText() + " (the agents will not guess this) ==="));
            System.out.println("Found in the request: \"" + q.get("evidence").asText() + "\"");
            System.out.println(q.get("text").asText());
            List<String> options = new ArrayList<>();
            q.get("options").fields().forEachRemaining(o -> {
                options.add(o.getKey());
                System.out.printf("  %-20s %s%n", bold(o.getKey()), o.getValue().asText());
            });
            String choice = ask("Your answer [" + String.join("/", options) + "]: ", options);
            new Engine(dir, 1, null, null).submitAnswer(qid.asText(), choice, who);
            System.out.println(green("Recorded " + qid.asText() + " = " + choice + ". The plan will be revised."));
            acted = true;
        }
        Approvals approvals = new Approvals(dir.resolve("approvals.json"), new AuditLog(dir.resolve("audit.jsonl"), st.runId));
        for (JsonNode r : approvals.pending()) {
            String node = r.get("node").asText();
            System.out.println(bold("\n=== APPROVAL NEEDED: " + node + " ==="));
            System.out.println("Why a human must approve:");
            r.get("reasons").forEach(x -> System.out.println("  - " + x.asText()));
            System.out.println(r.get("summary").asText());
            Path diff = latestDiff(dir, node);
            if (diff != null) {
                System.out.println(dim("Full diff: " + diff));
            }
            String choice = ask("Approve? [y/n/d=show diff]: ", List.of("y", "n", "d"));
            if (choice.equals("d") && diff != null) {
                System.out.println(Files.readString(diff));
                choice = ask("Approve? [y/n]: ", List.of("y", "n"));
            }
            System.out.print("Comment (optional): ");
            System.out.flush();
            String comment = STDIN.readLine();
            approvals.decide(node, choice.equals("y"), who, comment == null ? "" : comment.strip());
            System.out.println(choice.equals("y") ? green("Approved.") : red("Rejected: the change will be rolled back."));
            acted = true;
        }
        return acted;
    }

    static Path latestDiff(Path dir, String node) throws IOException {
        try (Stream<Path> s = Files.list(dir.resolve("changes"))) {
            return s.filter(p -> p.getFileName().toString().startsWith(node + ".a")).sorted().reduce((a, b) -> b)
                    .orElse(null);
        }
    }

    // ------------------------------------------------------------------ run and summary

    static int execute(Path dir, int maxParallel, boolean interactive, String who) throws IOException {
        String status;
        while (true) {
            RunState st = new RunState(dir);
            Engine engine = new Engine(dir, maxParallel, llm(st.scenario.path("provider").asText("deterministic")),
                    new Progress());
            Thread hook = new Thread(() -> engine.requestStop("SIGINT"));
            Runtime.getRuntime().addShutdownHook(hook);
            status = engine.execute();
            Runtime.getRuntime().removeShutdownHook(hook);
            if (status.equals("waiting") && interactive && askHumans(dir, who)) {
                System.out.println(bold("\nContinuing...\n"));
                continue;
            }
            break;
        }
        summary(dir);
        return status.equals("succeeded") || status.equals("waiting") ? 0 : 1;
    }

    static void summary(Path dir) throws IOException {
        RunState st = new RunState(dir);
        String status = st.status.toUpperCase();
        String colored = st.status.equals("succeeded") ? green(status) : st.status.equals("waiting") ? yellow(status)
                : red(status);
        System.out.println("\n" + bold("=".repeat(70)));
        System.out.println("Run " + st.runId + ": " + colored + (st.stopReason != null ? "  (" + st.stopReason + ")" : ""));
        System.out.println(bold("=".repeat(70)));
        if (st.status.equals("waiting")) {
            printPending(dir);
        }
        Path ws = dir.resolve("workspace");
        System.out.println("\n" + bold("Code built by this run:") + "  " + ws);
        try (Stream<Path> s = Files.list(ws)) {
            s.sorted().forEach(p -> System.out.println("   " + p.getFileName() + (Files.isDirectory(p) ? "/" : "")));
        }
        JsonNode tests = st.store.get("test_report");
        if (!tests.isMissingNode()) {
            System.out.println("\n" + bold("Tests:") + " " + tests.get("passed") + " passed, " + tests.get("failed")
                    + " failed, line coverage " + tests.get("coverage") + "%");
        }
        JsonNode rel = st.store.get("release");
        if (!rel.isMissingNode() && st.status.equals("succeeded")) {
            System.out.println(bold("Released version:") + " " + rel.get("version").asText());
        }
        System.out.println("\n" + bold("Evidence report:") + "  " + dir.resolve("report.md"));
        if (st.status.equals("succeeded")) {
            System.out.println("\n" + bold("Try the service:") + "  " + AF + " serve " + st.runId);
            if (Playbooks.load(st.scenario.get("playbook").asText()).path("package").asText().endsWith("shortener")) {
                System.out.println(dim("   then open http://localhost:8000/ in a browser (or use curl):"));
                System.out.println(dim("   curl -s -XPOST localhost:8000/api/v1/links -H 'X-API-Key: dev-key' "
                        + "-H 'Content-Type: application/json' -d '{\"url\":\"https://example.com\",\"alias\":\"demo\"}'"));
                System.out.println(dim("   curl -si localhost:8000/demo | head -3        # 302 redirect"));
            }
        }
    }

    static void printPending(Path dir) {
        RunState st = new RunState(dir);
        Approvals approvals = new Approvals(dir.resolve("approvals.json"), new AuditLog(dir.resolve("audit.jsonl"), st.runId));
        for (JsonNode r : approvals.pending()) {
            System.out.println("\nApproval needed for '" + r.get("node").asText() + "': "
                    + String.join("; ", texts(r.get("reasons"))));
            System.out.println("  " + AF + " approve " + st.runId + " " + r.get("node").asText() + " --by <your-name>");
        }
        JsonNode req = st.store.get("requirements");
        for (JsonNode qid : req.path("blocking_open")) {
            for (JsonNode q : req.get("questions")) {
                if (q.get("id").asText().equals(qid.asText())) {
                    System.out.println("\nQuestion " + qid.asText() + ": " + q.get("text").asText());
                    q.get("options").fields().forEachRemaining(o -> System.out.printf("    %-10s %s%n", o.getKey(),
                            o.getValue().asText()));
                }
            }
            System.out.println("  " + AF + " answer " + st.runId + " " + qid.asText() + " <option> --by <your-name>");
        }
        System.out.println("then: " + AF + " resume " + st.runId);
    }

    static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(v -> out.add(v.asText()));
        return out;
    }

    // ------------------------------------------------------------------ commands

    abstract static class RunOptions {
        @Option(names = "--no-input", description = "never prompt; pause for human actions")
        boolean noInput;

        @Option(names = "--as", description = "your name for the audit trail", defaultValue = "${env:USER}")
        String who;

        @Option(names = "--max-parallel", defaultValue = "4")
        int maxParallel;
    }

    @Command(name = "run", description = "start a scenario (greenfield | brownfield | ambiguous)")
    static class Run extends RunOptions implements Callable<Integer> {
        @Parameters(index = "0")
        String scenario;

        @Option(names = "--provider", defaultValue = "deterministic", description = "deterministic | ollama | claude")
        String provider;

        @Option(names = "--run-id")
        String runId;

        @Override
        public Integer call() throws IOException {
            ObjectNode sc = Scenario.load(scenario);
            sc.put("provider", provider);
            Path dir = Engine.create(sc, Home.runs(), runId);
            System.out.println(bold("Run " + dir.getFileName() + ": " + sc.get("title").asText()));
            System.out.println(dim("Requirement:\n  " + sc.get("requirement").asText().strip().replace("\n", "\n  ")) + "\n");
            return execute(dir, maxParallel, !noInput && interactive(), who);
        }
    }

    @Command(name = "resume", description = "continue a paused or stopped run")
    static class Resume extends RunOptions implements Callable<Integer> {
        @Parameters(index = "0")
        String run;

        @Override
        public Integer call() throws IOException {
            return execute(runDir(run), maxParallel, !noInput && interactive(), who);
        }
    }

    @Command(name = "approve", description = "record an approval decision (non-interactive)")
    static class Approve implements Callable<Integer> {
        @Parameters(index = "0")
        String run;

        @Parameters(index = "1")
        String node;

        @Option(names = "--by", required = true)
        String by;

        @Option(names = "--reject")
        boolean reject;

        @Option(names = "--comment", defaultValue = "")
        String comment;

        @Override
        public Integer call() {
            Path dir = runDir(run);
            RunState st = new RunState(dir);
            try {
                JsonNode d = new Approvals(dir.resolve("approvals.json"), new AuditLog(dir.resolve("audit.jsonl"),
                        st.runId)).decide(node, !reject, by, comment);
                System.out.println(node + ": " + d.get("status").asText() + " by " + by + ". Run `resume` to continue.");
                return 0;
            } catch (IllegalStateException | IllegalArgumentException e) {
                System.out.println(red(e.getMessage()));
                printPending(dir);
                return 1;
            }
        }
    }

    @Command(name = "answer", description = "answer a clarifying question (non-interactive)")
    static class Answer implements Callable<Integer> {
        @Parameters(index = "0")
        String run;

        @Parameters(index = "1")
        String question;

        @Parameters(index = "2")
        String option;

        @Option(names = "--by", required = true)
        String by;

        @Override
        public Integer call() {
            new Engine(runDir(run), 1, null, null).submitAnswer(question, option, by);
            System.out.println(question + " = " + option + ". Run `resume` to re-plan and continue.");
            return 0;
        }
    }

    @Command(name = "status", description = "show run status")
    static class Status implements Callable<Integer> {
        @Parameters(index = "0")
        String run;

        @Override
        public Integer call() throws IOException {
            Path dir = runDir(run);
            RunState st = new RunState(dir);
            for (String id : st.graph.topologicalOrder()) {
                Node n = st.graph.get(id);
                String note = n.waitReason != null ? n.waitReason : n.error != null ? n.error : "";
                System.out.printf("  %-10s %-28s attempts=%d %s%n", n.status.value(), id, n.attempts, cut(note, 100));
            }
            summary(dir);
            return 0;
        }
    }

    @Command(name = "stop", description = "request a safe stop")
    static class Stop implements Callable<Integer> {
        @Parameters(index = "0")
        String run;

        @Override
        public Integer call() {
            Engine.touchStop(runDir(run));
            System.out.println("stop requested; the run halts safely at the next scheduling step");
            return 0;
        }
    }

    @Command(name = "verify", description = "verify the audit hash chain")
    static class Verify implements Callable<Integer> {
        @Parameters(index = "0")
        String run;

        @Override
        public Integer call() {
            Path audit = runDir(run).resolve("audit.jsonl");
            String problem = AuditLog.verify(audit);
            System.out.println(problem == null ? "OK: " + AuditLog.read(audit).size() + " records, chain intact"
                    : "TAMPERED: " + problem);
            return problem == null ? 0 : 2;
        }
    }

    @Command(name = "report", description = "regenerate report.md")
    static class ReportCmd implements Callable<Integer> {
        @Parameters(index = "0")
        String run;

        @Override
        public Integer call() {
            System.out.println(Report.write(runDir(run)));
            return 0;
        }
    }

    @Command(name = "metrics", description = "aggregate reliability metrics across runs")
    static class MetricsCmd implements Callable<Integer> {
        @Override
        public Integer call() {
            System.out.println(Json.pretty(Metrics.aggregate(Home.runs())));
            return 0;
        }
    }

    @Command(name = "list", description = "list runs")
    static class ListCmd implements Callable<Integer> {
        @Override
        public Integer call() throws IOException {
            Path runs = Home.runs();
            if (!Files.isDirectory(runs)) {
                return 0;
            }
            try (Stream<Path> s = Files.list(runs)) {
                for (Path d : s.sorted().toList()) {
                    if (Files.exists(d.resolve("state.json"))) {
                        System.out.printf("%-45s %s%n", d.getFileName(), new RunState(d).status);
                    }
                }
            }
            return 0;
        }
    }

    @Command(name = "serve", description = "build and start the service a run produced")
    static class Serve implements Callable<Integer> {
        @Parameters(index = "0")
        String run;

        @Option(names = "--port", defaultValue = "8000")
        int port;

        @Override
        public Integer call() throws IOException, InterruptedException {
            Path dir = runDir(run);
            RunState st = new RunState(dir);
            Path ws = dir.resolve("workspace");
            System.out.println("Building " + ws + " ...");
            String err = AppRunner.packageJar(ws);
            if (err != null) {
                System.out.println(red(err));
                return 1;
            }
            List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-jar", AppRunner.jar(ws).toString(), "--server.port=" + port));
            JsonNode book = Playbooks.load(st.scenario.get("playbook").asText());
            book.path("serve_props").fields().forEachRemaining(e -> cmd.add("--" + e.getKey() + "="
                    + e.getValue().asText().replace("{run_dir}", dir.toString())));
            if (book.path("package").asText().endsWith("shortener")) {
                cmd.add("--shortener.base-url=http://localhost:" + port);
            }
            System.out.println("Serving on " + bold("http://localhost:" + port + "/") + "  (Ctrl+C to stop)");
            System.out.println("  OpenAPI: http://localhost:" + port + "/v3/api-docs");
            Process p = new ProcessBuilder(cmd).directory(ws.toFile()).inheritIO().start();
            Runtime.getRuntime().addShutdownHook(new Thread(p::destroy));
            return p.waitFor();
        }
    }

    @Command(name = "demo", description = "run all three scenarios non-interactively (scripts/demo.sh)")
    static class Demo implements Callable<Integer> {
        @Override
        public Integer call() throws IOException, InterruptedException {
            Path script = Home.root().resolve("scripts").resolve("demo.sh");
            return new ProcessBuilder("bash", script.toString()).inheritIO().start().waitFor();
        }
    }
}
