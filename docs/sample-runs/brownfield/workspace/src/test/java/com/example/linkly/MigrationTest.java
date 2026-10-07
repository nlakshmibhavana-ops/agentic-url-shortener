package com.example.linkly;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Production databases created by v0.4.x must upgrade in place, without data loss. */
class MigrationTest {

    @Test
    void upgradesAnExistingDatabaseWithLiveRows(@TempDir Path dir) throws SQLException {
        String url = "jdbc:h2:file:" + dir.resolve("prod-v04");
        // The database as v0.4 left it: schema version 1, with real rows in it.
        flyway(url).target("1").load().migrate();
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
            s.execute("INSERT INTO urls (code, url, clicks) VALUES ('1', 'https://old.example', 41)");
        }

        flyway(url).load().migrate();
        flyway(url).load().migrate(); // idempotent

        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT url, clicks, expires_at FROM urls WHERE code = '1'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("url")).isEqualTo("https://old.example");
            assertThat(rs.getInt("clicks")).isEqualTo(41);
            assertThat(rs.getObject("expires_at")).isNull();
        }
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration flyway(String url) {
        return Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration");
    }
}
