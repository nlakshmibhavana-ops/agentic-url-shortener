package com.example.shortener.domain;

import java.time.Instant;

public record Link(String code, String targetUrl, String owner, Instant createdAt, Instant expiresAt,
        Instant deletedAt, long clickCount) {

    public boolean isGone(Instant now) {
        return deletedAt != null || (expiresAt != null && !expiresAt.isAfter(now));
    }
}
