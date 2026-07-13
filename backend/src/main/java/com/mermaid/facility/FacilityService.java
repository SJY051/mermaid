package com.mermaid.facility;

import com.mermaid.common.GeoUtils;
import com.mermaid.common.SourceRef;
import com.mermaid.facility.domain.DutyTable;
import com.mermaid.facility.domain.Facility;
import com.mermaid.facility.domain.FacilityOperation;
import com.mermaid.facility.domain.FacilityType;
import com.mermaid.facility.domain.OpenInterval;
import com.mermaid.facility.domain.WeeklyHours;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
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
    private static final int METRES_PER_KM = 1000;

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

        PharmacyApiClient.PharmacyBatch batch = pharmacyApiClient.findNear(lat, lng);
        return batch.pharmacies().stream()
                .map(raw -> toFacility(raw, batch.origin(), lat, lng, now, holiday, retrievedAt))
                .filter(f -> f.distanceMeters() <= radiusMeters)
                // `open_now=true` returns only status=open. A pharmacy whose timetable we could not
                // read is excluded rather than guessed at, in either direction (spec §2-13).
                .filter(f -> !openNow || Boolean.TRUE.equals(f.operation().isOpenNow()))
                .sorted(Comparator.comparingDouble(Facility::distanceMeters))
                .toList();
    }

    private Facility toFacility(
            PharmacyApiClient.RawPharmacy raw,
            SourceRef.DataMode listOrigin,
            double originLat,
            double originLng,
            ZonedDateTime now,
            boolean holiday,
            Instant retrievedAt) {

        DutyTable weekly = pharmacyApiClient.weeklyHours(raw.hpid());

        // The card's provenance is the whole truth behind it: fixture if the directory OR the schedule
        // we actually used came from a fixture. An empty table is never consulted (§2-14).
        boolean scheduleUsedFixture =
                !weekly.byDay().isEmpty() && weekly.origin() == SourceRef.DataMode.FIXTURE;
        SourceRef.DataMode origin =
                (listOrigin == SourceRef.DataMode.FIXTURE || scheduleUsedFixture)
                        ? SourceRef.DataMode.FIXTURE
                        : SourceRef.DataMode.LIVE;

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
                distanceMetres(raw, originLat, originLng),
                operationOf(raw, weekly, now, holiday, retrievedAt),
                sourceOf(raw, retrievedAt, origin));
    }

    /**
     * The API's own {@code distance} is in kilometres. Trust it when present — it is the same figure
     * the service sorted by — and fall back to Haversine when a row omits it.
     */
    private double distanceMetres(
            PharmacyApiClient.RawPharmacy raw, double originLat, double originLng) {
        if (raw.distanceKm() != null) {
            return raw.distanceKm() * METRES_PER_KM;
        }
        return GeoUtils.haversineMeters(originLat, originLng, raw.latitude(), raw.longitude());
    }

    /**
     * Open-now, from the best evidence we have.
     *
     * <p>With a published weekly table this is {@code OFFICIAL_SCHEDULE} and handles wrap-past-midnight
     * night pharmacies; without one we infer from the single start/end pair the location endpoint
     * returns, and say so ({@code INFERRED}).
     */
    private FacilityOperation operationOf(
            PharmacyApiClient.RawPharmacy raw,
            DutyTable weekly,
            ZonedDateTime now,
            boolean holiday,
            Instant retrievedAt) {

        if (!weekly.byDay().isEmpty()) {
            WeeklyHours hours = WeeklyHours.fromDutyTimes(weekly.byDay());
            if (!hours.hasAnySchedule()) {
                return FacilityOperation.unknown(retrievedAt);
            }
            return hours.isOpenAt(now, holiday)
                    ? FacilityOperation.open(retrievedAt)
                    : FacilityOperation.closed(retrievedAt);
        }

        OpenInterval today = OpenInterval.of(raw.startTime(), raw.endTime());
        if (today.isClosed()) {
            return FacilityOperation.unknown(retrievedAt);
        }
        LocalTime at = now.toLocalTime();
        boolean open = today.containsStartingToday(at) || today.carriesInto(at);
        return FacilityOperation.inferred(open, retrievedAt);
    }

    private SourceRef sourceOf(
            PharmacyApiClient.RawPharmacy raw, Instant retrievedAt, SourceRef.DataMode origin) {
        return new SourceRef(
                "src:" + PHARMACY_PROVIDER + ":" + raw.hpid(),
                "국립중앙의료원 전국 약국 정보",
                raw.hpid(),
                retrievedAt,
                origin,
                "National Medical Center — pharmacy directory");
    }

    /**
     * TODO(BE-2, DEV-203): hospitals need two upstream calls, unlike pharmacies.
     *
     * <ol>
     *   <li>{@code hospInfoServicev2/getHospBasisList?xPos=&yPos=&radius=} — this one <i>does</i> take
     *       a radius, in metres. It gives you {@code ykiho} and coordinates, but no hours. {@code
     *       radius} is not optional: omit it and you get all 79,727 hospitals in the country.
     *   <li>{@code MadmDtlInfoService2.8/getDtlInfo2.8?ykiho=} — per hospital, for {@code trmtMonStart}…
     *       {@code trmtSatEnd}, {@code lunchWeek}, {@code noTrmtSun}, {@code emyNgtYn}. <b>The version
     *       suffix appears twice</b>: on the service and on the operation. {@code …2.8/getDtlInfo}
     *       answers 404 — which means that operation name is wrong, not that the service is absent.
     * </ol>
     *
     * <p>Both are approved and answer 200 (2026-07-10). Real responses are in {@code
     * fixtures/hospital_list.json} and {@code hospital_detail.json}; read {@code fixtures/README.md}
     * items 12-17 before writing the parser. In particular: this API reports {@code distance} in
     * <b>metres</b> while the pharmacy API reports <b>kilometres</b>, and {@code XPos} is longitude.
     *
     * <p>Run {@code ./bin/check-api-access.py} if a call starts failing; it separates 401 (unknown
     * key) from 403 (unapproved service) from 404 (wrong operation name).
     *
     * <p>The two are a pair: the detail service has no search operation, and only the list service
     * issues a {@code ykiho}. That second step is one call per hospital: a classic N+1. Cache it per
     * {@code ykiho} — a hospital's opening hours change about once a year. Namespace the id as {@code
     * facility:hira:<ykiho>}.
     *
     * <p>Hospitals with no timetable must keep {@code isOpenNow == null}. Sunday has no field at all:
     * {@code trmtSunStart} is absent and {@code noTrmtSun: "휴진"} is what you get instead.
     */
    private List<Facility> hospitals(double lat, double lng, int radiusMeters, boolean openNow) {
        // Returning an empty list here told the caller "there are no hospitals near you" when the
        // truth is "we cannot look". That is the same lie as rendering no_match_found as "safe": an
        // absence of data presented as a fact about the world. 501 says which one it is.
        throw new UnsupportedOperationException(
                "Hospital search is not implemented — see FacilityService#hospitals (DEV-203)");
    }
}
