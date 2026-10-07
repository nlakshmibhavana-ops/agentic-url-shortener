package io.agentflow.agents;

import io.agentflow.core.Json;
import io.agentflow.llm.LlmClient;
import io.agentflow.model.AgentException;
import io.agentflow.model.AgentResult;
import io.agentflow.model.ChangeSet;
import io.agentflow.model.FileOp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Asks the model for the task's change, feeding back the previous attempt's gate failures. The
 * engine policy-checks, applies, verifies and, if needed, rolls back the change, exactly as for a
 * playbook change; a model never writes to the workspace directly.
 */
public class LlmImplementAgent implements Agent {

    public record GeneratedFile(String path, String content) {
    }

    public record Change(String summary, List<GeneratedFile> files) {
    }

    @Override
    public AgentResult run(AgentContext ctx) {
        if (ctx.llm() == null) {
            throw new AgentException("no LLM provider configured");
        }
        Map<String, String> current = new LinkedHashMap<>();
        for (String p : ctx.node().writes) {
            if (ctx.workspace().exists(p)) {
                current.put(p, ctx.workspace().read(p));
            }
        }
        Map<String, String> tests = new LinkedHashMap<>();
        for (String p : ctx.node().verify()) {
            if (p.endsWith(".java") && ctx.workspace().exists(p)) {
                tests.put(p, ctx.workspace().read(p));
            }
        }
        String prompt = String.join("\n\n",
                "Task " + ctx.node().params.get("task") + ": " + ctx.node().title,
                "Project: Spring Boot 4 / Java 21, Maven, Flyway migrations, Checkstyle enforced.",
                "Acceptance tests that must pass:\n" + (tests.isEmpty() ? "(none yet)" : Json.pretty(tests)),
                "Allowed paths (write only these, complete file contents): " + ctx.node().writes,
                "Current content of those paths:\n" + (current.isEmpty() ? "(new files)" : Json.pretty(current)),
                "Design decisions:\n" + ctx.store().get("design").path("adrs"),
                "Previous attempt feedback:\n" + (ctx.feedback() == null ? "none" : ctx.feedback()));
        LlmClient.Result<Change> res = ctx.llm().structured(
                "You are a senior Java engineer. Produce minimal, production-quality changes that make the "
                        + "acceptance tests pass. Return complete file contents. Never add dependencies, secrets, "
                        + "command execution or string-built SQL. Released Flyway migrations are immutable.",
                prompt, Change.class);
        Change change = res.value();
        if (change.files() == null || change.files().isEmpty()) {
            throw new AgentException("model returned no files");
        }
        List<FileOp> ops = change.files().stream().map(f -> FileOp.write(f.path(), f.content())).toList();
        AgentResult r = new AgentResult();
        r.changeset = new ChangeSet((String) ctx.node().params.get("task"),
                change.summary() == null ? "model change" : change.summary(), ops, 0);
        r.tokens = res.tokens();
        return r.note("model change: " + ImplementAgent.abbreviate(r.changeset.summary(), 140));
    }
}
