package com.example.shortener.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.shortener.support.Http;
import com.example.shortener.support.IntegrationTest;
import com.example.shortener.support.MutableClock;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

@IntegrationTest
class ApiIntegrationTest {

    private static final String[] AUTH = {"X-API-Key", "test-key-1"};
    private static final String[] OTHER = {"X-API-Key", "test-key-2"};
    private static final Pattern CODE = Pattern.compile("\"code\":\"([^\"]+)\"");

    @LocalServerPort
    int port;

    @Autowired
    MutableClock clock;

    Http http;

    @BeforeEach
    void setUp() {
        http = new Http(port);
        // The context (and its rate limiter) is shared across tests: start each with full buckets.
        clock.advance(Duration.ofMinutes(1));
    }

    private HttpResponse<String> create(String json, String... headers) {
        return http.post("/api/v1/links", json, headers.length == 0 ? AUTH : headers);
    }

    private static String code(HttpResponse<String> response) {
        Matcher m = CODE.matcher(response.body());
        assertThat(m.find()).as(response.body()).isTrue();
        return m.group(1);
    }

    @Tag("AC-link_creation-1")
    @Tag("AC-redirect-1")
    @Tag("AC-analytics-1")
    @Test
    void createRedirectAndStatsFlow() {
        HttpResponse<String> created = create("{\"url\":\"https://example.com/landing\"}");
        assertThat(created.statusCode()).isEqualTo(201);
        String code = code(created);
        assertThat(created.body()).contains("\"short_url\":\"http://sho.rt/" + code + "\"");

        HttpResponse<String> redirect = http.get("/" + code, "Referer", "https://news.example/item");
        assertThat(redirect.statusCode()).isEqualTo(302);
        assertThat(redirect.headers().firstValue("Location")).hasValue("https://example.com/landing");
        assertThat(redirect.headers().firstValue("Cache-Control")).hasValue("private, no-store");
        http.get("/" + code);

        String stats = http.get("/api/v1/links/" + code + "/stats", AUTH).body();
        assertThat(stats).contains("\"total_clicks\":2", "{\"date\":\"" + clock.instant().toString().substring(0, 10)
                + "\",\"clicks\":2}", "{\"host\":\"news.example\",\"clicks\":1}", "{\"host\":\"direct\",\"clicks\":1}");
    }

    @Tag("AC-custom_alias-2")
    @Test
    void customAliasAndConflict() {
        String created = code(create("{\"url\":\"https://example.com\",\"alias\":\"spring-sale\"}"));
        assertThat(created).isEqualTo("spring-sale");
        HttpResponse<String> clash = create("{\"url\":\"https://example.org\",\"alias\":\"spring-sale\"}", OTHER);
        assertThat(clash.statusCode()).isEqualTo(409);
        assertThat(clash.body()).contains("\"error\":\"alias_taken\"");
    }

    @Tag("AC-url_safety-1")
    @Test
    void rejectsUnsafeTarget() {
        HttpResponse<String> response = create("{\"url\":\"javascript:alert(document.cookie)\"}");
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"error\":\"invalid_url\"");
    }

    @Test
    void rejectsUnknownFields() {
        assertThat(create("{\"url\":\"https://example.com\",\"owner\":\"someone-else\"}").statusCode()).isEqualTo(422);
    }

    @Test
    void managementRequiresApiKey() {
        assertThat(http.post("/api/v1/links", "{\"url\":\"https://example.com\"}").statusCode()).isEqualTo(401);
        assertThat(create("{\"url\":\"https://e.com\"}", "X-API-Key", "wrong").statusCode()).isEqualTo(401);
    }

    @Test
    void linksArePrivateToTheirOwner() {
        String code = code(create("{\"url\":\"https://example.com\"}"));
        assertThat(http.get("/api/v1/links/" + code, OTHER).statusCode()).isEqualTo(404);
        assertThat(http.get("/api/v1/links/" + code + "/stats", OTHER).statusCode()).isEqualTo(404);
        assertThat(http.send("DELETE", "/api/v1/links/" + code, null, OTHER).statusCode()).isEqualTo(404);
    }

    @Tag("AC-redirect-2")
    @Test
    void deleteMakesLinkGone() {
        String code = code(create("{\"url\":\"https://example.com\"}"));
        assertThat(http.send("DELETE", "/api/v1/links/" + code, null, AUTH).statusCode()).isEqualTo(204);
        assertThat(http.get("/" + code).statusCode()).isEqualTo(410);
    }

    @Tag("AC-redirect-2")
    @Tag("AC-expiry-2")
    @Test
    void expiredLinkReturnsGone() {
        String expires = clock.instant().plus(Duration.ofMinutes(5)).toString();
        String code = code(create("{\"url\":\"https://example.com\",\"expires_at\":\"" + expires + "\"}"));
        assertThat(http.get("/" + code).statusCode()).isEqualTo(302);
        clock.advance(Duration.ofSeconds(301));
        assertThat(http.get("/" + code).statusCode()).isEqualTo(410);
    }

    @Tag("AC-redirect-2")
    @Test
    void unknownCodeIs404() {
        HttpResponse<String> response = http.get("/doesNotExist");
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"error\":\"not_found\"");
    }

    @Tag("AC-reliability-1")
    @Test
    void idempotencyKeyReplaysOriginal() {
        String[] headers = {"X-API-Key", "test-key-1", "Idempotency-Key", "order-42"};
        HttpResponse<String> first = create("{\"url\":\"https://example.com\"}", headers);
        HttpResponse<String> again = create("{\"url\":\"https://example.com\"}", headers);
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(code(again)).isEqualTo(code(first));
        assertThat(create("{\"url\":\"https://example.org\"}", headers).statusCode()).isEqualTo(422);
    }

    @Tag("AC-rate_limiting-1")
    @Test
    void rateLimitReturns429WithRetryAfter() {
        String[] limits = {"X-API-Key", "test-key-limits"};
        for (int i = 0; i < 5; i++) {
            assertThat(create("{\"url\":\"https://example.com\"}", limits).statusCode()).isEqualTo(201);
        }
        HttpResponse<String> limited = create("{\"url\":\"https://example.com\"}", limits);
        assertThat(limited.statusCode()).isEqualTo(429);
        int retryAfter = Integer.parseInt(limited.headers().firstValue("Retry-After").orElseThrow());
        assertThat(retryAfter).isGreaterThanOrEqualTo(1);
        clock.advance(Duration.ofSeconds(12));
        assertThat(create("{\"url\":\"https://example.com\"}", limits).statusCode()).isEqualTo(201);
    }

    @Tag("AC-reliability-2")
    @Tag("AC-platform-1")
    @Test
    void healthAndReadiness() {
        assertThat(http.get("/healthz").body()).isEqualTo("{\"status\":\"ok\"}");
        assertThat(http.get("/readyz").statusCode()).isEqualTo(200);
    }

    @Tag("AC-reliability-3")
    @Test
    void requestIdIsPropagated() {
        HttpResponse<String> echoed = http.get("/healthz", "X-Request-ID", "abc123");
        assertThat(echoed.headers().firstValue("X-Request-ID")).hasValue("abc123");
        assertThat(http.get("/healthz").headers().firstValue("X-Request-ID")).isPresent();
    }

    @Test
    void homePageIsServed() {
        HttpResponse<String> home = http.get("/");
        assertThat(home.statusCode()).isEqualTo(200);
        assertThat(home.body()).contains("URL Shortener", "/api/v1/links");
    }
}
