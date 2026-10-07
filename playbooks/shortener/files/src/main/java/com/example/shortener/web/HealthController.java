package com.example.shortener.web;

import com.example.shortener.service.LinkRepository;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final LinkRepository repo;

    public HealthController(LinkRepository repo) {
        this.repo = repo;
    }

    /** Liveness: the process is up. */
    @GetMapping("/healthz")
    public Map<String, String> healthz() {
        return Map.of("status", "ok");
    }

    /** Readiness: the database answers. */
    @GetMapping("/readyz")
    public ResponseEntity<Map<String, String>> readyz() {
        boolean ok;
        try {
            ok = repo.ping();
        } catch (RuntimeException e) {
            ok = false;
        }
        return ResponseEntity.status(ok ? 200 : 503).body(Map.of("status", ok ? "ready" : "unavailable"));
    }
}
