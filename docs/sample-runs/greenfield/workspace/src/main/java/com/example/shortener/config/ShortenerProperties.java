package com.example.shortener.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Runtime configuration (prefix {@code shortener.}); every value can be set via the environment. */
@ConfigurationProperties("shortener")
public record ShortenerProperties(
        @DefaultValue("http://localhost:8000") String baseUrl,
        @DefaultValue List<String> apiKeys,
        @DefaultValue("7") int codeLength,
        @DefaultValue("2048") int maxUrlLength,
        @DefaultValue("60") int rateLimitPerMinute,
        @DefaultValue("false") boolean allowPrivateTargets,
        @DefaultValue("true") boolean asyncClicks) {

    public ShortenerProperties {
        baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        apiKeys = apiKeys.stream().map(String::strip).filter(k -> !k.isEmpty()).toList();
    }
}
