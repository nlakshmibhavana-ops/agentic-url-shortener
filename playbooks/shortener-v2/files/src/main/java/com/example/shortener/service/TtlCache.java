package com.example.shortener.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Bounded, thread-safe TTL cache (least recently used entries evicted) for the redirect hot path. */
public class TtlCache<V> {

    private record Entry<V>(V value, Instant expires) {
    }

    private final int maxSize;
    private final Duration ttl;
    private final Clock clock;
    private final Map<String, Entry<V>> entries = new LinkedHashMap<>(16, 0.75f, true);

    public TtlCache(int maxSize, Duration ttl, Clock clock) {
        this.maxSize = maxSize;
        this.ttl = ttl;
        this.clock = clock;
    }

    public synchronized Optional<V> get(String key) {
        Entry<V> entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (!entry.expires().isAfter(clock.instant())) {
            entries.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    public synchronized void put(String key, V value) {
        entries.put(key, new Entry<>(value, clock.instant().plus(ttl)));
        Iterator<String> eldest = entries.keySet().iterator();
        while (entries.size() > maxSize) {
            eldest.next();
            eldest.remove();
        }
    }

    public synchronized void invalidate(String key) {
        entries.remove(key);
    }

    public synchronized int size() {
        return entries.size();
    }
}
