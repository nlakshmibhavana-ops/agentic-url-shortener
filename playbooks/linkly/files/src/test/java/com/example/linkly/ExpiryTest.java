package com.example.linkly;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** LINK-150: optional link expiry; expired links answer 410 Gone. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:linkly-expiry;DB_CLOSE_DELAY=-1")
class ExpiryTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void linkWithoutExpiryStillWorks() {
        Http http = new Http(port);
        String code = Http.field(http.post("/shorten", "{\"url\":\"https://example.com\"}").body(), "code");
        assertThat(http.get("/r/" + code).statusCode()).isEqualTo(302);
        assertThat(http.get("/info/" + code).body()).contains("\"expires_at\":null");
    }

    @Test
    void expiringLinkReportsItsExpiry() {
        Http http = new Http(port);
        String body = http.post("/shorten", "{\"url\":\"https://example.com\",\"expires_in_days\":7}").body();
        String expires = Http.field(http.get("/info/" + Http.field(body, "code")).body(), "expires_at");
        Duration left = Duration.between(Instant.now(), Instant.parse(expires));
        assertThat(left).isBetween(Duration.ofDays(7).minusHours(1), Duration.ofDays(7));
    }

    @Test
    void expiredLinkIsGone() {
        Http http = new Http(port);
        String body = http.post("/shorten", "{\"url\":\"https://example.com\",\"expires_in_days\":1}").body();
        String code = Http.field(body, "code");
        jdbc.update("UPDATE urls SET expires_at = ? WHERE code = ?", OffsetDateTime.now().minusMinutes(1), code);
        assertThat(http.get("/r/" + code).statusCode()).isEqualTo(410);
    }

    @Test
    void expiryMustBePositiveAndBounded() {
        Http http = new Http(port);
        for (int days : new int[] {0, -1, 3651}) {
            String json = "{\"url\":\"https://example.com\",\"expires_in_days\":" + days + "}";
            assertThat(http.post("/shorten", json).statusCode()).as("days=%d", days).isEqualTo(400);
        }
    }
}
