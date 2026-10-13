package dev.switchyard;

import java.util.*;
import java.util.function.LongSupplier;

/** Per-socket-peer token buckets. Client-supplied forwarding headers are never trusted. */
final class RateLimiter {
    private static final class Bucket {
        double tokens;
        long updated;
        Bucket(double tokens, long now) { this.tokens = tokens; updated = now; }
    }
    private final Map<String, Bucket> buckets = new LinkedHashMap<>(16, .75f, true);
    private final double rate;
    private final int burst, maxClients;
    private final LongSupplier clock;
    RateLimiter(double rate, int burst, int maxClients) { this(rate, burst, maxClients, System::nanoTime); }
    RateLimiter(double rate, int burst, int maxClients, LongSupplier clock) {
        this.rate = rate; this.burst = burst; this.maxClients = maxClients; this.clock = clock;
    }
    synchronized boolean allow(String client) {
        if (rate == 0) return true;
        long now = clock.getAsLong();
        Bucket bucket = buckets.get(client);
        if (bucket == null) {
            if (buckets.size() >= maxClients) {
                // Evict only a fully replenished idle bucket; churn cannot reset a depleted bucket.
                Iterator<Bucket> it = buckets.values().iterator();
                Bucket oldest = it.next();
                if (refilled(oldest, now) < burst) return false;
                it.remove();
            }
            bucket = new Bucket(burst, now);
            buckets.put(client, bucket);
        }
        bucket.tokens = refilled(bucket, now);
        bucket.updated = now;
        if (bucket.tokens < 1) return false;
        bucket.tokens -= 1;
        return true;
    }
    private double refilled(Bucket bucket, long now) {
        return Math.min(burst, bucket.tokens + Math.max(0, now - bucket.updated) / 1e9 * rate);
    }
    int retryAfterSeconds() { return rate == 0 ? 1 : (int) Math.min(Integer.MAX_VALUE, Math.max(1, Math.ceil(1 / rate))); }
}
