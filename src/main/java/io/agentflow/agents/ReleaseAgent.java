package io.agentflow.agents;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.model.AgentResult;
import io.agentflow.model.ChangeSet;
import io.agentflow.model.FileOp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Cuts the release: version bump in the POM, VERSION and release notes. High impact: needs approval. */
public class ReleaseAgent implements Agent {

    private static final Pattern PARENT = Pattern.compile("(?s)<parent>.*?</parent>");
    private static final Pattern VERSION = Pattern.compile("<version>([^<]+)</version>");

    @Override
    public AgentResult run(AgentContext ctx) {
        String pom = ctx.workspace().read("pom.xml");
        Matcher m = VERSION.matcher(PARENT.matcher(pom).replaceFirst(""));
        if (!m.find()) {
            throw new IllegalStateException("pom.xml has no project version");
        }
        String current = m.group(1);
        boolean greenfield = !ctx.baselineFiles().contains("pom.xml");
        String next = greenfield ? current : bumpMinor(current);
        JsonNode plan = ctx.store().get("plan");
        JsonNode readiness = ctx.store().get("release_readiness");
        List<String> notes = new ArrayList<>(List.of("# Release " + next, "", ctx.scenario().get("title").asText(), "",
                "## Changes", ""));
        plan.get("tasks").forEach(t -> {
            if (!t.get("agent").asText().equals("none")) {
                notes.add("- " + t.get("title").asText());
            }
        });
        notes.addAll(List.of("", "## Evidence", ""));
        readiness.get("checks").forEach(c -> notes.add("- [" + (c.get("passed").asBoolean() ? "x" : " ") + "] "
                + c.get("check").asText() + ": " + c.get("detail").asText()));
        if (!readiness.get("residual_risks").isEmpty()) {
            notes.addAll(List.of("", "## Known risks / follow-ups", ""));
            readiness.get("residual_risks").forEach(r -> notes.add("- " + r.asText()));
        }
        List<FileOp> ops = new ArrayList<>(List.of(FileOp.write("VERSION", next + "\n"),
                FileOp.write("RELEASE_NOTES.md", String.join("\n", notes) + "\n")));
        if (!next.equals(current)) {
            String anchor = "<version>" + current + "</version>";
            // The project version follows the artifactId; anchor on both so a dependency can't match.
            Matcher a = Pattern.compile("<artifactId>[^<]+</artifactId>\\s*" + Pattern.quote(anchor))
                    .matcher(PARENT.matcher(pom).replaceFirst(""));
            String old = a.find() ? a.group() : anchor;
            ops.add(FileOp.edit("pom.xml", old, old.replace(anchor, "<version>" + next + "</version>")));
        }
        AgentResult r = new AgentResult().artifact("release", Map.of("version", next, "previous", current));
        r.changeset = new ChangeSet(ctx.node().id, "Release " + next, ops, 0);
        return r;
    }

    static String bumpMinor(String version) {
        String[] p = version.replaceAll("[^0-9.]", "").split("\\.");
        int major = Integer.parseInt(p[0]);
        int minor = p.length > 1 ? Integer.parseInt(p[1]) : 0;
        return major + "." + (minor + 1) + ".0";
    }
}
