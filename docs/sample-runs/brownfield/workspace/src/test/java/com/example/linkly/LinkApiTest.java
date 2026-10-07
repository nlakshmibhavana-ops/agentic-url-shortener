package com.example.linkly;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:linkly-api;DB_CLOSE_DELAY=-1")
class LinkApiTest {

    @LocalServerPort
    int port;

    @Test
    void shortenAndRedirect() {
        Http http = new Http(port);
        String code = Http.field(http.post("/shorten", "{\"url\":\"https://example.com\"}").body(), "code");
        HttpResponse<String> resp = http.get("/r/" + code);
        assertThat(resp.statusCode()).isIn(301, 302);
        assertThat(resp.headers().firstValue("Location")).hasValue("https://example.com");
    }

    @Test
    void infoCountsClicks() {
        Http http = new Http(port);
        String code = Http.field(http.post("/shorten", "{\"url\":\"https://example.com\"}").body(), "code");
        http.get("/r/" + code);
        http.get("/r/" + code);
        assertThat(Http.field(http.get("/info/" + code).body(), "clicks")).isEqualTo("2");
    }

    @Test
    void unknownCode() {
        assertThat(new Http(port).get("/r/zzz").statusCode()).isEqualTo(404);
    }
}
