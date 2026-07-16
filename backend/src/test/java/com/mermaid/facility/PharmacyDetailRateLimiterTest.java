package com.mermaid.facility;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The bounded-lookup boundary for pharmacy detail (issue #95, option A). */
class PharmacyDetailRateLimiterTest {

    @Test
    @DisplayName("allows up to capacity, then throttles once the bucket is empty")
    void allowsBurstThenThrottles() {
        // Frozen clock, so no refill happens between calls: the bucket behaves as a fixed capacity.
        var clock = new AtomicLong(0);
        var limiter = new PharmacyDetailRateLimiter(3, 1.0, clock::get);

        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
        assertThat(limiter.tryAcquire()).isFalse();
    }

    @Test
    @DisplayName("refills over time so a legitimate later lookup succeeds")
    void refillsOverTime() {
        var clock = new AtomicLong(0);
        var limiter = new PharmacyDetailRateLimiter(1, 1.0, clock::get); // 1 token, 1/s

        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse(); // empty, clock has not advanced

        clock.set(2_000_000_000L); // +2s → 2 tokens, capped at capacity 1

        assertThat(limiter.tryAcquire()).isTrue();
    }

    @Test
    @DisplayName("the production detail budget stays below the NMC daily quota")
    void productionBudgetStaysBelowDailyQuota() {
        var clock = new AtomicLong(0);
        var limiter =
                new PharmacyDetailRateLimiter(
                        PharmacyApiClient.DETAIL_LOOKUP_BURST,
                        PharmacyApiClient.DETAIL_LOOKUP_REFILL_PER_SECOND,
                        clock::get);
        int accepted = 0;

        for (int i = 0; i < PharmacyApiClient.DETAIL_LOOKUP_BURST; i++) {
            if (limiter.tryAcquire()) {
                accepted++;
            }
        }
        // At 50-second intervals, 0.01/s yields one token every two attempts: 864 further calls in
        // a day. Raising the sustained rate back to 0.5/s makes every attempt pass and turns this red.
        for (long seconds = 50; seconds <= 86_400; seconds += 50) {
            clock.set(seconds * 1_000_000_000L);
            if (limiter.tryAcquire()) {
                accepted++;
            }
        }

        assertThat(accepted).isEqualTo(894).isLessThan(1_000);
    }
}
