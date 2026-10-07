package com.example.shortener.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.shortener.support.Http;
import com.example.shortener.support.IntegrationTest;
import com.example.shortener.support.MutableClock;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Q-PRIVACY (answer: hashed): unique visitors without storing personal data. */
@IntegrationTest
class VisitorAnalyticsTest {

    private static final String[] AUTH = {"X-API-Key", "test-key-2"};

    @LocalServerPort
    int port;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcClient jdbc;

    Http http;

    @BeforeEach
    void setUp() {
        http = new Http(port);
        clock.advance(Duration.ofMinutes(1));
    }

    private String create() {
        Matcher m = Pattern.compile("\"code\":\"([^\"]+)\"")
                .matcher(http.post("/api/v1/links", "{\"url\":\"https://example.com\"}", AUTH).body());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    @Tag("AC-unique_visitors-1")
    @Test
    void uniqueVisitorsAreCountedPerDay() {
        String code = create();
        http.get("/" + code, "User-Agent", "Mozilla (X11)");
        http.get("/" + code, "User-Agent", "Mozilla (X11)");
        http.get("/" + code, "User-Agent", "Mozilla (iPhone)");
        assertThat(http.get("/api/v1/links/" + code + "/stats", AUTH).body())
                .contains("\"total_clicks\":3", "\"unique_visitors\":2");

        clock.advance(Duration.ofDays(1)); // the daily key rotates, so the same visitor counts again
        http.get("/" + code, "User-Agent", "Mozilla (X11)");
        assertThat(http.get("/api/v1/links/" + code + "/stats", AUTH).body()).contains("\"unique_visitors\":3");
    }

    @Tag("AC-unique_visitors-2")
    @Test
    void rawIpIsNeverPersisted() {
        String code = create();
        http.get("/" + code);
        List<Map<String, Object>> rows = jdbc.sql("SELECT * FROM clicks WHERE code = ?").param(code)
                .query().listOfRows();
        assertThat(rows).isNotEmpty();
        for (Map<String, Object> row : rows) {
            assertThat(row.values().toString()).doesNotContain("127.0.0.1", "0:0:0:0:0:0:0:1");
            assertThat((String) row.get("VISITOR_HASH")).hasSize(16);
        }
    }

    @Test
    void existingDatabaseIsMigrated(@TempDir Path dir) throws SQLException {
        String url = "jdbc:h2:file:" + dir.resolve("v1");
        Flyway.configure().dataSource(url, "sa", "").target("1").load().migrate();
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
            s.execute("INSERT INTO links (code, target_url, owner, created_at) VALUES ('old', 'https://o.example', 'o',"
                    + " TIMESTAMP WITH TIME ZONE '2026-01-01 00:00:00+00')");
            s.execute("INSERT INTO clicks (code, clicked_at, clicked_day) VALUES ('old',"
                    + " TIMESTAMP WITH TIME ZONE '2026-01-01 00:00:00+00', '2026-01-01')");
        }
        Flyway.configure().dataSource(url, "sa", "").load().migrate();
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT code, visitor_hash FROM clicks")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("code")).isEqualTo("old");
            assertThat(rs.getString("visitor_hash")).isNull();
        }
    }
}
