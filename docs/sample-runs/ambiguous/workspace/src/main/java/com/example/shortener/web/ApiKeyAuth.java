package com.example.shortener.web;

import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.domain.Errors;
import com.example.shortener.service.LinkService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;

/** Resolves the X-API-Key header to a stable owner id; raw keys never reach the database. */
@Component
public class ApiKeyAuth {

    private final ShortenerProperties props;

    public ApiKeyAuth(ShortenerProperties props) {
        this.props = props;
    }

    public String owner(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new Errors.Unauthorized();
        }
        byte[] given = apiKey.getBytes(StandardCharsets.UTF_8);
        boolean ok = false;
        for (String key : props.apiKeys()) {
            // Constant-time comparison for every configured key.
            ok |= MessageDigest.isEqual(given, key.getBytes(StandardCharsets.UTF_8));
        }
        if (!ok) {
            throw new Errors.Unauthorized();
        }
        return LinkService.ownerId(apiKey);
    }
}
