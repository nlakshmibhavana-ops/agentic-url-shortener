package io.agentflow.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Approver registry: who may approve what. Each approver has roles and a random token; only a salted
 * SHA-256 of the token is stored (tokens are 256-bit random, so a slow KDF adds nothing). Approving
 * requires presenting the token, so attribution is authenticated, not self-declared.
 *
 * <p>Roles: {@code change} (protected files, large diffs, high-impact tasks), {@code data} (schema
 * changes against existing databases), {@code release} (cutting a release).
 */
public final class Approvers {

    public record Approver(String name, List<String> roles) {
    }

    public static class AuthenticationException extends RuntimeException {
        public AuthenticationException(String message) {
            super(message);
        }
    }

    private Approvers() {
    }

    public static Path file() {
        String env = System.getenv("AGENTFLOW_APPROVERS");
        return env != null ? Path.of(env) : Home.root().resolve("config").resolve("approvers.yaml");
    }

    static JsonNode load(Path file) {
        try {
            return Files.exists(file) ? Json.tree(new Yaml().load(Files.readString(file))) : Json.MAPPER.createObjectNode();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static boolean known(String name) {
        return name != null && load(file()).path("approvers").has(name);
    }

    public static Approver authenticate(String name, String token) {
        return authenticate(file(), name, token);
    }

    public static Approver authenticate(Path file, String name, String token) {
        JsonNode a = load(file).path("approvers").path(name == null ? "" : name);
        if (a.isMissingNode() || token == null || token.isEmpty()) {
            throw new AuthenticationException("unknown approver or missing token");
        }
        byte[] expected = HexFormat.of().parseHex(a.get("sha256").asText());
        byte[] actual = HexFormat.of().parseHex(Json.sha256(a.get("salt").asText() + ":" + token));
        if (!MessageDigest.isEqual(expected, actual)) { // constant time
            throw new AuthenticationException("invalid token for approver '" + name + "'");
        }
        List<String> roles = new ArrayList<>();
        a.path("roles").forEach(r -> roles.add(r.asText()));
        return new Approver(name, roles);
    }

    /** Registers (or rotates) an approver and returns the new token, which is shown once and never stored. */
    @SuppressWarnings("unchecked")
    public static String add(Path file, String name, List<String> roles) {
        SecureRandom random = new SecureRandom();
        byte[] tokenBytes = new byte[32];
        random.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        byte[] saltBytes = new byte[16];
        random.nextBytes(saltBytes);
        String salt = HexFormat.of().formatHex(saltBytes);
        Map<String, Object> doc = Json.convert(load(file), Map.class);
        Map<String, Object> approvers = (Map<String, Object>) doc.computeIfAbsent("approvers", k -> new LinkedHashMap<>());
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("roles", roles);
        entry.put("salt", salt);
        entry.put("sha256", Json.sha256(salt + ":" + token));
        approvers.put(name, entry);
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        FileLocks.writeAtomically(file, "# Approver registry: roles and salted SHA-256 of each token (tokens are never "
                + "stored).\n" + new Yaml(opts).dump(doc));
        return token;
    }

    /** The role an approval request needs, from its reasons. */
    public static String requiredRole(String nodeId, List<String> reasons) {
        if (nodeId.equals("release")) {
            return "release";
        }
        return reasons.stream().anyMatch(r -> r.startsWith("DATA-")) ? "data" : "change";
    }
}
