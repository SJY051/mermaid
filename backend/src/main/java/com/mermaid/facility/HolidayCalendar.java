package com.mermaid.facility;

import java.time.LocalDate;
import java.util.function.Predicate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Is a given date a Korean public holiday?
 *
 * <p>Matters because both public APIs keep a separate holiday row (the pharmacy API's {@code
 * dutyTime8s}/{@code dutyTime8c}; HIRA's {@code noTrmtHoli}). Getting this wrong means telling
 * someone a pharmacy is open on 설날.
 *
 * <p>A calendar fetch failure propagates rather than using an older in-memory value. A temporary
 * holiday can be declared after the cached value was fetched; using stale data would turn that
 * uncertainty into an incorrect opening decision.
 */
@Component
public class HolidayCalendar {

    /** Non-null in production; null for the test predicate seam. */
    private final HolidayApiClient holidays;

    /** Non-null only for tests that inject a deterministic calendar. */
    private final Predicate<LocalDate> override;

    @Autowired
    public HolidayCalendar(HolidayApiClient holidays) {
        this.holidays = holidays;
        this.override = null;
    }

    /** Package-visible seam for facility tests that need a known calendar date. */
    HolidayCalendar(Predicate<LocalDate> override) {
        this.holidays = null;
        this.override = override;
    }

    public boolean isHoliday(LocalDate date) {
        if (override != null) {
            return override.test(date);
        }

        return holidays.holidaysFor(date.getYear()).isHoliday(date);
    }
}
