package com.mermaid.facility;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;

/** Verifies the year cache through Spring's {@code @Cacheable} proxy. */
@SpringBootTest
@ActiveProfiles("test")
class HolidayApiClientCacheTest {

    @Autowired HolidayApiClient holidays;
    @Autowired CacheManager cacheManager;

    @BeforeEach
    void clearCache() {
        cacheManager.getCache("holidaysByYear.v1").clear();
    }

    @Test
    void repeatedDatesInOneYearShareOneCalendarValue() {
        var first = holidays.holidaysFor(2026);
        var second = holidays.holidaysFor(2026);

        assertThat(second).isSameAs(first);
        assertThat(second.isHoliday(java.time.LocalDate.of(2026, 5, 5))).isTrue();
    }

    @Test
    void serializesConcurrentColdLoadsForTheSameYear() throws NoSuchMethodException {
        Cacheable cacheable =
                HolidayApiClient.class
                        .getMethod("holidaysFor", int.class)
                        .getAnnotation(Cacheable.class);

        assertThat(cacheable.sync()).isTrue();
    }
}
