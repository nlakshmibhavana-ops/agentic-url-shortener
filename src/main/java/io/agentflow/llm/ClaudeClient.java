package io.agentflow.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import io.agentflow.model.AgentException;

/**
 * Claude backend (claude-opus-5-5). Uses the SDK's class-based structured output: the JSON schema is
 * derived from the agent's record and the response is parsed into it. Needs ANTHROPIC_API_KEY.
 */
public class ClaudeClient implements LlmClient {

    static final String MODEL = "claude-opus-5-5";

    private final AnthropicClient client = AnthropicOkHttpClient.fromEnv();

    @Override
    public String describe() {
        return "claude:" + MODEL;
    }

    @Override
    public <T> Result<T> structured(String system, String prompt, Class<T> type) {
        StructuredMessageCreateParams<T> params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(16000L)
                .system(system)
                .outputConfig(type)
                .addUserMessage(prompt)
                .build();
        try {
            StructuredMessage<T> message = client.messages().create(params);
            T value = message.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(text -> text.text())
                    .findFirst()
                    .orElseThrow(() -> new AgentException("model returned no structured output (stop reason: "
                            + message.stopReason().map(Object::toString).orElse("unknown") + ")"));
            long tokens = message.usage().inputTokens() + message.usage().outputTokens();
            return new Result<>(value, tokens);
        } catch (AnthropicException e) {
            throw new AgentException("Claude API error: " + e.getMessage(), e);
        }
    }
}
