package com.example.shortener.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.shortener.service.TtlCache;
import com.example.shortener.support.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TtlCacheTest {

    private final MutableClock clock = new MutableClock();

    @Test
    void getPutAndExpiry() {
        TtlCache<Integer> cache = new TtlCache<>(10, Duration.ofSeconds(30), clock);
        cache.put("a", 1);
        assertThat(cache.get("a")).contains(1);
        clock.advance(Duration.ofSeconds(30));
        assertThat(cache.get("a")).isEmpty();
    }

    @Test
    void leastRecentlyUsedEntriesAreEvicted() {
        TtlCache<Integer> cache = new TtlCache<>(2, Duration.ofSeconds(30), clock);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.get("a");
        cache.put("c", 3);
        assertThat(cache.get("a")).contains(1);
        assertThat(cache.get("b")).isEmpty();
        assertThat(cache.get("c")).contains(3);
        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    void invalidate() {
        TtlCache<Integer> cache = new TtlCache<>(2, Duration.ofSeconds(30), clock);
        cache.put("a", 1);
        cache.invalidate("a");
        cache.invalidate("missing");
        assertThat(cache.get("a")).isEmpty();
    }
}
