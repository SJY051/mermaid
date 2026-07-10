package com.mermaid.facility;

import com.mermaid.common.GeoUtils;
import com.mermaid.common.SourceRef;
import com.mermaid.facility.domain.Facility;
import com.mermaid.facility.domain.FacilityOperation;
import com.mermaid.facility.domain.FacilityType;
import com.mermaid.facility.domain.WeeklyHours;
import java.time.Clock;
import java.time.Instant;
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

    private static final String PHARMACY_PROVIDER = "nmc"; // 국립중앙의료원

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
        Instant retrievedAt = now.toInstant();

        return pharmacyApiClient.findNear(lat, lng).stream()
                .map(raw -> toFacility(raw, lat, lng, now, holiday, retrievedAt))
                .filter(f -> f.distanceMeters() <= radiusMeters)
                // `open_now=true` returns only status=open. An unknown timetable is not a
                // promise either way, so it is excluded here rather than guessed (spec §2-13).
                .filter(f -> !openNow || Boolean.TRUE.equals(f.operation().isOpenNow()))
                .sorted(Comparator.comparingDouble(Facility::distanceMeters))
                .toList();
    }

    private Facility toFacility(
            PharmacyApiClient.RawPharmacy raw,
            double originLat,
            double originLng,
            ZonedDateTime now,
            boolean holiday,
            Instant retrievedAt) {

        WeeklyHours hours = WeeklyHours.fromDutyTimes(raw.dutyTimes());

        FacilityOperation operation;
        if (!hours.hasAnySchedule()) {
            operation = FacilityOperation.unknown(retrievedAt);
        } else if (hours.isOpenAt(now, holiday)) {
            operation = FacilityOperation.open(retrievedAt);
        } else {
            operation = FacilityOperation.closed(retrievedAt);
        }

        return new Facility(
                Facility.idOf(PHARMACY_PROVIDER, raw.hpid()),
                FacilityType.PHARMACY,
                raw.name(),
                null, // the pharmacy API publishes no English name
                raw.address(),
                null,
                raw.phone(),
                raw.latitude(),
                raw.longitude(),
                GeoUtils.haversineMeters(originLat, originLng, raw.latitude(), raw.longitude()),
                operation,
                new SourceRef(
                        "src:" + PHARMACY_PROVIDER + ":" + raw.hpid(),
                        "국립중앙의료원 전국 약국 정보",
                        raw.hpid(),
                        retrievedAt,
                        SourceRef.DataMode.LIVE,
                        "National Medical Center — pharmacy directory"));
    }

    /**
     * TODO(team): hospitals need two upstream calls, unlike pharmacies. See DEV-203.
     *
     * <ol>
     *   <li>{@code hospInfoServicev2/getHospBasisList?xPos=&yPos=&radius=} — this one <i>does</i>
     *       take a radius, in metres. It gives you {@code ykiho} and coordinates, but no hours.
     *   <li>{@code MadmDtlInfoService2.8/getDtlInfo?ykiho=} — per hospital, for {@code trmtMonStart}
     *       … {@code trmtSunEnd}, {@code lunchWeek}, {@code noTrmtSun}, {@code emyNgtYn}.
     * </ol>
     *
     * <p>That second step is one call per hospital: a classic N+1. Cache it per {@code ykiho} — a
     * hospital's opening hours change about once a year. Namespace the id as {@code
     * facility:hira:<ykiho>}.
     */
    private List<Facility> hospitals(double lat, double lng, int radiusMeters, boolean openNow) {
        return List.of();
    }
}
