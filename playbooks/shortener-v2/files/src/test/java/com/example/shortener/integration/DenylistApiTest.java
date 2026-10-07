package com.example.shortener.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.shortener.support.Http;
import com.example.shortener.support.IntegrationTest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

@IntegrationTest
class DenylistApiTest {

    @LocalServerPort
    int port;

    @Test
    void blockedDomainIsRejectedThroughTheApi() {
        HttpResponse<String> response = new Http(port).post("/api/v1/links",
                "{\"url\":\"https://login.evil.example/reset\"}", "X-API-Key", "test-key-2");
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"error\":\"invalid_url\"", "blocked");
    }
}
