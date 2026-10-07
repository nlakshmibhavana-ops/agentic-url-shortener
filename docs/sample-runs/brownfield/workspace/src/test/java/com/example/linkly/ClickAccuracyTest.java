package com.example.linkly;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** LINK-151: click counts were under-reported (lost updates and cached 301 redirects). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:linkly-clicks;DB_CLOSE_DELAY=-1")
class ClickAccuracyTest {

    @LocalServerPort
    int port;

    @Test
    void concurrentClicksAreNotLost() throws InterruptedException {
        Http http = new Http(port);
        String code = Http.field(http.post("/shorten", "{\"url\":\"https://example.com\"}").body(), "code");
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 200; i++) {
                pool.execute(() -> http.get("/r/" + code));
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(Http.field(http.get("/info/" + code).body(), "clicks")).isEqualTo("200");
    }

    @Test
    void redirectIsNotPermanentlyCacheable() {
        Http http = new Http(port);
        String code = Http.field(http.post("/shorten", "{\"url\":\"https://example.com\"}").body(), "code");
        HttpResponse<String> resp = http.get("/r/" + code);
        assertThat(resp.statusCode()).isEqualTo(302);
        assertThat(resp.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    }
}
