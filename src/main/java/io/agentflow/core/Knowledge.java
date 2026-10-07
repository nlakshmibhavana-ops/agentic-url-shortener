package io.agentflow.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.yaml.snakeyaml.Yaml;

/** The capability vocabulary, acceptance criteria, design records and ambiguity rules. */
public final class Knowledge {

    private static final JsonNode DATA = load();

    private Knowledge() {
    }

    private static JsonNode load() {
        try (InputStream in = Knowledge.class.getResourceAsStream("/knowledge.yaml")) {
            return Json.tree(new Yaml().load(in));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static JsonNode capabilities() {
        return DATA.get("capabilities");
    }

    public static JsonNode capability(String id) {
        return DATA.get("capabilities").path(id);
    }

    public static List<String> criteria(String id) {
        List<String> out = new ArrayList<>();
        capability(id).path("criteria").forEach(c -> out.add(c.asText()));
        return out;
    }

    public static JsonNode refines() {
        return DATA.get("refines");
    }

    public static JsonNode design(String capability) {
        return DATA.get("design").path(capability);
    }

    public static JsonNode questions() {
        return DATA.get("questions");
    }

    public static JsonNode question(String id) {
        for (JsonNode q : DATA.get("questions")) {
            if (q.get("id").asText().equals(id)) {
                return q;
            }
        }
        return null;
    }
}
