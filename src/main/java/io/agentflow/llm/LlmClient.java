package io.agentflow.llm;

/** A model that answers with JSON matching a Java record. Implementations throw AgentException on bad output. */
public interface LlmClient {

    record Result<T>(T value, long tokens) {
    }

    <T> Result<T> structured(String system, String prompt, Class<T> type);

    String describe();
}
