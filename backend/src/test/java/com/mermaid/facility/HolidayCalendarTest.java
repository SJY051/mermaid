package com.mermaid.facility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mermaid.common.PublicApiException;
import com.mermaid.config.DataModeProperties;
import com.mermaid.config.PublicApiProperties;
import com.mermaid.facility.HolidayApiClient.HolidayYear;
import java.time.LocalDate;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Calendar failures stay visible rather than becoming guessed opening decisions (spec 010 FR-005). */
class HolidayCalendarTest {

    private static final PublicApiProperties CONFIGURED =
            new PublicApiProperties(
                    "decoding-key",
                    "https://x",
                    "https://x",
                    "https://holiday.example",
                    "https://x",
                    "https://x",
                    "https://x",
                    "https://x",
                    "https://x");

    private static final LocalDate CHILDRENS_DAY = LocalDate.of(2026, 5, 5);

    private static HolidayApiClient client(java.util.function.IntFunction<HolidayYear> answer) {
        return new HolidayApiClient(
                null, CONFIGURED, new DataModeProperties(DataModeProperties.DataMode.HYBRID)) {
            @Override
            public HolidayYear holidaysFor(int year) {
                return answer.apply(year);
            }
        };
    }

    @Test
    @DisplayName("a refetch failure remains visible even after a previous successful calendar load")
    void doesNotServeAnUnboundedStaleCalendar() {
        var calls = new AtomicInteger();
        var flaky =
                client(
                        year -> {
                            if (calls.getAndIncrement() == 0) {
                                return new HolidayYear(Set.of(CHILDRENS_DAY));
                            }
                            throw new PublicApiException("holiday API unavailable");
                        });
        var calendar = new HolidayCalendar(flaky);

        // The first lookup proves a calendar value existed. A later failure must still surface rather
        // than treating an indefinitely old value as current; reintroducing a stale fallback makes
        // the second assertion red.
        assertThat(calendar.isHoliday(CHILDRENS_DAY)).isTrue();
        assertThatThrownBy(() -> calendar.isHoliday(CHILDRENS_DAY))
                .isInstanceOf(PublicApiException.class);
    }

    @Test
    @DisplayName("a year never loaded still fails loudly rather than guessing not-a-holiday")
    void failsWhenNeverLoadedSuccessfully() {
        var down =
                client(
                        year -> {
                            throw new PublicApiException("holiday API unavailable");
                        });

        assertThatThrownBy(() -> new HolidayCalendar(down).isHoliday(CHILDRENS_DAY))
                .isInstanceOf(PublicApiException.class);
    }
}
