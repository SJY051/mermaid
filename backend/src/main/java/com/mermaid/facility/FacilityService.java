package com.mermaid.facility;

import com.mermaid.common.GeoUtils;
import com.mermaid.common.Parallel;
import com.mermaid.common.SourceRef;
import com.mermaid.config.DataModeProperties;
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
import java.util.Map;
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
    private static final int HOSPITAL_DETAIL_CONCURRENCY = 4;

    private final PharmacyApiClient pharmacyApiClient;
    private final HospitalApiClient hospitalApiClient;
    private final HospitalDetailApiClient hospitalDetailApiClient;
    private final HolidayCalendar holidayCalendar;
    private final DataModeProperties dataMode;
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
                // `open_now=true` returns only status=open. A pharmacy whose timetable we could not
                // read is excluded rather than guessed at, in either direction (spec §2-13).
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
                operationOf(raw, now, holiday, retrievedAt),
                sourceOf(raw, retrievedAt));
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
     * <p>Once {@link PharmacyApiClient#weeklyHours} is implemented (DEV-202) this becomes {@code
     * OFFICIAL_SCHEDULE} and handles wrap-past-midnight night pharmacies. Until then we infer from
     * the single start/end pair the location endpoint returns, and say so.
     */
    private FacilityOperation operationOf(
            PharmacyApiClient.RawPharmacy raw, ZonedDateTime now, boolean holiday, Instant retrievedAt) {

        Map<Integer, String[]> weekly = pharmacyApiClient.weeklyHours(raw.hpid());
        if (!weekly.isEmpty()) {
            WeeklyHours hours = WeeklyHours.fromDutyTimes(weekly);
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

    private SourceRef sourceOf(PharmacyApiClient.RawPharmacy raw, Instant retrievedAt) {
        return new SourceRef(
                "src:" + PHARMACY_PROVIDER + ":" + raw.hpid(),
                "국립중앙의료원 전국 약국 정보",
                raw.hpid(),
                retrievedAt,
                dataMode.isFixtureOnly() ? SourceRef.DataMode.FIXTURE : SourceRef.DataMode.LIVE,
                "National Medical Center — pharmacy directory");
    }

    private List<Facility> hospitals(double lat, double lng, int radiusMeters, boolean openNow) {
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(KST);
        boolean holiday = holidayCalendar.isHoliday(now.toLocalDate());
        Instant retrievedAt = now.toInstant();
        HospitalApiClient.HospitalBatch batch = hospitalApiClient.findNear(lat, lng, radiusMeters);

        // HIRA's radius is authoritative, but its distance is a decimal string. Resolve each metre
        // value once — here, so a malformed/out-of-contract row is dropped before the N+1 detail
        // fan-out and the same figure is reused on the card instead of recomputed (Haversine twice).
        List<NearbyHospital> inRadius =
                batch.hospitals().stream()
                        .map(h -> new NearbyHospital(h, hospitalDistanceMetres(h, lat, lng)))
                        .filter(h -> h.distanceMeters() <= radiusMeters)
                        .toList();

        return Parallel.map(
                        inRadius,
                        HOSPITAL_DETAIL_CONCURRENCY,
                        h -> toHospital(h.raw(), h.distanceMeters(), batch.origin(), now, holiday, retrievedAt))
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
            ZonedDateTime now,
            boolean holiday,
            Instant retrievedAt) {
        HospitalDetailApiClient.HospitalDetailBatch detailBatch =
                hospitalDetailApiClient.findByYkiho(raw.ykiho());
        SourceRef.DataMode origin =
                listOrigin == SourceRef.DataMode.LIVE && detailBatch.origin() == SourceRef.DataMode.LIVE
                        ? SourceRef.DataMode.LIVE
                        : SourceRef.DataMode.FIXTURE;
        return new Facility(
                Facility.idOf(HOSPITAL_PROVIDER, raw.ykiho()),
                FacilityType.HOSPITAL,
                raw.nameKo(),
                null,
                raw.addressKo(),
                null,
                raw.phone(),
                raw.latitude(),
                raw.longitude(),
                distanceMeters,
                hospitalOperation(detailBatch.detail(), now, holiday, retrievedAt),
                new SourceRef(
                        "src:" + HOSPITAL_PROVIDER + ":" + raw.ykiho(),
                        "건강보험심사평가원 병원정보서비스",
                        raw.ykiho(),
                        retrievedAt,
                        origin,
                        "Health Insurance Review & Assessment Service — hospital directory"));
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
        if (detail.weekdayHours().isEmpty()) {
            return FacilityOperation.unknown(retrievedAt);
        }
        if (holiday) {
            return detail.holidayClosed()
                    ? FacilityOperation.closed(retrievedAt)
                    : FacilityOperation.unknown(retrievedAt);
        }
        if (now.getDayOfWeek() == DayOfWeek.SUNDAY) {
            if (detail.sundayClosed()) {
                return FacilityOperation.closed(retrievedAt);
            }
            if (!detail.weekdayHours().containsKey(DayOfWeek.SUNDAY.getValue())) {
                return FacilityOperation.unknown(retrievedAt);
            }
        }

        WeeklyHours hours =
                WeeklyHours.fromDutyTimes(
                        detail.weekdayHours().entrySet().stream()
                                .collect(
                                        java.util.stream.Collectors.toMap(
                                                Map.Entry::getKey,
                                                entry -> entry.getValue().toArray(String[]::new))));
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
