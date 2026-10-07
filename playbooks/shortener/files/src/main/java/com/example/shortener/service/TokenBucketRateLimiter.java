package com.example.shortener.service;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** In-process token bucket per client key, with bounded memory (least recently used keys evicted). */
public class TokenBucketRateLimiter {

    private final double ratePerNano;
    private final double capacity;
    private final LongSupplier nanoClock;
    private final int maxKeys;
    private final Map<String, double[]> buckets = new LinkedHashMap<>(16, 0.75f, true);

    public TokenBucketRateLimiter(int ratePerMinute, int burst, LongSupplier nanoClock, int maxKeys) {
        this.ratePerNano = ratePerMinute / 60e9;
        this.capacity = burst;
        this.nanoClock = nanoClock;
        this.maxKeys = maxKeys;
    }

    /** Takes one token. Returns 0 when allowed, else the seconds until a token is available. */
    public synchronized double acquire(String key) {
        long now = nanoClock.getAsLong();
        double[] b = buckets.computeIfAbsent(key, k -> new double[] {capacity, now});
        b[0] = Math.min(capacity, b[0] + (now - (long) b[1]) * ratePerNano);
        b[1] = now;
        while (buckets.size() > maxKeys) {
            Iterator<String> eldest = buckets.keySet().iterator();
            eldest.next();
            eldest.remove();
        }
        if (b[0] >= 1) {
            b[0] -= 1;
            return 0;
        }
        return (1 - b[0]) / ratePerNano / 1e9;
    }

    synchronized int size() {
        return buckets.size();
    }
}
