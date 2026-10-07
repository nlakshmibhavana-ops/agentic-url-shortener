package io.agentflow.agents;

import io.agentflow.core.Diagnosis;
import io.agentflow.core.Playbooks;
import io.agentflow.model.AgentResult;
import io.agentflow.model.ChangeSet;
import io.agentflow.model.NoRepairException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Produces the change set for one planned task from the reviewed playbook (deterministic, reproducible).
 *
 * <p>Repair is evidence-driven. The first attempt uses the task's primary change. After this agent's own
 * change fails, it may only try a reviewed alternative whose declared {@code repairs} match the
 * structured diagnosis of that failure (phase, failing test, rule, file). If none matches, it does not
 * guess: it stops and asks for a human ({@link NoRepairException}). A failure of a different agent (a
 * model's change) says nothing about the reviewed change, so it does not count against it.
 */
public class ImplementAgent implements Agent {

    static final String NAME = "implement";

    @Override
    public AgentResult run(AgentContext ctx) {
        String task = (String) ctx.node().params.get("task");
        List<AgentContext.Attempt> own = ctx.history() == null ? List.of()
                : ctx.history().stream().filter(a -> NAME.equals(a.agent())).toList();
        int candidate = own.isEmpty() ? 0 : repairFor(ctx.playbook(), task, own);
        ChangeSet cs = Playbooks.changeset(ctx.playbook(), task, candidate);
        AgentResult r = new AgentResult();
        r.changeset = cs;
        int total = Playbooks.candidateCount(ctx.playbook(), task);
        String note = "candidate " + (cs.candidate() + 1) + "/" + total + ": " + cs.summary();
        if (!own.isEmpty()) {
            Diagnosis d = own.get(own.size() - 1).diagnosis();
            note += " (selected as the reviewed repair for diagnosed " + d.phase() + " failure"
                    + (d.failingTests().isEmpty() ? "" : " in " + d.failingTests()) + ")";
        } else if (ctx.diagnosis() != null) {
            note += " (previous attempt by another agent failed: " + abbreviate(ctx.diagnosis().summary(), 120) + ")";
        }
        return r.note(note);
    }

    /** The first untried candidate whose repair declaration matches the latest diagnosis of our own change. */
    static int repairFor(String playbook, String task, List<AgentContext.Attempt> own) {
        Diagnosis d = own.get(own.size() - 1).diagnosis();
        Set<Integer> tried = own.stream().map(AgentContext.Attempt::candidate).collect(Collectors.toSet());
        if (d.phase().equals("timeout") && !tried.isEmpty()) {
            return own.get(own.size() - 1).candidate(); // infrastructure, not the change: the same change again
        }
        int total = Playbooks.candidateCount(playbook, task);
        for (int i = 0; i < total; i++) {
            if (tried.contains(i)) {
                continue;
            }
            for (Map<String, Object> repair : Playbooks.repairs(playbook, task, i)) {
                if (d.matches(repair)) {
                    return i;
                }
            }
        }
        throw new NoRepairException("no reviewed repair addresses the diagnosed " + d.phase() + " failure"
                + (d.failingTests().isEmpty() ? "" : " " + d.failingTests())
                + (d.rules().isEmpty() ? "" : " " + d.rules()) + "; human intervention required");
    }

    static String abbreviate(String s, int max) {
        String one = s.replace('\n', ' ');
        return one.length() <= max ? one : one.substring(0, max) + "...";
    }
}
