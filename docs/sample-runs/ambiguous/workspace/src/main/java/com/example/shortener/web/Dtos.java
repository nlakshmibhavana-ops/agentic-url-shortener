package com.example.shortener.web;

import com.example.shortener.domain.Link;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** HTTP request and response bodies (snake_case on the wire). */
public final class Dtos {

    private Dtos() {
    }

    public record CreateLinkRequest(
            @NotBlank @Size(max = 2048) String url,
            @Size(min = 3, max = 32) String alias,
            @JsonProperty("expires_at") Instant expiresAt) {
    }

    public record LinkResponse(
            String code,
            @JsonProperty("short_url") String shortUrl,
            @JsonProperty("target_url") String targetUrl,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("expires_at") Instant expiresAt,
            @JsonProperty("click_count") long clickCount) {

        static LinkResponse of(Link link, String baseUrl) {
            return new LinkResponse(link.code(), baseUrl + "/" + link.code(), link.targetUrl(), link.createdAt(),
                    link.expiresAt(), link.clickCount());
        }
    }

    public record DailyClicks(String date, long clicks) {
    }

    public record ReferrerCount(String host, long clicks) {
    }

    public record StatsResponse(
            String code,
            @JsonProperty("total_clicks") long totalClicks,
            List<DailyClicks> daily,
            @JsonProperty("top_referrers") List<ReferrerCount> topReferrers,
            @JsonProperty("unique_visitors") long uniqueVisitors) {
    }

    public record ErrorResponse(String error, String detail, @JsonProperty("request_id") String requestId) {
    }
}
