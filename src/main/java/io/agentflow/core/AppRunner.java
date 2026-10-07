package io.agentflow.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Packages the workspace's Spring Boot app and runs the JAR on a free port, as it would be deployed. */
public final class AppRunner implements AutoCloseable {

    public final int port;
    private final Process process;
    private final Path log;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5)).build();

    private AppRunner(int port, Process process, Path log) {
        this.port = port;
        this.process = process;
        this.log = log;
    }

    /** Builds the JAR (tests skipped: the test stage already ran them). Returns an error or null. */
    public static String packageJar(Path workspace) {
        Maven.Result r = Maven.mvn(workspace, 600, List.of("package", "-DskipTests", "-Djacoco.skip=true",
                "-Dcheckstyle.skip=true"));
        return r.ok() ? null : "package failed:\n" + r.tail(15);
    }

    public static Path jar(Path workspace) {
        try (Stream<Path> files = Files.list(workspace.resolve("target"))) {
            return files.filter(p -> p.toString().endsWith(".jar") && !p.toString().endsWith("-plain.jar"))
                    .findFirst().orElseThrow(() -> new IllegalStateException("no JAR in target/"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Command line for running the packaged app sandboxed (it keeps the network so it can be reached). */
    public static List<String> command(Path workspace, int port, String dataName, Map<String, String> props) {
        Path data = workspace.resolve("target").resolve("agentflow-data").resolve(dataName).toAbsolutePath();
        List<String> cmd = new ArrayList<>(List.of(Sandbox.javaHome().resolve("bin/java").toString(),
                "-jar", jar(workspace).toString(), "--server.port=" + port,
                "--spring.datasource.url=jdbc:h2:file:" + data.resolve("app")));
        props.forEach((k, v) -> cmd.add("--" + k + "=" + v));
        return Sandbox.wrap(cmd, workspace, true);
    }

    public static ProcessBuilder processBuilder(List<String> cmd, Path workspace) {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(workspace.toFile());
        pb.environment().clear();
        pb.environment().putAll(Sandbox.environment(Map.of()));
        return pb;
    }

    public static AppRunner start(Path workspace, Map<String, String> props, String dataName) throws IOException {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        Path log = Files.createTempFile("agentflow-app-", ".log");
        Process p = processBuilder(command(workspace, port, dataName, props), workspace).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        AppRunner app = new AppRunner(port, p, log);
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline) {
            if (!p.isAlive()) {
                String out = Files.readString(log);
                app.close();
                throw new IOException("application exited during startup:\n" + tail(out, 15));
            }
            try {
                app.request("GET", "/", null, Map.of());
                return app;
            } catch (IOException notYet) {
                sleep(300);
            }
        }
        app.close();
        throw new IOException("application did not start within 90s");
    }

    public HttpResponse<String> request(String method, String path, String json, Map<String, String> headers)
            throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20))
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) {
            b.header("Content-Type", "application/json");
        }
        headers.forEach(b::header);
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    @Override
    public void close() {
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        try {
            Files.deleteIfExists(log);
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static String tail(String text, int lines) {
        String[] all = text.strip().split("\n");
        return String.join("\n", java.util.Arrays.copyOfRange(all, Math.max(0, all.length - lines), all.length));
    }
}
