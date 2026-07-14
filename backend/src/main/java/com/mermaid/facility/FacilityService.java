package com.mermaid.facility;

import com.mermaid.common.GeoUtils;
import com.mermaid.common.Parallel;
import com.mermaid.common.SourceRef;
import com.mermaid.facility.domain.DutyTable;
import com.mermaid.facility.domain.Facility;
import com.mermaid.facility.domain.FacilityOperation;
import com.mermaid.facility.domain.FacilityType;
import com.mermaid.facility.domain.OpenInterval;
import com.mermaid.facility.domain.WeeklyHours;
import java.time.Clock;
import java.time.DayOfWeek;
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
    private static final String HOSPITAL_PROVIDER = "hira"; // 건강보험심사평가원
    private static final int METRES_PER_KM = 1000;
    private static final int PHARMACY_WEEKLY_HOURS_CONCURRENCY = 4;
    // The pharmacy location endpoint supplies at most 100 rows. Checking all of those only for an
    // open-now request widens the useful search without adding pagination or an unbounded fan-out.
    private static final int MAX_OPEN_NOW_PHARMACY_CANDIDATES = 100;
    private static final int HOSPITAL_DETAIL_CONCURRENCY = 4;
    /** HIRA 종별코드 for 요양병원 (long-term-care hospitals) — the stable code, not the label. */
    private static final String NURSING_HOSPITAL_CODE = "28";
    /** The public API's maximum result count, keeping one map load within a bounded detail fan-out. */
    public static final int MAX_FACILITY_RESULTS = 50;

    private final PharmacyApiClient pharmacyApiClient;
    private final HospitalApiClient hospitalApiClient;
    private final HospitalDetailApiClient hospitalDetailApiClient;
    private final HolidayCalendar holidayCalendar;
    private final Clock clock;

    public List<Facility> findNearby(
            double lat, double lng, int radiusMeters, boolean openNow, FacilityType type) {
        return findNearby(lat, lng, radiusMeters, openNow, type, MAX_FACILITY_RESULTS);
    }

    /**
     * Returns the nearest requested number of facilities. Hospital detail calls are bounded by the
     * same public result limit because a dense HIRA radius otherwise creates one upstream call per row.
     */
    public List<Facility> findNearby(
            double lat, double lng, int radiusMeters, boolean openNow, FacilityType type, int limit) {
        return switch (type) {
            case PHARMACY -> pharmacies(lat, lng, radiusMeters, openNow, limit);
            case HOSPITAL -> hospitals(lat, lng, radiusMeters, openNow, limit);
        };
    }

    private List<Facility> pharmacies(
            double lat, double lng, int radiusMeters, boolean openNow, int limit) {
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(KST);
        boolean holiday = holidayCalendar.isHoliday(now.toLocalDate());
        Instant retrievedAt = now.toInstant();

        PharmacyApiClient.PharmacyBatch batch = pharmacyApiClient.findNear(lat, lng);
        int candidateLimit =
                openNow ? MAX_OPEN_NOW_PHARMACY_CANDIDATES : limit;
        List<PharmacyApiClient.RawPharmacy> candidates =
                batch.pharmacies().stream()
                // The location API has no radius parameter and can return 100 rows. Filter before
                // toFacility() so an out-of-radius pharmacy never spends an HPID detail call.
                .filter(raw -> distanceMetres(raw, lat, lng) <= radiusMeters)
                .sorted(Comparator.comparingDouble(raw -> distanceMetres(raw, lat, lng)))
                .limit(candidateLimit)
                .toList();

        return Parallel.map(
                        candidates,
                        PHARMACY_WEEKLY_HOURS_CONCURRENCY,
                        raw -> toFacility(raw, batch.origin(), lat, lng, now, holiday, retrievedAt))
                .stream()
                // `open_now=true` returns only status=open. A pharmacy whose timetable we could not
                // read is excluded rather than guessed at, in either direction (spec §2-13).
                .filter(f -> !openNow || Boolean.TRUE.equals(f.operation().isOpenNow()))
                .sorted(Comparator.comparingDouble(Facility::distanceMeters))
                .limit(limit)
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
                sourceOf(raw, retrievedAt, origin),
                null, // the pharmacy API has no emergency-room flags
                null);
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

    private List<Facility> hospitals(
            double lat, double lng, int radiusMeters, boolean openNow, int limit) {
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(KST);
        boolean holiday = holidayCalendar.isHoliday(now.toLocalDate());
        HospitalApiClient.HospitalBatch batch = hospitalApiClient.findNear(lat, lng, radiusMeters);

        // HIRA's radius is authoritative, but its distance is a decimal string. Resolve each metre
        // value once — here, so a malformed/out-of-contract row is dropped before the detail fan-out
        // and the same figure is reused on the card instead of recomputed (Haversine twice).
        //
        // HIRA classifies 요양병원 (long-term-care hospitals) under 종별코드 28. The default search is
        // for acute care, so omit only that exact code before any detail call, matching the stable code
        // rather than the display label; unknown/other codes remain visible rather than guessed out.
        //
        // Sort by distance and keep the API's requested nearest N *before* the fan-out: distance comes
        // from the list, so we spend a detail call only on facilities that can appear in this response.
        List<NearbyHospital> nearest =
                batch.hospitals().stream()
                        .map(h -> new NearbyHospital(h, hospitalDistanceMetres(h, lat, lng)))
                        .filter(h -> h.distanceMeters() <= radiusMeters)
                        .filter(h -> !NURSING_HOSPITAL_CODE.equals(h.raw().classificationCode()))
                        .sorted(Comparator.comparingDouble(NearbyHospital::distanceMeters))
                        .limit(limit)
                        .toList();

        return Parallel.map(
                        nearest,
                        HOSPITAL_DETAIL_CONCURRENCY,
                        h ->
                                toHospital(
                                        h.raw(),
                                        h.distanceMeters(),
                                        batch.origin(),
                                        batch.retrievedAt(),
                                        now,
                                        holiday))
                .stream()
                .filter(f -> !openNow || Boolean.TRUE.equals(f.operation().isOpenNow()))
                .sorted(Comparator.comparingDouble(Facility::distanceMeters))
                .toList();
    }

    /** A list row paired with its distance, resolved once before the detail fan-out. */
    private record NearbyHospital(HospitalApiClient.RawHospital raw, double distanceMeters) {}

    private Facility toHospital(
            HospitalApiClient.RawHospital raw,
            double distanceMeters,
            SourceRef.DataMode listOrigin,
            Instant listRetrievedAt,
            ZonedDateTime now,
            boolean holiday) {
        HospitalDetailApiClient.HospitalDetailBatch detailBatch =
                hospitalDetailApiClient.findByYkiho(raw.ykiho());
        SourceRef.DataMode origin =
                listOrigin == SourceRef.DataMode.LIVE && detailBatch.origin() == SourceRef.DataMode.LIVE
                        ? SourceRef.DataMode.LIVE
                        : SourceRef.DataMode.FIXTURE;
        HospitalDetailApiClient.HospitalDetail detail = detailBatch.detail();
        return new Facility(
                Facility.idOf(HOSPITAL_PROVIDER, Facility.urlSafeSegment(raw.ykiho())),
                FacilityType.HOSPITAL,
                raw.nameKo(),
                null,
                raw.addressKo(),
                null,
                raw.phone(),
                raw.latitude(),
                raw.longitude(),
                distanceMeters,
                hospitalOperation(detail, now, holiday, detailBatch.retrievedAt()),
                new SourceRef(
                        "src:" + HOSPITAL_PROVIDER + ":" + raw.ykiho(),
                        "건강보험심사평가원 병원정보서비스",
                        raw.ykiho(),
                        listRetrievedAt,
                        origin,
                        "Health Insurance Review & Assessment Service — hospital directory"),
                detail.emergencyDay(),
                detail.emergencyNight());
    }

    /** HIRA reports metres (unlike the pharmacy API's kilometres); only malformed omissions use Haversine. */
    private double hospitalDistanceMetres(
            HospitalApiClient.RawHospital raw, double originLat, double originLng) {
        return raw.distanceMeters() != null
                ? raw.distanceMeters()
                : GeoUtils.haversineMeters(originLat, originLng, raw.latitude(), raw.longitude());
    }

    private FacilityOperation hospitalOperation(
            HospitalDetailApiClient.HospitalDetail detail,
            ZonedDateTime now,
            boolean holiday,
            Instant retrievedAt) {
        if (holiday && detail.holidayClosed()) {
            return FacilityOperation.closed(retrievedAt);
        }
        if (now.getDayOfWeek() == DayOfWeek.SUNDAY && detail.sundayClosed()) {
            return FacilityOperation.closed(retrievedAt);
        }
        if (detail.weekdayHours().isEmpty()) {
            return FacilityOperation.unknown(retrievedAt);
        }
        if (holiday) {
            return FacilityOperation.unknown(retrievedAt);
        }
        if (now.getDayOfWeek() == DayOfWeek.SUNDAY) {
            if (!detail.weekdayHours().containsKey(DayOfWeek.SUNDAY.getValue())) {
                return FacilityOperation.unknown(retrievedAt);
            }
        }

        WeeklyHours hours = WeeklyHours.fromDutyTimes(detail.weekdayHours());
        if (!hours.hasAnySchedule()) {
            return FacilityOperation.unknown(retrievedAt);
        }
        LocalTime at = now.toLocalTime();
        // HIRA labels this weekday-only field lunchWeek. Its separate lunchSat value is not part of
        // DEV-203, so Saturday remains based on published treatment hours rather than guessed closed.
        if (now.getDayOfWeek().getValue() <= DayOfWeek.FRIDAY.getValue()
                && detail.lunchBreak()
                .filter(lunch -> !at.isBefore(lunch.start()) && at.isBefore(lunch.end()))
                .isPresent()) {
            return FacilityOperation.closed(retrievedAt);
        }
        return hours.isOpenAt(now, false)
                ? FacilityOperation.open(retrievedAt)
                : FacilityOperation.closed(retrievedAt);
    }
}
