package io.agentflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.agentflow.core.Json;
import io.agentflow.llm.LlmClient;
import io.agentflow.llm.OllamaClient;
import io.agentflow.model.AgentException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The Ollama client against a fake server: valid, malformed and unreachable responses. */
class OllamaClientTest {

    record Answer(List<String> capabilities) {
    }

    HttpServer server;
    final AtomicReference<String> lastBody = new AtomicReference<>();

    String start(String chatContent, String tags) throws IOException {
        return start(chatContent, tags, "stop");
    }

    String start(String chatContent, String tags, String doneReason) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", ex -> {
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = Json.write(Map.of("message", Map.of("content", chatContent), "prompt_eval_count", 120,
                    "eval_count", 30, "done_reason", doneReason)).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.createContext("/api/tags", ex -> {
            byte[] out = tags.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void schemaConstrainedAnswerIsParsedAndCounted() throws IOException {
        String url = start("<think>hmm</think>```json\n{\"capabilities\": [\"expiry\"]}\n```", "{}");
        LlmClient.Result<Answer> r = new OllamaClient(url, "m", Duration.ofSeconds(5)).structured("sys", "p", Answer.class);
        assertThat(r.value().capabilities()).containsExactly("expiry");
        assertThat(r.tokens()).isEqualTo(150);
        assertThat(Json.parse(lastBody.get()).at("/format/properties/capabilities/type").asText()).isEqualTo("array");
        assertThat(Json.parse(lastBody.get()).get("stream").asBoolean()).isFalse();
    }

    @Test
    void malformedOutputRaisesAgentException() throws IOException {
        String url = start("Sure! The capabilities are: expiry", "{}");
        assertThatThrownBy(() -> new OllamaClient(url, "m", Duration.ofSeconds(5)).structured("s", "p", Answer.class))
                .isInstanceOf(AgentException.class).hasMessageContaining("does not match the schema");
    }

    @Test
    void runawayOutputIsCappedAndReportedAsTruncated() throws IOException {
        String url = start("{\"capabilities\": [\"expiry\", \"expiry\", \"expi", "{}", "length");
        assertThatThrownBy(() -> new OllamaClient(url, "m", Duration.ofSeconds(5)).structured("s", "p", Answer.class))
                .isInstanceOf(AgentException.class).hasMessageContaining("truncated");
        assertThat(Json.parse(lastBody.get()).at("/options/num_predict").asInt()).isPositive();
    }

    @Test
    void unreachableServerAndMissingModel() throws IOException {
        assertThatThrownBy(() -> new OllamaClient("http://127.0.0.1:9", "m", Duration.ofSeconds(2)).check())
                .isInstanceOf(AgentException.class).hasMessageContaining("not reachable");
        String url = start("{}", "{\"models\":[{\"name\":\"other:7b\"}]}");
        assertThatThrownBy(() -> new OllamaClient(url, "m", Duration.ofSeconds(2)).check())
                .hasMessageContaining("ollama pull m");
    }
}
