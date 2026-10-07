package com.example.linkly;

import java.net.URI;
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

    public record ShortenRequest(String url) {
    }

    @PostMapping("/shorten")
    public Map<String, String> shorten(@RequestBody ShortenRequest body) {
        String code = store.create(body.url());
        return Map.of("code", code, "short", "/r/" + code);
    }

    @GetMapping("/r/{code}")
    public ResponseEntity<Void> go(@PathVariable String code) {
        Map<String, Object> row = store.lookup(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "not found"));
        store.hit(code);
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .location(URI.create((String) row.get("URL")))
                .build();
    }

    @GetMapping("/info/{code}")
    public Map<String, Object> info(@PathVariable String code) {
        Map<String, Object> row = store.lookup(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "not found"));
        return Map.of("code", code, "url", row.get("URL"), "clicks", row.get("CLICKS"));
    }
}
