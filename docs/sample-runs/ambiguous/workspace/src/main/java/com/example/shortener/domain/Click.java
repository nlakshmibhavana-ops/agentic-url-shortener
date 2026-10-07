package com.example.shortener.domain;

import java.time.Instant;

public record Click(Instant clickedAt, String referrerHost, String userAgentFamily, String visitorHash) {
}
