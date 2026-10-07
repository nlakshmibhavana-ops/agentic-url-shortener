package com.example.shortener.web;

import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.domain.Errors;
import com.example.shortener.domain.LinkStats;
import com.example.shortener.service.LinkService;
import com.example.shortener.service.TokenBucketRateLimiter;
import com.example.shortener.web.Dtos.CreateLinkRequest;
import com.example.shortener.web.Dtos.DailyClicks;
import com.example.shortener.web.Dtos.LinkResponse;
import com.example.shortener.web.Dtos.ReferrerCount;
import com.example.shortener.web.Dtos.StatsResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/links")
public class LinkController {

    private final LinkService service;
    private final ApiKeyAuth auth;
    private final TokenBucketRateLimiter limiter;
    private final ShortenerProperties props;

    public LinkController(LinkService service, ApiKeyAuth auth, TokenBucketRateLimiter limiter,
            ShortenerProperties props) {
        this.service = service;
        this.auth = auth;
        this.limiter = limiter;
        this.props = props;
    }

    @PostMapping
    public ResponseEntity<LinkResponse> create(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateLinkRequest body) {
        String owner = auth.owner(apiKey);
        double wait = limiter.acquire(owner);
        if (wait > 0) {
            throw new Errors.RateLimited(Math.max(1, Math.round(wait)));
        }
        LinkService.Created result = service.create(body.url(), owner, body.alias(), body.expiresAt(),
                idempotencyKey);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(LinkResponse.of(result.link(), props.baseUrl()));
    }

    @GetMapping("/{code}")
    public LinkResponse get(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @PathVariable String code) {
        return LinkResponse.of(service.getOwned(code, auth.owner(apiKey)), props.baseUrl());
    }

    @DeleteMapping("/{code}")
    public ResponseEntity<Void> delete(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @PathVariable String code) {
        service.delete(code, auth.owner(apiKey));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{code}/stats")
    public StatsResponse stats(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @PathVariable String code, @RequestParam(defaultValue = "30") int days) {
        LinkStats stats = service.stats(code, auth.owner(apiKey), Math.max(1, Math.min(days, 365)));
        return new StatsResponse(stats.code(), stats.totalClicks(),
                stats.daily().stream().map(d -> new DailyClicks(d.date(), d.clicks())).toList(),
                stats.topReferrers().stream().map(h -> new ReferrerCount(h.host(), h.clicks())).toList());
    }
}
