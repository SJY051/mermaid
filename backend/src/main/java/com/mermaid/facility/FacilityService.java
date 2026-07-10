package com.mermaid.facility;

import com.mermaid.common.GeoUtils;
import com.mermaid.facility.domain.Facility;
import com.mermaid.facility.domain.FacilityType;
import com.mermaid.facility.domain.WeeklyHours;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Answers "what is open near me, right now" (FR-02, TC-02).
 *
 * <p>Neither the radius filter nor the open-now filter exists upstream — no public API offers them
 * (spec §2-9). Both are implemented here, which makes this class the one place TC-02 can fail.
 */
@Service
@RequiredArgsConstructor
public class FacilityService {

    /** The public timetables are all local Korean time. Never use the server's default zone. */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final PharmacyApiClient pharmacyApiClient;
    private final HolidayCalendar holidayCalendar;
    private final Clock clock;

    public List<Facility> findNearby(
            double lat, double lng, int radiusMeters, boolean openNow, FacilityType type) {

        return switch (type) {
            case PHARMACY -> pharmacies(lat, lng, radiusMeters, openNow);
            case HOSPITAL -> hospitals(lat, lng, radiusMeters, openNow);
        };
    }

    private List<Facility> pharmacies(double lat, double lng, int radiusMeters, boolean openNow) {
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(KST);
        boolean holiday = holidayCalendar.isHoliday(now.toLocalDate());

        return pharmacyApiClient.findNear(lat, lng).stream()
                .map(
                        raw -> {
                            double distance =
                                    GeoUtils.haversineMeters(lat, lng, raw.latitude(), raw.longitude());
                            boolean open =
                                    WeeklyHours.fromDutyTimes(raw.dutyTimes()).isOpenAt(now, holiday);
                            return new Facility(
                                    raw.hpid(),
                                    FacilityType.PHARMACY,
                                    raw.name(),
                                    raw.address(),
                                    raw.phone(),
                                    raw.latitude(),
                                    raw.longitude(),
                                    distance,
                                    open);
                        })
                .filter(f -> f.distanceMeters() <= radiusMeters)
                .filter(f -> !openNow || f.openNow())
                .sorted(Comparator.comparingDouble(Facility::distanceMeters))
                .toList();
    }

    /**
     * TODO(team): hospitals need two upstream calls, unlike pharmacies.
     *
     * <ol>
     *   <li>{@code hospInfoServicev2/getHospBasisList?xPos=&yPos=&radius=} — this one <i>does</i>
     *       take a radius, in metres. It gives you {@code ykiho} and coordinates, but no hours.
     *   <li>{@code MadmDtlInfoService2.8/getDtlInfo?ykiho=} — per hospital, for {@code trmtMonStart}
     *       … {@code trmtSunEnd}, {@code lunchWeek}, {@code noTrmtSun}, {@code emyNgtYn}.
     * </ol>
     *
     * <p>That second step is one call per hospital: a classic N+1. Cache it per {@code ykiho} — a
     * hospital's opening hours change about once a year.
     *
     * <p>Also confirm the live version suffix (2.7 vs 2.8) on data.go.kr before writing the DTOs.
     */
    private List<Facility> hospitals(double lat, double lng, int radiusMeters, boolean openNow) {
        return List.of();
    }
}
