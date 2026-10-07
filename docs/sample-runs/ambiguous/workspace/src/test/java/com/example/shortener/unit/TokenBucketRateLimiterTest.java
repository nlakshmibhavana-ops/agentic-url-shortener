package com.example.shortener.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.shortener.service.TokenBucketRateLimiter;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class TokenBucketRateLimiterTest {

    private final AtomicLong nanos = new AtomicLong();

    @Tag("AC-rate_limiting-1")
    @Test
    void allowsBurstThenLimitsAndRefills() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(60, 3, nanos::get, 100);
        assertThat(limiter.acquire("k")).isZero();
        assertThat(limiter.acquire("k")).isZero();
        assertThat(limiter.acquire("k")).isZero();
        assertThat(limiter.acquire("k")).isBetween(0.01, 1.0);
        nanos.addAndGet(1_000_000_000L);
        assertThat(limiter.acquire("k")).isZero();
    }

    @Test
    void keysAreIsolated() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(60, 1, nanos::get, 100);
        assertThat(limiter.acquire("a")).isZero();
        assertThat(limiter.acquire("a")).isPositive();
        assertThat(limiter.acquire("b")).isZero();
    }

    @Test
    void memoryIsBounded() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(60, 1, nanos::get, 10);
        for (int i = 0; i < 100; i++) {
            limiter.acquire("k" + i);
        }
        // the oldest keys were evicted, so k0 starts with a full bucket again
        assertThat(limiter.acquire("k0")).isZero();
        assertThat(limiter.acquire("k99")).isPositive();
    }
}
