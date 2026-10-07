package io.agentflow.llm;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.core.Json;
import io.agentflow.model.AgentException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Local models through Ollama: no API key, nothing leaves the network. The agent's record schema is
 * sent as Ollama's {@code format}, so decoding is constrained; the answer is still validated.
 */
public class OllamaClient implements LlmClient {

    private static final Pattern THINK = Pattern.compile("(?s)<think>.*?</think>");

    private final String baseUrl;
    private final String model;
    private final Duration timeout;
    private final HttpClient http;

    public OllamaClient() {
        this(env("OLLAMA_BASE_URL", "http://localhost:11434"), env("OLLAMA_MODEL", "qwen2.5-coder:7b"),
                Duration.ofSeconds(Long.parseLong(env("OLLAMA_TIMEOUT", "900"))));
    }

    public OllamaClient(String baseUrl, String model, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    @Override
    public String describe() {
        return "ollama:" + model + " @ " + baseUrl;
    }

    /** Fails fast with a useful message if the server or the model is missing. */
    public void check() {
        JsonNode tags = send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/tags")).timeout(Duration.ofSeconds(10))
                .GET().build(), "Ollama not reachable at " + baseUrl);
        for (JsonNode m : tags.path("models")) {
            String name = m.path("name").asText();
            if (name.equals(model) || name.equals(model + ":latest")) {
                return;
            }
        }
        throw new AgentException("model " + model + " is not pulled; run: ollama pull " + model);
    }

    @Override
    public <T> Result<T> structured(String system, String prompt, Class<T> type) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("stream", false);
        body.put("format", Schemas.of(type));
        // Bounded output: small models sometimes never close constrained JSON and would generate
        // until the timeout. A truncated answer fails schema validation fast and triggers the fallback.
        body.put("options", Map.of("temperature", 0.1, "num_ctx", 16384,
                "num_predict", Integer.parseInt(env("OLLAMA_MAX_TOKENS", "6000"))));
        body.put("messages", List.of(
                Map.of("role", "system", "content", system + "\nAnswer with JSON matching the schema only."),
                Map.of("role", "user", "content", prompt)));
        JsonNode data = send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/chat")).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build(), "Ollama request failed");
        if ("length".equals(data.path("done_reason").asText())) {
            throw new AgentException("model output truncated at the token limit (OLLAMA_MAX_TOKENS)");
        }
        String text = THINK.matcher(data.path("message").path("content").asText()).replaceAll("").strip();
        text = text.replaceFirst("^```(json)?", "").replaceFirst("```$", "").strip();
        T value;
        try {
            value = Json.MAPPER.readValue(text, type);
        } catch (IOException e) {
            throw new AgentException("model output does not match the schema: "
                    + e.getMessage().lines().findFirst().orElse(""), e);
        }
        return new Result<>(value, data.path("prompt_eval_count").asLong() + data.path("eval_count").asLong());
    }

    private JsonNode send(HttpRequest request, String context) {
        try {
            HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                throw new AgentException(context + ": HTTP " + r.statusCode() + " " + r.body());
            }
            return Json.parse(r.body());
        } catch (HttpTimeoutException e) {
            throw new AgentException("Ollama timed out after " + timeout.toSeconds() + "s", e);
        } catch (IOException e) {
            throw new AgentException(context + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentException("interrupted", e);
        }
    }
}
