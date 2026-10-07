package io.agentflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentflow.core.Policy;
import io.agentflow.model.ChangeSet;
import io.agentflow.model.FileOp;
import io.agentflow.model.Node;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PolicyTest {

    static Set<String> evaluate(String path, String after, String before, List<String> writes, Set<String> baseline) {
        Node node = new Node("t", "task", "implement", "t");
        node.writes = new java.util.ArrayList<>(writes == null ? List.of(path) : writes);
        ChangeSet cs = new ChangeSet("t", "s", List.of(FileOp.write(path, after)), 0);
        return new Policy(baseline).evaluate(node, cs, Map.of(path, new String[] {before, after})).findings().stream()
                .map(f -> f.rule() + "/" + f.severity()).collect(Collectors.toSet());
    }

    static Set<String> evaluate(String path, String after) {
        return evaluate(path, after, null, null, Set.of());
    }

    static String cls(String body) {
        return "class A {\n  void m(java.sql.Statement st, String id) throws Exception {\n    " + body + "\n  }\n}\n";
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "Runtime.getRuntime().exec(\"rm -rf /\");|SEC-002",
        "new ProcessBuilder(\"sh\").start();|SEC-002",
        "new java.io.ObjectInputStream(null).readObject();|SEC-002",
        "st.executeQuery(\"SELECT * FROM t WHERE id = \" + id);|SEC-002",
        "String token = \"AKIAABCDEFGHIJKLMNOP\";|SEC-001",
        "String password = \"hunter2hunter2\";|SEC-001",
        "int broken = ;|SEC-000"})
    void securityFindingsBlock(String body, String rule) {
        assertThat(evaluate("src/main/java/A.java", cls(body))).contains(rule + "/block");
    }

    @Test
    void literalOnlySqlAndBindParametersAreFine() {
        assertThat(evaluate("src/main/java/A.java", cls("st.executeQuery(\"SELECT * FROM t\" + \" WHERE id = ?\");")))
                .isEmpty();
    }

    @Test
    void placeholderCredentialsInFixturesAreAllowed() {
        assertThat(evaluate("src/test/java/T.java", "class T { String apiKey = \"test-key-1\"; }\n")).isEmpty();
    }

    @Test
    void writesOutsideTheDeclaredScopeAreBlocked() {
        assertThat(evaluate("src/main/java/Other.java", "class Other {}", null, List.of("src/main/java/Mine.java"), Set.of()))
                .contains("CHG-003/block");
    }

    @Test
    void forbiddenPathsAreBlocked() {
        assertThat(evaluate(".env", "A=1")).contains("CHG-001/block");
    }

    @Test
    void newMigrationNeedsApprovalOnlyAgainstAnExistingDatabase() {
        String path = "src/main/resources/db/migration/V2__x.sql";
        String sql = "ALTER TABLE t ADD COLUMN c VARCHAR(10);";
        Set<String> existing = Set.of("src/main/resources/db/migration/V1__init.sql");
        assertThat(evaluate(path, sql, null, null, existing)).contains("DATA-001/approve");
        assertThat(evaluate(path, sql, null, null, Set.of())).doesNotContain("DATA-001/approve");
    }

    @Test
    void releasedMigrationsAreImmutable() {
        String path = "src/main/resources/db/migration/V1__init.sql";
        assertThat(evaluate(path, "CREATE TABLE t (a INT, b INT);", "CREATE TABLE t (a INT);", null, Set.of(path)))
                .contains("DATA-002/block");
    }

    @Test
    void rawPersonalDataColumnsAreBlocked() {
        assertThat(evaluate("src/main/resources/db/migration/V2__ip.sql", "ALTER TABLE c ADD COLUMN client_ip VARCHAR(45);"))
                .contains("PII-001/block");
    }

    @Test
    void dependenciesMustBeAllowlisted() {
        String before = "<project><dependencies><dependency><groupId>org.springframework.boot</groupId>"
                + "<artifactId>spring-boot-starter-webmvc</artifactId></dependency></dependencies></project>";
        String bad = before.replace("</dependencies>", "<dependency><groupId>com.evil</groupId><artifactId>leftpad"
                + "</artifactId></dependency></dependencies>");
        String ok = before.replace("</dependencies>", "<dependency><groupId>org.springdoc</groupId><artifactId>"
                + "springdoc-openapi-starter-webmvc-api</artifactId></dependency></dependencies>");
        assertThat(evaluate("pom.xml", bad, before, null, Set.of("pom.xml"))).contains("DEP-001/block");
        assertThat(evaluate("pom.xml", ok, before, null, Set.of("pom.xml"))).contains("DEP-002/approve", "CHG-002/approve");
    }

    @Test
    void largeDiffsNeedApprovalButGeneratedFilesDoNotCount() {
        String big = "x\n".repeat(0) + java.util.stream.IntStream.range(0, 900).mapToObj(i -> "line" + i)
                .collect(Collectors.joining("\n"));
        assertThat(evaluate("notes.md", big)).contains("CHG-004/approve");
        assertThat(evaluate("openapi.json", big)).doesNotContain("CHG-004/approve");
    }
}
