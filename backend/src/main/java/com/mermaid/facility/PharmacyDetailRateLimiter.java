package com.mermaid.facility;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Bounds how fast distinct pharmacy-detail HPIDs may reach NMC (issue #95, option A).
 *
 * <p><b>Why this exists.</b> {@code GET /facilities/{id}} validates the HPID shape and caches negative
 * results, so a repeated or malformed id costs nothing. What remains is enumeration: an anonymous
 * script walking well-formed ids ({@code C0000001}, {@code C0000002}, …) would still spend one of
 * NMC's <b>1,000 calls/day</b> per distinct id and could drain the quota that real users share. A
 * per-id cache cannot stop that because every id is different.
 *
 * <p><b>What it does.</b> A token bucket caps the <i>sustained rate</i> of upstream detail lookups.
 * Each live NMC lookup spends one token; tokens refill continuously at {@code refillPerSecond} up to
 * {@code capacity}. When the bucket is empty {@link #tryAcquire()} returns {@code false} and the caller
 * fails the request as {@code SOURCE_UNAVAILABLE} (retryable) instead of calling NMC. Cache hits,
 * fixture mode, and the no-key path never reach the limiter, so ordinary saved-place refreshes — a
 * handful per user — are never throttled while a burst of thousands is.
 *
 * <p><b>Scope and tuning.</b> The bucket is per application instance (in memory); a multi-instance
 * deployment multiplies the effective rate, which is acceptable for this MVP — tighten it with a
 * shared store only if enumeration proves to be a real problem. The default capacity/refill are a
 * conservative starting point; raise them if legitimate traffic is ever throttled.
 *
 * <p>Thread-safe via a lock-free compare-and-set on an immutable snapshot; {@link System#nanoTime()}
 * is monotonic, so a wall-clock adjustment cannot skew the refill.
 */
final class PharmacyDetailRateLimiter {

    private final double capacity;
    private final double refillPerSecond;
    private final LongSupplier nanoTime;
    private final AtomicReference<Snapshot> state;

    PharmacyDetailRateLimiter(int capacity, double refillPerSecond) {
        this(capacity, refillPerSecond, System::nanoTime);
    }

    /** Time source injectable so tests advance the clock deterministically instead of sleeping. */
    PharmacyDetailRateLimiter(int capacity, double refillPerSecond, LongSupplier nanoTime) {
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.nanoTime = nanoTime;
        this.state = new AtomicReference<>(new Snapshot(capacity, nanoTime.getAsLong()));
    }

    /** @return true if a token was available and spent; false when the bucket is empty (throttle). */
    boolean tryAcquire() {
        while (true) {
            Snapshot current = state.get();
            long now = nanoTime.getAsLong();
            double refilled =
                    Math.min(
                            capacity,
                            current.tokens + (now - current.nanoTime) / 1_000_000_000.0 * refillPerSecond);
            if (refilled < 1.0) {
                return false;
            }
            if (state.compareAndSet(current, new Snapshot(refilled - 1.0, now))) {
                return true;
            }
        }
    }

    private record Snapshot(double tokens, long nanoTime) {}
}
