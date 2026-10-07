package com.example.shortener.web;

import com.example.shortener.domain.Link;
import com.example.shortener.service.LinkService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.concurrent.Executor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RedirectController {

    private final LinkService service;
    private final Executor clickExecutor;

    public RedirectController(LinkService service, Executor clickExecutor) {
        this.service = service;
        this.clickExecutor = clickExecutor;
    }

    /** Codes and aliases are [A-Za-z0-9_-] only, so static files such as index.html never match. */
    @GetMapping("/{code:[A-Za-z0-9_-]+}")
    public ResponseEntity<Void> follow(
            @PathVariable String code,
            @RequestHeader(value = HttpHeaders.REFERER, required = false) String referer,
            @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent,
            HttpServletRequest request) {
        Link link = service.resolve(code);
        String clientIp = request.getRemoteAddr(); // only ever hashed by the service, never stored
        clickExecutor.execute(() -> service.recordClick(link.code(), referer, userAgent, clientIp));
        // 302 + no-store (not 301) so every click reaches the service and is counted.
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(link.targetUrl()))
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .build();
    }
}
