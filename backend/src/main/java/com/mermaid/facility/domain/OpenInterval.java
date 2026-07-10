package com.mermaid.facility.domain;

import java.time.LocalTime;

/**
 * One day's opening window.
 *
 * <p>Handles the case that trips people up: a night pharmacy open {@code 2100–0200} has an end time
 * numerically <i>before</i> its start. That is not bad data — it wraps past midnight.
 *
 * @param open inclusive
 * @param close exclusive
 */
public record OpenInterval(LocalTime open, LocalTime close) {

    /** A day the facility is closed. */
    public static final OpenInterval CLOSED = new OpenInterval(null, null);

    public boolean isClosed() {
        return open == null || close == null;
    }

    public boolean wrapsMidnight() {
        return !isClosed() && close.isBefore(open);
    }

    /**
     * Is {@code t} inside the stretch this interval opens <i>on its own day</i>?
     *
     * <p>For a wrapping 21:00–02:00 window this is only the 21:00–24:00 half. The 00:00–02:00 half
     * belongs to the next calendar day and is reported by {@link #carriesInto(LocalTime)}.
     */
    public boolean containsStartingToday(LocalTime t) {
        if (isClosed()) {
            return false;
        }
        if (wrapsMidnight()) {
            return !t.isBefore(open);
        }
        return !t.isBefore(open) && t.isBefore(close);
    }

    /**
     * Does this interval spill past midnight and still cover {@code t} on the following day?
     *
     * <p>Ask yesterday's interval this question when testing an early-morning time.
     */
    public boolean carriesInto(LocalTime t) {
        return wrapsMidnight() && t.isBefore(close);
    }

    /**
     * Parses the HHMM forms both public APIs use.
     *
     * <p>The pharmacy API returns zero-padded strings ({@code "0900"}). The HIRA hospital detail API
     * returns integers, so 09:30 arrives as {@code "930"} with the leading zero gone. Blank, null,
     * and {@code "0"} all mean closed.
     */
    public static LocalTime parseHhmm(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String digits = raw.trim();
        if (!digits.matches("\\d{1,4}")) {
            return null;
        }
        int value = Integer.parseInt(digits);
        if (value == 0) {
            return null; // "0" / "0000" is how both APIs spell "closed"
        }
        int hour = value / 100;
        int minute = value % 100;
        // 2400 shows up meaning "until midnight".
        if (hour == 24 && minute == 0) {
            return LocalTime.MAX;
        }
        if (hour > 23 || minute > 59) {
            return null;
        }
        return LocalTime.of(hour, minute);
    }

    public static OpenInterval of(String openHhmm, String closeHhmm) {
        LocalTime o = parseHhmm(openHhmm);
        LocalTime c = parseHhmm(closeHhmm);
        return (o == null || c == null) ? CLOSED : new OpenInterval(o, c);
    }
}
