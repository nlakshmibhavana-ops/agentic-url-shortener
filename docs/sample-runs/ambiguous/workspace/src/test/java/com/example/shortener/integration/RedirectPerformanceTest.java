package com.example.shortener.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.shortener.support.Http;
import com.example.shortener.support.IntegrationTest;
import com.example.shortener.support.MutableClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Q-PERF: the agreed objective is redirect p95 under 50 ms in-process. */
@IntegrationTest
class RedirectPerformanceTest {

    private static final String[] AUTH = {"X-API-Key", "test-key-2"};

    @LocalServerPort
    int port;

    @Autowired
    MutableClock clock;

    Http http;

    @BeforeEach
    void setUp() {
        http = new Http(port);
        clock.advance(Duration.ofMinutes(1));
    }

    private String create(String json) {
        Matcher m = Pattern.compile("\"code\":\"([^\"]+)\"").matcher(http.post("/api/v1/links", json, AUTH).body());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    @Tag("AC-performance-1")
    @Tag("AC-redirect_cache-3")
    @Test
    void redirectP95IsUnder50Ms() {
        String code = create("{\"url\":\"https://example.com\"}");
        for (int i = 0; i < 20; i++) {
            http.get("/" + code); // warm-up: JIT and connection pool
        }
        List<Long> nanos = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            long started = System.nanoTime();
            assertThat(http.get("/" + code).statusCode()).isEqualTo(302);
            nanos.add(System.nanoTime() - started);
        }
        Collections.sort(nanos);
        long p95 = nanos.get((int) (nanos.size() * 0.95) - 1);
        assertThat(p95).as("p95 %.1f ms", p95 / 1e6).isLessThan(50_000_000L);
    }

    @Tag("AC-redirect_cache-2")
    @Test
    void deleteInvalidatesTheCachedRedirect() {
        String code = create("{\"url\":\"https://example.com\"}");
        assertThat(http.get("/" + code).statusCode()).isEqualTo(302);
        assertThat(http.send("DELETE", "/api/v1/links/" + code, null, AUTH).statusCode()).isEqualTo(204);
        assertThat(http.get("/" + code).statusCode()).isEqualTo(410);
    }

    @Test
    void cachedLinkStillExpires() {
        String expires = clock.instant().plusSeconds(10).toString();
        String code = create("{\"url\":\"https://example.com\",\"expires_at\":\"" + expires + "\"}");
        assertThat(http.get("/" + code).statusCode()).isEqualTo(302);
        clock.advance(Duration.ofSeconds(11));
        assertThat(http.get("/" + code).statusCode()).isEqualTo(410);
    }
}
