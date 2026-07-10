package com.mermaid.facility.domain;

import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.Map;

/**
 * A facility's opening hours for a whole week, plus public holidays.
 *
 * <p>No public API exposes an "open now" filter — not the pharmacy service, not HIRA (spec §2-9).
 * They give us weekly tables and we decide. This is where {@code open_now=true} is actually
 * implemented.
 *
 * <p>The pharmacy API numbers its {@code dutyTime{N}s} / {@code dutyTime{N}c} fields 1–8, where 1–7
 * are Monday–Sunday and 8 is 공휴일. That happens to match {@link DayOfWeek#getValue()} exactly, so
 * indices 1–7 map straight across.
 */
public final class WeeklyHours {

    private final Map<DayOfWeek, OpenInterval> byDay;
    private final OpenInterval holiday;

    private WeeklyHours(Map<DayOfWeek, OpenInterval> byDay, OpenInterval holiday) {
        this.byDay = byDay;
        this.holiday = holiday;
    }

    /**
     * @param dutyTimes index 1..8 → {open, close} as HHMM strings; 1=Mon … 7=Sun, 8=holiday
     */
    public static WeeklyHours fromDutyTimes(Map<Integer, String[]> dutyTimes) {
        Map<DayOfWeek, OpenInterval> map = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek d : DayOfWeek.values()) {
            String[] pair = dutyTimes.get(d.getValue());
            map.put(d, pair == null ? OpenInterval.CLOSED : OpenInterval.of(pair[0], pair[1]));
        }
        String[] holidayPair = dutyTimes.get(8);
        OpenInterval holidayInterval =
                holidayPair == null ? OpenInterval.CLOSED : OpenInterval.of(holidayPair[0], holidayPair[1]);
        return new WeeklyHours(map, holidayInterval);
    }

    /**
     * Is the facility open at this instant?
     *
     * <p>Two ways to be open at 01:00 on a Tuesday: Tuesday's own row starts before 01:00, or
     * <i>Monday's</i> row wrapped past midnight (a 21:00–02:00 night pharmacy). Checking only the
     * current day's row silently loses every night pharmacy in the small hours — which is exactly
     * the case this app exists for.
     *
     * <p>Known limitation: on a holiday we consult only the holiday row, so a night pharmacy whose
     * shift began the evening before is missed. Fixing that needs yesterday's holiday status too.
     *
     * @param at the moment to test — pass a KST time; the public data is all local
     * @param isHoliday whether {@code at} falls on a Korean public holiday
     */
    public boolean isOpenAt(ZonedDateTime at, boolean isHoliday) {
        java.time.LocalTime now = at.toLocalTime();

        if (isHoliday) {
            return holiday.containsStartingToday(now);
        }

        boolean openedToday = byDay.get(at.getDayOfWeek()).containsStartingToday(now);
        boolean carriedFromYesterday = byDay.get(at.getDayOfWeek().minus(1)).carriesInto(now);
        return openedToday || carriedFromYesterday;
    }

    /**
     * Did the provider publish any hours at all?
     *
     * <p>When every row is closed, we do not know the place is shut — we know the data is
     * missing. The caller must report {@code unknown}, not {@code closed} (spec §2-13).
     */
    public boolean hasAnySchedule() {
        return byDay.values().stream().anyMatch(i -> !i.isClosed()) || !holiday.isClosed();
    }

    public OpenInterval on(DayOfWeek day) {
        return byDay.get(day);
    }

    public OpenInterval onHoliday() {
        return holiday;
    }
}
