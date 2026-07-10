package com.mermaid.facility.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The whole point of this app is telling someone at 1 a.m. which pharmacy is open. These tests exist
 * because the obvious implementation gets exactly that case wrong.
 */
class WeeklyHoursTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 2026-07-06 is a Monday. */
    private static ZonedDateTime at(int dayOfMonth, int hour, int minute) {
        return ZonedDateTime.of(LocalDateTime.of(2026, 7, dayOfMonth, hour, minute), KST);
    }

    /** Mon 21:00–02:00 (wraps), Tue 09:00–18:00, everything else closed. */
    private static WeeklyHours nightPharmacy() {
        return WeeklyHours.fromDutyTimes(
                Map.of(
                        1, new String[] {"2100", "0200"},
                        2, new String[] {"0900", "1800"}));
    }

    @Nested
    @DisplayName("a shift that wraps past midnight")
    class WrappingShift {

        @Test
        @DisplayName("is open late on the evening it starts")
        void openMondayNight() {
            assertThat(nightPharmacy().isOpenAt(at(6, 22, 30), false)).isTrue();
        }

        @Test
        @DisplayName("is still open after midnight, on the NEXT day's calendar")
        void openTuesdayEarlyMorning() {
            // 01:00 Tuesday. Tuesday's own row says 09:00–18:00 — it does not cover 01:00.
            // Only Monday's wrapping row does. Checking today's row alone loses this pharmacy,
            // which is the single case the whole service exists for.
            assertThat(nightPharmacy().isOpenAt(at(7, 1, 0), false)).isTrue();
        }

        @Test
        @DisplayName("is closed once the wrapped shift ends")
        void closedAfterWrapEnds() {
            assertThat(nightPharmacy().isOpenAt(at(7, 2, 0), false)).isFalse();
            assertThat(nightPharmacy().isOpenAt(at(7, 8, 59), false)).isFalse();
        }

        @Test
        @DisplayName("does not leak into a morning whose previous day was closed")
        void closedMondayEarlyMorning() {
            // 01:00 Monday. Sunday is closed, so nothing carries in.
            assertThat(nightPharmacy().isOpenAt(at(6, 1, 0), false)).isFalse();
        }
    }

    @Nested
    @DisplayName("an ordinary daytime shift")
    class NormalShift {

        @Test
        void openDuring() {
            assertThat(nightPharmacy().isOpenAt(at(7, 10, 0), false)).isTrue();
        }

        @Test
        @DisplayName("closes exclusively — 18:00 is shut")
        void closedAtClosingTime() {
            assertThat(nightPharmacy().isOpenAt(at(7, 18, 0), false)).isFalse();
        }

        @Test
        @DisplayName("opens inclusively — 09:00 is open")
        void openAtOpeningTime() {
            assertThat(nightPharmacy().isOpenAt(at(7, 9, 0), false)).isTrue();
        }
    }

    @Nested
    @DisplayName("holidays use index 8, not the weekday row")
    class Holidays {

        @Test
        void closedOnHolidayWhenNoHolidayRow() {
            // Tuesday 10:00 would be open, but on a holiday we read row 8, which is absent.
            assertThat(nightPharmacy().isOpenAt(at(7, 10, 0), true)).isFalse();
        }

        @Test
        void openOnHolidayWhenHolidayRowSaysSo() {
            WeeklyHours hours =
                    WeeklyHours.fromDutyTimes(Map.of(8, new String[] {"1000", "1400"}));
            assertThat(hours.isOpenAt(at(7, 11, 0), true)).isTrue();
            assertThat(hours.isOpenAt(at(7, 15, 0), true)).isFalse();
        }
    }

    @Nested
    @DisplayName("HHMM parsing tolerates both APIs' spellings")
    class Parsing {

        @Test
        @DisplayName("pharmacy sends zero-padded strings, HIRA drops the leading zero")
        void parsesBothForms() {
            assertThat(OpenInterval.parseHhmm("0930")).isEqualTo(java.time.LocalTime.of(9, 30));
            assertThat(OpenInterval.parseHhmm("930")).isEqualTo(java.time.LocalTime.of(9, 30));
        }

        @Test
        @DisplayName("blank, null and zero all mean closed")
        void closedForms() {
            assertThat(OpenInterval.parseHhmm(null)).isNull();
            assertThat(OpenInterval.parseHhmm("")).isNull();
            assertThat(OpenInterval.parseHhmm("0")).isNull();
            assertThat(OpenInterval.parseHhmm("0000")).isNull();
        }

        @Test
        @DisplayName("garbage does not throw — it means closed")
        void garbageIsClosed() {
            assertThat(OpenInterval.parseHhmm("2570")).isNull();
            assertThat(OpenInterval.parseHhmm("휴무")).isNull();
            assertThat(OpenInterval.of("휴무", "휴무").isClosed()).isTrue();
        }
    }
}
