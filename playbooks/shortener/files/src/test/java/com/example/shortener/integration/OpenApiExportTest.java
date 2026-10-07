package com.example.shortener.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.shortener.support.Http;
import com.example.shortener.support.IntegrationTest;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Exports the live OpenAPI contract to target/openapi.json, so documentation cannot drift from code. */
@IntegrationTest
class OpenApiExportTest {

    @LocalServerPort
    int port;

    @Test
    void exportsTheOpenApiContract() throws IOException {
        HttpResponse<String> response = new Http(port).get("/v3/api-docs");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"openapi\":\"3", "/api/v1/links");
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "openapi.json"), response.body());
    }
}
