package com.example.linkly;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** linkly HTTP API. */
@RestController
public class LinkController {

    private final LinkStore store;

    public LinkController(LinkStore store) {
        this.store = store;
    }

    public record ShortenRequest(String url,
            @JsonProperty("expires_in_days") @Min(1) @Max(3650) Integer expiresInDays) {
    }

    @PostMapping("/shorten")
    public Map<String, String> shorten(@Valid @RequestBody ShortenRequest body) {
        String url;
        try {
            url = UrlValidator.validate(body.url());
        } catch (UrlValidator.InvalidUrlException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
        Instant expiresAt = body.expiresInDays() == null
                ? null : Instant.now().plus(Duration.ofDays(body.expiresInDays()));
        String code = store.create(url, expiresAt);
        return Map.of("code", code, "short", "/r/" + code);
    }

    @GetMapping("/r/{code}")
    public ResponseEntity<Void> go(@PathVariable String code) {
        Map<String, Object> row = store.lookup(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "not found"));
        if (row.get("EXPIRES_AT") instanceof OffsetDateTime at && !at.toInstant().isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.GONE, "link expired");
        }
        store.hit(code);
        // 302 + no-store: a cached 301 never reaches us again, so its clicks went uncounted.
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create((String) row.get("URL")))
                .header("Cache-Control", "private, no-store")
                .build();
    }

    @GetMapping("/info/{code}")
    public Map<String, Object> info(@PathVariable String code) {
        Map<String, Object> row = store.lookup(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "not found"));
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("code", code);
        info.put("url", row.get("URL"));
        info.put("clicks", row.get("CLICKS"));
        Object expires = row.get("EXPIRES_AT");
        info.put("expires_at", expires == null ? null : ((OffsetDateTime) expires).toInstant().toString());
        return info;
    }
}
