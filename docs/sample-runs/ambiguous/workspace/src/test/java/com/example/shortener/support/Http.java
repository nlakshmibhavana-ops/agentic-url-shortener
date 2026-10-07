package com.example.shortener.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Minimal HTTP client for integration tests (redirects are not followed). */
public final class Http {

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final String base;

    public Http(int port) {
        this.base = "http://localhost:" + port;
    }

    public HttpResponse<String> send(String method, String path, String json, String... headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        if (headers.length > 0) {
            b.headers(headers);
        }
        b.method(method, json == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) {
            b.header("Content-Type", "application/json");
        }
        try {
            return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public HttpResponse<String> get(String path, String... headers) {
        return send("GET", path, null, headers);
    }

    public HttpResponse<String> post(String path, String json, String... headers) {
        return send("POST", path, json, headers);
    }
}
