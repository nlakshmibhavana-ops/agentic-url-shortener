package com.example.linkly;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Minimal HTTP client for black-box tests (redirects are not followed). */
public final class Http {

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final String base;

    public Http(int port) {
        this.base = "http://localhost:" + port;
    }

    public HttpResponse<String> get(String path) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).GET().build());
    }

    public HttpResponse<String> post(String path, String json) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build());
    }

    public static String field(String json, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + name + "\":(\"([^\"]*)\"|[^,}]+)").matcher(json);
        if (!m.find()) {
            throw new AssertionError("no field " + name + " in " + json);
        }
        return m.group(2) != null ? m.group(2) : m.group(1);
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
