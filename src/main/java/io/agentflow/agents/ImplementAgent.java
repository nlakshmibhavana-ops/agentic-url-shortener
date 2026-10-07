package io.agentflow.agents;

import io.agentflow.core.Playbooks;
import io.agentflow.model.AgentException;
import io.agentflow.model.AgentResult;
import io.agentflow.model.ChangeSet;

/** Produces the change set for one planned task from the reviewed playbook (deterministic, reproducible). */
public class ImplementAgent implements Agent {

    @Override
    public AgentResult run(AgentContext ctx) {
        String task = (String) ctx.node().params.get("task");
        int total = Playbooks.candidateCount(ctx.playbook(), task);
        if (total > 1 && ctx.attempt() > total) {
            throw new AgentException("all " + total + " candidate implementations exhausted");
        }
        ChangeSet cs = Playbooks.changeset(ctx.playbook(), task, ctx.attempt() - 1);
        AgentResult r = new AgentResult();
        r.changeset = cs;
        String note = "candidate " + (cs.candidate() + 1) + "/" + total + ": " + cs.summary();
        if (ctx.feedback() != null) {
            note += " (previous attempt failed: " + abbreviate(ctx.feedback(), 120) + ")";
        }
        return r.note(note);
    }

    static String abbreviate(String s, int max) {
        String one = s.replace('\n', ' ');
        return one.length() <= max ? one : one.substring(0, max) + "...";
    }
}
