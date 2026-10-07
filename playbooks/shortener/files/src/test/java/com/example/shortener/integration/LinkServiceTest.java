package com.example.shortener.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.shortener.domain.Errors;
import com.example.shortener.domain.Link;
import com.example.shortener.service.LinkService;
import com.example.shortener.support.IntegrationTest;
import com.example.shortener.support.MutableClock;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class LinkServiceTest {

    @Autowired
    LinkService service;

    @Autowired
    MutableClock clock;

    @Test
    void createAndResolve() {
        Link link = service.create("https://example.com", "svc-owner", null, null, null).link();
        assertThat(service.resolve(link.code()).targetUrl()).isEqualTo("https://example.com");
    }

    @Test
    void aliasConflict() {
        service.create("https://example.com", "svc-owner", "svc-promo", null, null);
        assertThatThrownBy(() -> service.create("https://example.org", "other", "svc-promo", null, null))
                .isInstanceOf(Errors.AliasTaken.class);
    }

    @Test
    void expiryMustBeFutureAndIsEnforced() {
        assertThatThrownBy(() -> service.create("https://example.com", "svc-owner", null, clock.instant(), null))
                .isInstanceOf(Errors.InvalidExpiry.class);
        Link link = service.create("https://example.com", "svc-owner", null,
                clock.instant().plus(Duration.ofHours(1)), null).link();
        clock.advance(Duration.ofSeconds(3601));
        assertThatThrownBy(() -> service.resolve(link.code())).isInstanceOf(Errors.Gone.class);
    }

    @Test
    void idempotencyReplayAndConflict() {
        LinkService.Created a = service.create("https://example.com", "svc-owner", null, null, "svc-k1");
        LinkService.Created b = service.create("https://example.com", "svc-owner", null, null, "svc-k1");
        assertThat(b.link().code()).isEqualTo(a.link().code());
        assertThat(a.created()).isTrue();
        assertThat(b.created()).isFalse();
        assertThatThrownBy(() -> service.create("https://example.org", "svc-owner", null, null, "svc-k1"))
                .isInstanceOf(Errors.IdempotencyConflict.class);
    }

    @Test
    void ownerIsolation() {
        Link link = service.create("https://example.com", "svc-owner", null, null, null).link();
        assertThatThrownBy(() -> service.getOwned(link.code(), "intruder")).isInstanceOf(Errors.NotFound.class);
        assertThatThrownBy(() -> service.delete(link.code(), "intruder")).isInstanceOf(Errors.NotFound.class);
    }

    @Test
    void clickCountingIsAtomicUnderConcurrency() throws InterruptedException {
        Link link = service.create("https://example.com", "svc-owner", null, null, null).link();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 80; i++) {
                pool.execute(() -> service.recordClick(link.code(), null, "x"));
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(service.stats(link.code(), "svc-owner", 30).totalClicks()).isEqualTo(80);
    }
}
