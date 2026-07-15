package com.mermaid.facility;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

/** Cache regressions that only exist when Spring's {@code @Cacheable} proxy is active. */
@SpringBootTest
@ActiveProfiles("test")
@Import(HospitalCacheTest.MutableClockConfig.class)
class HospitalCacheTest {

    @Autowired HospitalApiClient hospitals;
    @Autowired HospitalDetailApiClient details;
    @Autowired CacheManager cacheManager;
    @Autowired MutableClock clock;

    @BeforeEach
    void clearCaches() {
        cacheManager.getCache("hospitalsNear.v1").clear();
        cacheManager.getCache("hospitalDetails").clear();
        clock.set(Instant.parse("2026-07-14T00:00:00Z"));
    }

    @Test
    void cacheHitsKeepTheActualHiraFetchTimestamp() {
        var firstList = hospitals.findNear(37.5663, 126.9779, 1000);
        var firstDetail = details.findByYkiho("cache-test-ykiho");

        clock.advance(Duration.ofHours(5));

        var cachedList = hospitals.findNear(37.5663, 126.9779, 1000);
        var cachedDetail = details.findByYkiho("cache-test-ykiho");

        assertThat(cachedList.retrievedAt()).isEqualTo(firstList.retrievedAt());
        assertThat(cachedDetail.retrievedAt()).isEqualTo(firstDetail.retrievedAt());
        assertThat(cachedList.retrievedAt()).isNotEqualTo(Instant.now(clock));
        assertThat(cachedDetail.retrievedAt()).isNotEqualTo(Instant.now(clock));
    }

    @Test
    void distinctCoordinatesDoNotShareA100MetreGridCacheEntry() {
        var south = hospitals.findNear(37.565501, 126.9779, 100);
        var north = hospitals.findNear(37.566499, 126.9779, 100);

        assertThat(north).isNotSameAs(south);
    }

    @TestConfiguration
    static class MutableClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-07-14T00:00:00Z"));
        }
    }

    static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
