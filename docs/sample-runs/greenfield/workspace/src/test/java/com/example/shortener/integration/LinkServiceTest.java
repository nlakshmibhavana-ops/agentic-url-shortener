package com.example.shortener.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.domain.Errors;
import com.example.shortener.domain.Link;
import com.example.shortener.service.CodeGenerator;
import com.example.shortener.service.LinkRepository;
import com.example.shortener.service.LinkService;
import com.example.shortener.support.IntegrationTest;
import com.example.shortener.support.MutableClock;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class LinkServiceTest {

    @Autowired
    LinkService service;

    @Autowired
    MutableClock clock;

    @Tag("AC-link_creation-1")
    @Test
    void createAndResolve() {
        Link link = service.create("https://example.com", "svc-owner", null, null, null).link();
        assertThat(service.resolve(link.code()).targetUrl()).isEqualTo("https://example.com");
    }

    @Autowired
    LinkRepository repo;

    @Autowired
    ShortenerProperties props;

    @Tag("AC-link_creation-3")
    @Test
    void codeCollisionsAreRetriedTransparently() {
        String taken = service.create("https://example.com/first", "svc-owner", null, null, null).link().code();
        // A generator whose first two codes collide with an existing link.
        CodeGenerator colliding = new CodeGenerator() {
            private int calls;

            @Override
            public String generate(int length) {
                return calls++ < 2 ? taken : super.generate(length);
            }
        };
        Link link = new LinkService(repo, colliding, props, clock).create("https://example.com/second", "svc-owner",
                null, null, null).link();
        assertThat(link.code()).isNotEqualTo(taken);
        assertThat(service.resolve(taken).targetUrl()).isEqualTo("https://example.com/first");
        assertThat(service.resolve(link.code()).targetUrl()).isEqualTo("https://example.com/second");
    }

    @Tag("AC-custom_alias-2")
    @Test
    void aliasConflict() {
        service.create("https://example.com", "svc-owner", "svc-promo", null, null);
        assertThatThrownBy(() -> service.create("https://example.org", "other", "svc-promo", null, null))
                .isInstanceOf(Errors.AliasTaken.class);
    }

    @Tag("AC-expiry-1")
    @Tag("AC-expiry-2")
    @Test
    void expiryMustBeFutureAndIsEnforced() {
        assertThatThrownBy(() -> service.create("https://example.com", "svc-owner", null, clock.instant(), null))
                .isInstanceOf(Errors.InvalidExpiry.class);
        Link link = service.create("https://example.com", "svc-owner", null,
                clock.instant().plus(Duration.ofHours(1)), null).link();
        clock.advance(Duration.ofSeconds(3601));
        assertThatThrownBy(() -> service.resolve(link.code())).isInstanceOf(Errors.Gone.class);
    }

    @Tag("AC-reliability-1")
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
