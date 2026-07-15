package com.mermaid.facility;

import com.mermaid.common.GeoUtils;
import com.mermaid.common.NotFoundException;
import com.mermaid.common.Parallel;
import com.mermaid.common.PublicApiException;
import com.mermaid.common.SourceRef;
import com.mermaid.facility.domain.DutyTable;
import com.mermaid.facility.domain.Facility;
import com.mermaid.facility.domain.FacilityOperation;
import com.mermaid.facility.domain.FacilityType;
import com.mermaid.facility.domain.OpenInterval;
import com.mermaid.facility.domain.WeeklyHours;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Answers "what is open near me, right now" (FR-02, TC-02).
 *
 * <p>Neither the radius filter nor the open-now filter exists upstream — no public API offers them
 * (spec §2-9). Both are implemented here, which makes this class the one place TC-02 can fail.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FacilityService {

    /** The public timetables are all local Korean time. Never use the server's default zone. */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final String PHARMACY_PROVIDER = "nmc"; // 국립중앙의료원
    private static final String HOSPITAL_PROVIDER = "hira"; // 건강보험심사평가원
    private static final String EMERGENCY_ROOM_PROVIDER = "nmc-emergency";
    /** NMC 기관ID (HPID): one letter + seven digits, e.g. {@code C1110693}. Widen only if a real id is rejected. */
    private static final Pattern HPID_PATTERN = Pattern.compile("[A-Za-z][0-9]{7}");
    /** HIRA ykiho decoded from the captured standard-base64 value, e.g. {@code $481881#51#$1#…}. */
    private static final Pattern HIRA_YKIHO_PATTERN = Pattern.compile("\\$\\d+(?:[#\\$]+\\d+)+");
    private static final int METRES_PER_KM = 1000;
    private static final int PHARMACY_WEEKLY_HOURS_CONCURRENCY = 4;
    /**
     * How many distance-ranked candidates an {@code open_now=true} request inspects before returning
     * the nearest confirmed-open results. Looking only as far as the returned pin limit would hide a
     * farther open facility behind nearer closed ones.
     *
     * <p>100 is the pharmacy location endpoint's own row cap, so for pharmacies it drops nothing. HIRA
     * supplies more — the Seoul City Hall fixture reports {@code totalCount: 440} — so for hospitals
     * this is a deliberate cap, and an open hospital ranked 101st or farther stays invisible.
     *
     * <p>Quota is not what bounds this. The daily budgets are 1,000 calls for the pharmacy APIs — the
     * tightest we hold — and 10,000 for HIRA; both detail lookups are cached per id ({@code @Cacheable}),
     * so a second map load over the same area re-spends nothing. Hospitals share the pharmacy's cap
     * because latency is the binding constraint, not because HIRA is scarce: with 10x the headroom it
     * has no reason to be the more conservative of the two.
     *
     * <p>Lowering this cap is the obvious lever for that latency and the wrong one to reach for blind:
     * it trades away the farther open hospital this constant exists to find. The levers that do not are
     * {@link #HOSPITAL_DETAIL_CONCURRENCY the detail concurrency} and the grid-shared list cache in
     * {@link HospitalApiClient#findNear} — reach for those first, and take timings there several at a
     * time in one window, because HIRA's detail latency swings by 3x between identical runs.
     */
    private static final int MAX_OPEN_NOW_CANDIDATES = 100;
    /**
     * How many HIRA detail calls may be in flight at once. This is the dominant cost of an
     * {@code open_now=true} hospital load — 100 per-ykiho lookups, each ~1.4 s against HIRA's slow
     * detail server (the pharmacy timetable endpoint answers the same shape in ~40 ms).
     *
     * <p>16, not 4. Four was inherited from {@code DrugService}, whose MFDS DUR endpoint throttles at
     * four; HIRA does not — it scales almost linearly. Measured against the live detail endpoint,
     * 100 calls: concurrency 4 ≈ 2.4 req/s, 8 ≈ 5.8, 16 ≈ 11.4, 32 ≈ 25 (2026-07-14). End to end, a
     * cold {@code open_now=true} Seoul City Hall load went from a ~57 s median (worst 86 s) to ~17 s
     * (worst 25 s) — measured in-app, both settings alternated in one window to defeat HIRA's drift.
     *
     * <p>Stopped at 16 rather than 32 on manners, not measurement: 32 concurrent sockets to a
     * government API sustained is unproven, and 16 already buys 3.4x. It does not make the cold path
     * fast — 17 s is still a frozen screen — so the loading state and the list cache still matter.
     */
    private static final int HOSPITAL_DETAIL_CONCURRENCY = 16;
    /** HIRA 종별코드 for 요양병원 (long-term-care hospitals) — the stable code, not the label. */
    private static final String NURSING_HOSPITAL_CODE = "28";
    /** The public API's maximum number of returned map pins. */
    public static final int MAX_FACILITY_RESULTS = 50;

    private final PharmacyApiClient pharmacyApiClient;
    private final HospitalApiClient hospitalApiClient;
    private final HospitalDetailApiClient hospitalDetailApiClient;
    private final EmergencyRoomApiClient emergencyRoomApiClient;
    private final HolidayCalendar holidayCalendar;
    private final Clock clock;

    public List<Facility> findNearby(
            double lat, double lng, int radiusMeters, boolean openNow, FacilityType type) {
        return findNearby(lat, lng, radiusMeters, openNow, type, MAX_FACILITY_RESULTS);
    }

    /** Returns the nearest requested number of facilities. */
    public List<Facility> findNearby(
            double lat, double lng, int radiusMeters, boolean openNow, FacilityType type, int limit) {
        return switch (type) {
            case PHARMACY -> pharmacies(lat, lng, radiusMeters, openNow, limit);
            case HOSPITAL -> hospitals(lat, lng, radiusMeters, openNow, limit);
            case EMERGENCY_ROOM -> emergencyRooms(lat, lng, radiusMeters, openNow, limit);
        };
    }

    /**
     * A single facility by its namespaced id (UI-03, DEV-205), e.g. {@code facility:nmc:C1110693}.
     *
     * <p>A pharmacy is fully reconstructable from its {@code hpid} alone: one basis call carries name,
     * address, phone, coordinates and the official timetable. A detail-by-id request has no origin
     * point, so {@code distanceMeters} is genuinely unknown here — {@code null}, never a fabricated 0.
     *
     * <p>A hospital is not: HIRA exposes treatment hours by {@code ykiho} but not identity, so name,
     * address and coordinates can only be read from the geo list ({@code getHospBasisList}), which
     * needs a coordinate and a radius. Until that gap is bridged, {@code facility:hira:…} answers 501 —
     * the honest "we cannot look it up this way", not a blank or invented card.
     *
     * @throws NotFoundException the id is malformed, names an unknown provider, or no such pharmacy
     *     exists upstream (→ 404)
     * @throws UnsupportedOperationException a hospital id, whose detail-by-id path is not built (→ 501)
     */
    public Facility detail(String id) {
        String[] parts = id.split(":", 3);
        if (parts.length != 3 || !"facility".equals(parts[0]) || parts[2].isEmpty()) {
            throw new NotFoundException("malformed facility id: " + id);
        }
        String provider = parts[1];
        String recordId = parts[2];

        if (PHARMACY_PROVIDER.equals(provider)) {
            if (!HPID_PATTERN.matcher(recordId).matches()) {
                // Reject a malformed HPID here, before any upstream call: otherwise every distinct
                // bogus id would spend one of the 1,000/day pharmacy calls and cache a negative
                // lookup. A malformed id is a 404, as documented — never an upstream request.
                throw new NotFoundException("malformed pharmacy id: " + recordId);
            }
            return pharmacyDetail(recordId);
        }
        if (HOSPITAL_PROVIDER.equals(provider)) {
            if (!isWellFormedHospitalId(recordId)) {
                throw new NotFoundException("malformed hospital id: " + recordId);
            }
            // TODO(team): hospital detail-by-id (DEV-205) needs a HIRA by-ykiho identity source. The
            // detail service (getDtlInfo2.8) returns hours but no name/address/coordinates; those come
            // only from the coordinate-and-radius list. Until that gap is bridged this is an honest 501.
            throw new UnsupportedOperationException(
                    "Hospital detail-by-id is not built — HIRA exposes hours by ykiho but not identity"
                            + " (name/address/coordinates); see FacilityService#detail");
        }
        throw new NotFoundException("unknown facility provider: " + provider);
    }

    /**
     * HIRA returns its opaque ykiho as standard base64; our URL id wraps that value in base64url.
     * Requiring both canonical encodings and the captured decoded grammar keeps arbitrary path text
     * from reaching an unimplemented detail route as a misleading 501.
     */
    private static boolean isWellFormedHospitalId(String recordId) {
        try {
            String ykiho = Facility.decodeSegment(recordId);
            if (!Facility.urlSafeSegment(ykiho).equals(recordId)) {
                return false;
            }
            String decodedYkiho =
                    new String(Base64.getDecoder().decode(ykiho), StandardCharsets.UTF_8);
            return HIRA_YKIHO_PATTERN.matcher(decodedYkiho).matches();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private Facility pharmacyDetail(String hpid) {
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(KST);
        boolean holiday = holidayCalendar.isHoliday(now.toLocalDate());
        Instant retrievedAt = now.toInstant();

        PharmacyApiClient.PharmacyDetailBatch batch = pharmacyApiClient.basisDetail(hpid);
        PharmacyApiClient.PharmacyDetail detail = batch.detail();
        if (detail == null) {
            throw new NotFoundException("no pharmacy found for hpid " + hpid);
        }

        return new Facility(
                Facility.idOf(PHARMACY_PROVIDER, hpid),
                FacilityType.PHARMACY,
                detail.name(),
                null, // the pharmacy API publishes no English name
                detail.address(),
                null,
                detail.phone(),
                detail.latitude(),
                detail.longitude(),
                null, // a detail-by-id request has no origin coordinate, so distance is unknown
                weeklyOperation(detail.weeklyHours(), now, holiday, retrievedAt),
                new SourceRef(
                        "src:" + PHARMACY_PROVIDER + ":" + hpid,
                        "국립중앙의료원 전국 약국 정보",
                        hpid,
                        retrievedAt,
                        batch.origin(),
                        "National Medical Center — pharmacy directory"),
                null, // the pharmacy API has no emergency-room flags
                null);
    }

    private List<Facility> pharmacies(
            double lat, double lng, int radiusMeters, boolean openNow, int limit) {
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(KST);
        boolean holiday = holidayCalendar.isHoliday(now.toLocalDate());
        Instant retrievedAt = now.toInstant();

        PharmacyApiClient.PharmacyBatch batch = pharmacyApiClient.findNear(lat, lng);
        int candidateLimit = openNow ? MAX_OPEN_NOW_CANDIDATES : limit;
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

        DutyTable weekly;
        boolean weeklyHoursLookupFailed = false;
        try {
            weekly = pharmacyApiClient.weeklyHours(raw.hpid());
        } catch (PublicApiException e) {
            // The directory row is already verified and map-worthy. A failed optional timetable must
            // make only its hours unknown, not discard every nearby pharmacy as a 503 response.
            log.warn("pharmacy weekly-hours lookup failed for {}; retaining directory row", raw.hpid());
            weekly = DutyTable.empty(listOrigin);
            weeklyHoursLookupFailed = true;
        }

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
                weeklyHoursLookupFailed
                        ? FacilityOperation.unknown(retrievedAt)
                        : operationOf(raw, weekly, now, holiday, retrievedAt),
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
            return weeklyOperation(weekly, now, holiday, retrievedAt);
        }

        OpenInterval today = OpenInterval.of(raw.startTime(), raw.endTime());
        if (today.isClosed()) {
            return FacilityOperation.unknown(retrievedAt);
        }
        LocalTime at = now.toLocalTime();
        boolean open = today.containsStartingToday(at) || today.carriesInto(at);
        return FacilityOperation.inferred(open, retrievedAt);
    }

    /**
     * Open-now from a published weekly table alone — {@code OFFICIAL_SCHEDULE}, or {@code UNKNOWN}
     * when the table holds no usable interval. Unlike {@link #operationOf} there is no single
     * start/end pair to infer from, so a missing schedule stays honestly unknown, never guessed.
     */
    private FacilityOperation weeklyOperation(
            DutyTable weekly, ZonedDateTime now, boolean holiday, Instant retrievedAt) {
        if (weekly.byDay().isEmpty()) {
            return FacilityOperation.unknown(retrievedAt);
        }
        WeeklyHours hours = WeeklyHours.fromDutyTimes(weekly.byDay());
        if (!hours.hasAnySchedule()) {
            return FacilityOperation.unknown(retrievedAt);
        }
        return hours.isOpenAt(now, holiday)
                ? FacilityOperation.open(retrievedAt)
                : FacilityOperation.closed(retrievedAt);
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

        // Resolve each row's distance once — here — so it is filtered before the detail fan-out and
        // the same metre value lands on the card. It is our Haversine from the caller's coordinate,
        // not HIRA's origin-relative figure, because the list was fetched grid-centred and shared.
        //
        // HIRA classifies 요양병원 (long-term-care hospitals) under 종별코드 28. The default search is
        // for acute care, so omit only that exact code before any detail call, matching the stable code
        // rather than the display label; unknown/other codes remain visible rather than guessed out.
        //
        // HIRA detail calls are per-ykiho. For an ordinary map load, inspect only pins that can be
        // returned; for open-now, inspect a wider but fixed candidate set so a farther open hospital
        // is not hidden behind nearer closed ones.
        int candidateLimit = openNow ? MAX_OPEN_NOW_CANDIDATES : limit;
        List<NearbyHospital> candidates =
                batch.hospitals().stream()
                        .map(h -> new NearbyHospital(h, hospitalDistanceMetres(h, lat, lng)))
                        .filter(h -> h.distanceMeters() <= radiusMeters)
                        .filter(h -> !NURSING_HOSPITAL_CODE.equals(h.raw().classificationCode()))
                        .sorted(Comparator.comparingDouble(NearbyHospital::distanceMeters))
                        .limit(candidateLimit)
                        .toList();

        return Parallel.map(
                        candidates,
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
                .limit(limit)
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

    /** Distance in metres, always our own Haversine from the caller — see the body for why HIRA's is unused. */
    private double hospitalDistanceMetres(
            HospitalApiClient.RawHospital raw, double originLat, double originLng) {
        // Always our own Haversine, never HIRA's distance. The list is cached grid-centred and shared
        // (HospitalApiClient#findNear), so HIRA's origin-relative figure belongs to the cell centre,
        // not this caller. It matched ours within a mean 4 m on 100 live rows anyway (2026-07-14).
        // raw.distanceMeters() is still parsed — the list fixture test asserts it — just not trusted here.
        return GeoUtils.haversineMeters(originLat, originLng, raw.latitude(), raw.longitude());
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

    private List<Facility> emergencyRooms(
            double lat, double lng, int radiusMeters, boolean openNow, int limit) {
        Instant retrievedAt = clock.instant();
        EmergencyRoomApiClient.EmergencyRoomBatch batch = emergencyRoomApiClient.findNear(lat, lng);

        return batch.emergencyRooms().stream()
                .map(raw -> toEmergencyRoom(raw, batch.origin(), lat, lng, retrievedAt))
                .filter(f -> f.distanceMeters() <= radiusMeters)
                // No NMC location record proves an emergency department is open. `true` would lie,
                // so open_now includes only an explicitly confirmed open status.
                .filter(f -> !openNow || Boolean.TRUE.equals(f.operation().isOpenNow()))
                .sorted(Comparator.comparingDouble(Facility::distanceMeters))
                .limit(limit)
                .toList();
    }

    private Facility toEmergencyRoom(
            EmergencyRoomApiClient.RawEmergencyRoom raw,
            SourceRef.DataMode origin,
            double originLat,
            double originLng,
            Instant retrievedAt) {
        return new Facility(
                Facility.idOf(EMERGENCY_ROOM_PROVIDER, raw.hpid()),
                FacilityType.EMERGENCY_ROOM,
                raw.name(),
                null,
                raw.address(),
                null,
                raw.phone(),
                raw.latitude(),
                raw.longitude(),
                emergencyDistanceMetres(raw, originLat, originLng),
                FacilityOperation.unknown(retrievedAt),
                new SourceRef(
                        "src:" + EMERGENCY_ROOM_PROVIDER + ":" + raw.hpid(),
                        "국립중앙의료원 응급의료기관 정보",
                        raw.hpid(),
                        retrievedAt,
                        origin,
                        "National Medical Center — emergency facility directory"),
                null,
                null);
    }

    private double emergencyDistanceMetres(
            EmergencyRoomApiClient.RawEmergencyRoom raw, double originLat, double originLng) {
        // A cached NMC list can belong to another point in this 100-m grid. Its provider distance is
        // relative to that original point, so using it here would move radius boundaries and ordering.
        return GeoUtils.haversineMeters(originLat, originLng, raw.latitude(), raw.longitude());
    }

}
