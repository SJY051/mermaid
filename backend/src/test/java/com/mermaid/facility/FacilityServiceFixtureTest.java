package com.mermaid.facility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mermaid.common.FixtureLoader;
import com.mermaid.common.NotFoundException;
import com.mermaid.common.SourceRef;
import com.mermaid.config.DataModeProperties;
import com.mermaid.config.PublicApiProperties;
import com.mermaid.facility.domain.Facility;
import com.mermaid.facility.domain.FacilityOperation;
import com.mermaid.facility.domain.FacilityType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The whole facility path, driven by the response the real pharmacy API returned on 2026-07-10.
 *
 * <p>This is what {@code data-mode: fixture} buys us: FE-2 can build the map, QA can run the demo,
 * and nobody spends a single call from the 1,000/day budget.
 */
class FacilityServiceFixtureTest {

    /** 서울시청. The fixture was captured from exactly this point. */
    private static final double LAT = 37.5663;
    private static final double LNG = 126.9779;

    /** 2026-07-10 was a Friday. 14:00 KST = 05:00 UTC. */
    private static final Clock FRIDAY_AFTERNOON =
            Clock.fixed(Instant.parse("2026-07-10T05:00:00Z"), ZoneId.of("UTC"));

    /** 03:00 KST on the same Friday — every pharmacy in the fixture is shut. */
    private static final Clock FRIDAY_PREDAWN =
            Clock.fixed(Instant.parse("2026-07-09T18:00:00Z"), ZoneId.of("UTC"));

    /** 13:00 KST on the same Friday — inside the hospital fixture's lunchWeek interval. */
    private static final Clock FRIDAY_LUNCH =
            Clock.fixed(Instant.parse("2026-07-10T04:00:00Z"), ZoneId.of("UTC"));

    /** 14:00 KST on 2026 어린이날, a captured official public holiday. */
    private static final Clock CHILDRENS_DAY_AFTERNOON =
            Clock.fixed(Instant.parse("2026-05-05T05:00:00Z"), ZoneId.of("UTC"));

    private FacilityService serviceAt(Clock clock) {
        var props =
                new PublicApiProperties("", "https://x", "https://x", "https://x", "https://x", "https://x", "https://x", "https://x");
        var dataMode = new DataModeProperties(DataModeProperties.DataMode.FIXTURE);
        var loader = new FixtureLoader(new ObjectMapper());
        return new FacilityService(
                new PharmacyApiClient(null, props, dataMode, loader),
                new HospitalApiClient(null, props, dataMode, loader),
                new HospitalDetailApiClient(null, props, dataMode, loader),
                new EmergencyRoomApiClient(null, props, dataMode, loader),
                new HolidayCalendar(date -> false),
                clock);
    }

    private FacilityService serviceWithDetail(
            Clock clock,
            HospitalDetailApiClient.HospitalDetail detail,
            SourceRef.DataMode listOrigin,
            SourceRef.DataMode detailOrigin) {
        return serviceWithDetail(clock, detail, listOrigin, detailOrigin, new HolidayCalendar(date -> false));
    }

    private FacilityService serviceWithDetail(
            Clock clock,
            HospitalDetailApiClient.HospitalDetail detail,
            SourceRef.DataMode listOrigin,
            SourceRef.DataMode detailOrigin,
            HolidayCalendar holidayCalendar) {
        var props =
                new PublicApiProperties("", "https://x", "https://x", "https://x", "https://x", "https://x", "https://x", "https://x");
        var dataMode = new DataModeProperties(DataModeProperties.DataMode.FIXTURE);
        var loader = new FixtureLoader(new ObjectMapper());
        var listClient =
                new HospitalApiClient(null, props, dataMode, loader) {
                    @Override
                    public HospitalBatch findNear(double lat, double lng, int radiusMeters) {
                        return new HospitalBatch(
                                List.of(
                                        new RawHospital(
                                                detail.ykiho(),
                                                "Hospital",
                                                "Seoul",
                                                null,
                                                null,
                                                null,
                                                LAT,
                                                LNG,
                                                10.0)),
                                listOrigin);
                    }
                };
        var detailClient =
                new HospitalDetailApiClient(null, props, dataMode, loader) {
                    @Override
                    public HospitalDetailBatch findByYkiho(String ykiho) {
                        return new HospitalDetailBatch(detail, detailOrigin);
                    }
                };
        return new FacilityService(
                new PharmacyApiClient(null, props, dataMode, loader),
                listClient,
                detailClient,
                new EmergencyRoomApiClient(null, props, dataMode, loader),
                holidayCalendar,
                clock);
    }

    private static Facility hospitalNamed(List<Facility> hospitals, String nameKo) {
        return hospitals.stream().filter(f -> nameKo.equals(f.nameKo())).findFirst().orElseThrow();
    }

    private HolidayCalendar officialFixtureCalendar() {
        var props =
                new PublicApiProperties("", "https://x", "https://x", "https://x", "https://x", "https://x", "https://x");
        return new HolidayCalendar(
                new HolidayApiClient(
                        null, props, new DataModeProperties(DataModeProperties.DataMode.FIXTURE)));
    }

    /**
     * A {@code hybrid} service whose upstream is guaranteed to fail (the base URL points at a closed
     * port), so every fetch falls back to fixtures the way it would during a government outage.
     */
    private FacilityService hybridServiceWithDeadUpstream(Clock clock) {
        var props =
                new PublicApiProperties(
                        "decoding-key", // non-blank → isConfigured(), so we attempt the live call
                        "http://127.0.0.1:1", // connection refused → fallback path
                        "https://x",
                        "https://x", "https://x", "https://x", "https://x", "https://x");
        var dataMode = new DataModeProperties(DataModeProperties.DataMode.HYBRID);
        var loader = new FixtureLoader(new ObjectMapper());
        var client = org.springframework.web.reactive.function.client.WebClient.create();
        return new FacilityService(
                new PharmacyApiClient(client, props, dataMode, loader),
                new HospitalApiClient(client, props, dataMode, loader),
                new HospitalDetailApiClient(client, props, dataMode, loader),
                new EmergencyRoomApiClient(client, props, dataMode, loader),
                new HolidayCalendar(date -> false),
                clock);
    }

    @Test
    @DisplayName("fixture mode returns real pharmacies without touching the network")
    void returnsPharmacies() {
        List<Facility> found =
                serviceAt(FRIDAY_AFTERNOON).findNearby(LAT, LNG, 1000, false, FacilityType.PHARMACY);

        assertThat(found).isNotEmpty();
        Facility first = found.get(0);
        assertThat(first.id()).startsWith("facility:nmc:");
        assertThat(first.nameKo()).isNotBlank();
        assertThat(first.type()).isEqualTo(FacilityType.PHARMACY);
    }

    @Test
    @DisplayName("a fixture row is labelled as fixture data, never as live")
    void fixtureIsLabelled() {
        Facility f = serviceAt(FRIDAY_AFTERNOON).findNearby(LAT, LNG, 1000, false, FacilityType.PHARMACY).get(0);

        assertThat(f.source().dataMode()).isEqualTo(SourceRef.DataMode.FIXTURE);
        assertThat(f.source().recordId()).isNotBlank();
    }

    @Test
    @DisplayName("a hybrid outage fallback is labelled fixture, not live (§2-14: never disguise fixture)")
    void hybridFallbackIsLabelledFixtureNotLive() {
        // Before provenance was per-fetch, sourceOf derived data_mode from the app-wide switch, so a
        // hybrid outage — real fallback to fixtures — still stamped every card `live`. That is exactly
        // the disguise §2-14 forbids. This turns red if data_mode goes back to the app-wide mode.
        Facility f =
                hybridServiceWithDeadUpstream(FRIDAY_AFTERNOON)
                        .findNearby(LAT, LNG, 1000, false, FacilityType.PHARMACY)
                        .get(0);

        assertThat(f.source().dataMode()).isEqualTo(SourceRef.DataMode.FIXTURE);
    }

    @Test
    @DisplayName("distance comes back in metres, not the API's kilometres")
    void distanceIsMetres() {
        List<Facility> found =
                serviceAt(FRIDAY_AFTERNOON).findNearby(LAT, LNG, 1000, false, FacilityType.PHARMACY);

        // The fixture's nearest row is 0.14 km from 서울시청. If we forgot the conversion it would
        // read as 0.14 m and every radius filter would pass everything.
        assertThat(found.get(0).distanceMeters()).isBetween(100.0, 200.0);
    }

    @Test
    @DisplayName("results are sorted by distance")
    void sortedByDistance() {
        List<Facility> found =
                serviceAt(FRIDAY_AFTERNOON).findNearby(LAT, LNG, 5000, false, FacilityType.PHARMACY);

        assertThat(found).isSortedAccordingTo((a, b) -> Double.compare(a.distanceMeters(), b.distanceMeters()));
    }

    @Test
    @DisplayName("the radius filter actually excludes things")
    void radiusExcludes() {
        var service = serviceAt(FRIDAY_AFTERNOON);

        int wide = service.findNearby(LAT, LNG, 5000, false, FacilityType.PHARMACY).size();
        int narrow = service.findNearby(LAT, LNG, 150, false, FacilityType.PHARMACY).size();

        assertThat(narrow).isLessThan(wide).isPositive();
    }

    @Test
    @DisplayName("a published weekly timetable gives official opening-hours confidence")
    void statusIsOfficialWhenWeeklyTableExists() {
        Facility f =
                serviceAt(FRIDAY_AFTERNOON)
                        .findNearby(LAT, LNG, 1000, false, FacilityType.PHARMACY)
                        .get(0);

        assertThat(f.operation().statusConfidence())
                .isEqualTo(FacilityOperation.StatusConfidence.OFFICIAL_SCHEDULE);
        assertThat(f.operation().isOpenNow()).isTrue();
        assertThat(f.operation().notice()).containsIgnoringCase("call before");
    }

    @Test
    @DisplayName("open_now=true at 14:00 keeps pharmacies; at 03:00 it keeps none")
    void openNowFiltersByTime() {
        List<Facility> afternoon =
                serviceAt(FRIDAY_AFTERNOON).findNearby(LAT, LNG, 5000, true, FacilityType.PHARMACY);
        List<Facility> predawn =
                serviceAt(FRIDAY_PREDAWN).findNearby(LAT, LNG, 5000, true, FacilityType.PHARMACY);

        assertThat(afternoon).isNotEmpty();
        assertThat(predawn).isEmpty();
    }

    @Test
    @DisplayName("open_now=false at 03:00 still lists them, marked closed")
    void closedPharmaciesAreStillListed() {
        List<Facility> predawn =
                serviceAt(FRIDAY_PREDAWN).findNearby(LAT, LNG, 5000, false, FacilityType.PHARMACY);

        assertThat(predawn).isNotEmpty();
        assertThat(predawn.get(0).operation().isOpenNow()).isFalse();
        assertThat(predawn.get(0).operation().status())
                .isEqualTo(FacilityOperation.OperationStatus.CLOSED);
    }

    @Test
    @DisplayName("pharmacy detail-by-id reconstructs the full facility from its hpid alone (DEV-205)")
    void pharmacyDetailByIdReconstructsFromBasis() {
        Facility f = serviceAt(FRIDAY_AFTERNOON).detail("facility:nmc:C1110693");

        assertThat(f.id()).isEqualTo("facility:nmc:C1110693");
        assertThat(f.type()).isEqualTo(FacilityType.PHARMACY);
        assertThat(f.nameKo()).isEqualTo("청실약국");
        assertThat(f.addressKo()).contains("무교로");
        assertThat(f.phone()).isEqualTo("02-3789-6953");
        // The basis endpoint's coordinate fields are wgs84Lat/wgs84Lon, not the location endpoint's.
        assertThat(f.latitude()).isEqualTo(37.5672818668855);
        assertThat(f.longitude()).isEqualTo(126.978921749794);
        // A detail-by-id request carries no origin point, so distance is genuinely unknown here.
        assertThat(f.distanceMeters()).isNull();
        assertThat(f.operation().statusConfidence())
                .isEqualTo(FacilityOperation.StatusConfidence.OFFICIAL_SCHEDULE);
        assertThat(f.source().dataMode()).isEqualTo(SourceRef.DataMode.FIXTURE);
        assertThat(f.source().recordId()).isEqualTo("C1110693");
    }

    @Test
    @DisplayName("a cached pharmacy detail keeps its original provenance timestamp (issue #95)")
    void pharmacyDetailUsesTheBatchRetrievedAtForSourceAndOperation() {
        Instant fetchedAt = Instant.parse("2026-07-01T01:02:03Z");
        var props =
                new PublicApiProperties(
                        "", "https://x", "https://x", "https://x", "https://x", "https://x", "https://x");
        var dataMode = new DataModeProperties(DataModeProperties.DataMode.FIXTURE);
        var loader = new FixtureLoader(new ObjectMapper());
        var pharmacy =
                new PharmacyApiClient(null, props, dataMode, loader) {
                    @Override
                    public PharmacyDetailBatch basisDetail(String hpid) {
                        return new PharmacyDetailBatch(
                                new PharmacyDetail(
                                        hpid,
                                        "캐시약국",
                                        "서울 중구",
                                        "02-000-0000",
                                        LAT,
                                        LNG,
                                        new com.mermaid.facility.domain.DutyTable(
                                                Map.of(5, List.of("0900", "1900")),
                                                SourceRef.DataMode.LIVE)),
                                SourceRef.DataMode.LIVE,
                                fetchedAt);
                    }
                };
        var service =
                new FacilityService(
                        pharmacy,
                        new HospitalApiClient(null, props, dataMode, loader),
                        new HospitalDetailApiClient(null, props, dataMode, loader),
                        new EmergencyRoomApiClient(null, props, dataMode, loader),
                        new HolidayCalendar(date -> false),
                        FRIDAY_AFTERNOON);

        Facility facility = service.detail("facility:nmc:C1110693");

        // Restamping batch.retrievedAt() with the request clock makes both assertions red.
        assertThat(facility.source().retrievedAt()).isEqualTo(fetchedAt);
        assertThat(facility.operation().verifiedAt()).isEqualTo(fetchedAt);
    }

    @Test
    @DisplayName("a well-formed hpid upstream does not know is a NotFoundException, not a blank card")
    void pharmacyDetailUnknownHpidThrowsNotFound() {
        // Well-formed (letter + seven digits) but absent from the fixture: this reaches basisDetail and
        // gets a null row back, distinct from the malformed-id short-circuit below.
        assertThatThrownBy(() -> serviceAt(FRIDAY_AFTERNOON).detail("facility:nmc:C9999999"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("a malformed provider id is a 404 rejected before any upstream call (quota)")
    void malformedProviderIdMakesNoUpstreamCall() {
        var props =
                new PublicApiProperties("", "https://x", "https://x", "https://x", "https://x", "https://x", "https://x");
        var dataMode = new DataModeProperties(DataModeProperties.DataMode.FIXTURE);
        var loader = new FixtureLoader(new ObjectMapper());
        var pharmacy =
                new PharmacyApiClient(null, props, dataMode, loader) {
                    @Override
                    public PharmacyDetailBatch basisDetail(String hpid) {
                        throw new AssertionError("basisDetail must not run for a malformed id: " + hpid);
                    }
                };
        var service =
                new FacilityService(
                        pharmacy,
                        new HospitalApiClient(null, props, dataMode, loader),
                        new HospitalDetailApiClient(null, props, dataMode, loader),
                        new EmergencyRoomApiClient(null, props, dataMode, loader),
                        new HolidayCalendar(date -> false),
                        FRIDAY_AFTERNOON);

        // nmc not one-letter-seven-digits, and a hira segment that is not base64url: both are 404
        // without spending a pharmacy call (the overridden basisDetail would fail the test if reached).
        assertThatThrownBy(() -> service.detail("facility:nmc:not-an-hpid"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.detail("facility:hira:not*base64"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("hospital detail-by-id stays 501: HIRA has no by-ykiho identity lookup")
    void hospitalDetailByIdIsNotImplemented() {
        String capturedYkiho =
                "JDQ4MTg4MSM1MSMkMSMkMCMkODkkMzgxMzUxIzExIyQxIyQzIyQ3OSQ0NjEwMDIjNjEjJDEjJDQjJDgz";
        String id = Facility.idOf("hira", Facility.urlSafeSegment(capturedYkiho));

        assertThatThrownBy(() -> serviceAt(FRIDAY_AFTERNOON).detail(id))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a malformed id or unknown provider is a NotFoundException, never a 500")
    void malformedOrUnknownProviderIdThrowsNotFound() {
        var service = serviceAt(FRIDAY_AFTERNOON);
        assertThatThrownBy(() -> service.detail("not-a-facility-id"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.detail("facility:nmc:"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.detail("facility:unknownprovider:X"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("hospital fixture hours are official, fixture-labelled, and closed during lunch")
    void hospitalsUseOfficialHoursAndLunchClosure() {
        List<Facility> afternoonResults =
                serviceAt(FRIDAY_AFTERNOON)
                        .findNearby(LAT, LNG, 1000, false, FacilityType.HOSPITAL);
        List<Facility> lunchResults =
                serviceAt(FRIDAY_LUNCH)
                        .findNearby(LAT, LNG, 1000, false, FacilityType.HOSPITAL);
        Facility afternoon = hospitalNamed(afternoonResults, "강북삼성병원");
        Facility lunch = hospitalNamed(lunchResults, "강북삼성병원");

        assertThat(afternoon.id()).startsWith("facility:hira:");
        assertThat(afternoon.operation().isOpenNow()).isTrue();
        assertThat(afternoon.operation().statusConfidence())
                .isEqualTo(FacilityOperation.StatusConfidence.OFFICIAL_SCHEDULE);
        assertThat(afternoon.source().dataMode()).isEqualTo(SourceRef.DataMode.FIXTURE);
        // Emergency availability travels to the card for the matched hospital (fixture emyNgtYn=Y)...
        assertThat(afternoon.emergencyNight()).isTrue();
        assertThat(lunch.operation().isOpenNow()).isFalse();

        assertThat(afternoonResults).noneMatch(f -> "아미나요양병원".equals(f.nameKo()));
    }

    @Test
    @DisplayName("the radius filter drops hospitals beyond the bound, by Haversine from the caller (SC-002)")
    void hospitalRadiusExcludesByMetres() {
        var service = serviceAt(FRIDAY_AFTERNOON);

        // Distance is our Haversine from the caller, not HIRA's figure (the list is cached grid-centred
        // and shared). From the fixture coordinates the rows sit at 901.1 m (아미나요양병원, 요양병원),
        // 924.8 m (강북삼성병원), and 965.9 m (서울적십자병원). The default acute-care search excludes
        // only the nursing category; a 950-m radius then retains 강북삼성병원 and drops 서울적십자병원.
        List<Facility> wide = service.findNearby(LAT, LNG, 1000, false, FacilityType.HOSPITAL);
        List<Facility> narrow = service.findNearby(LAT, LNG, 950, false, FacilityType.HOSPITAL);

        assertThat(wide).extracting(Facility::nameKo).containsExactly("강북삼성병원", "서울적십자병원");
        assertThat(wide).noneMatch(f -> "아미나요양병원".equals(f.nameKo()));
        assertThat(narrow).singleElement().extracting(Facility::nameKo).isEqualTo("강북삼성병원");
        assertThat(narrow).allSatisfy(f -> assertThat(f.distanceMeters()).isLessThanOrEqualTo(950.0));
        assertThat(narrow).noneMatch(f -> "서울적십자병원".equals(f.nameKo()));
        assertThat(wide)
                .filteredOn(f -> "서울적십자병원".equals(f.nameKo()))
                .singleElement()
                .satisfies(f -> assertThat(f.distanceMeters()).isBetween(965.0, 966.0));
    }

    @Test
    @DisplayName("a hospital with no published detail schedule remains unknown, not closed")
    void hospitalWithoutDetailScheduleIsUnknown() {
        var service =
                serviceWithDetail(
                        FRIDAY_AFTERNOON,
                        new HospitalDetailApiClient.HospitalDetail(
                                "YKIHO-1", Map.of(), Optional.empty(), false, false, null, null),
                        SourceRef.DataMode.LIVE,
                        SourceRef.DataMode.LIVE);

        Facility hospital = service.findNearby(LAT, LNG, 1000, false, FacilityType.HOSPITAL).getFirst();

        assertThat(hospital.operation().isOpenNow()).isNull();
        assertThat(hospital.operation().status())
                .isEqualTo(FacilityOperation.OperationStatus.UNKNOWN);
    }

    @Test
    @DisplayName("lunchWeek does not close a Saturday schedule")
    void hospitalLunchWeekDoesNotCloseSaturday() {
        Clock saturdayLunch = Clock.fixed(Instant.parse("2026-07-11T04:00:00Z"), ZoneId.of("UTC"));
        var service =
                serviceWithDetail(
                        saturdayLunch,
                        new HospitalDetailApiClient.HospitalDetail(
                                "YKIHO-1",
                                Map.of(6, List.of("0830", "1700")),
                                Optional.of(
                                        new HospitalDetailApiClient.LunchBreak(
                                                java.time.LocalTime.of(12, 30), java.time.LocalTime.of(13, 30))),
                                false,
                                false,
                                null,
                                null),
                        SourceRef.DataMode.LIVE,
                        SourceRef.DataMode.LIVE);

        Facility hospital = service.findNearby(LAT, LNG, 1000, false, FacilityType.HOSPITAL).getFirst();

        assertThat(hospital.operation().isOpenNow()).isTrue();
    }

    @Test
    @DisplayName("missing Sunday hours without an official closure remain unknown")
    void hospitalWithoutSundayHoursOrClosureIsUnknown() {
        Clock sunday = Clock.fixed(Instant.parse("2026-07-12T05:00:00Z"), ZoneId.of("UTC"));
        var service =
                serviceWithDetail(
                        sunday,
                        new HospitalDetailApiClient.HospitalDetail(
                                "YKIHO-1", Map.of(1, List.of("0830", "1700")), Optional.empty(), false, false, null, null),
                        SourceRef.DataMode.LIVE,
                        SourceRef.DataMode.FIXTURE);

        Facility hospital = service.findNearby(LAT, LNG, 1000, false, FacilityType.HOSPITAL).getFirst();

        assertThat(hospital.operation().isOpenNow()).isNull();
        assertThat(hospital.source().dataMode()).isEqualTo(SourceRef.DataMode.FIXTURE);
    }

    @Test
    @DisplayName("an explicit Sunday closure applies even without published treatment hours")
    void hospitalExplicitSundayClosureAppliesWithoutSundayHours() {
        Clock sunday = Clock.fixed(Instant.parse("2026-07-12T05:00:00Z"), ZoneId.of("UTC"));
        var service =
                serviceWithDetail(
                        sunday,
                        new HospitalDetailApiClient.HospitalDetail(
                                "YKIHO-1", Map.of(), Optional.empty(), true, false, null, null),
                        SourceRef.DataMode.LIVE,
                        SourceRef.DataMode.LIVE);

        Facility hospital = service.findNearby(LAT, LNG, 1000, false, FacilityType.HOSPITAL).getFirst();

        assertThat(hospital.operation().isOpenNow()).isFalse();
    }

    @Test
    @DisplayName("an explicit holiday closure applies even without published treatment hours")
    void hospitalExplicitHolidayClosureAppliesWithoutTreatmentHours() {
        var service =
                serviceWithDetail(
                        FRIDAY_AFTERNOON,
                        new HospitalDetailApiClient.HospitalDetail(
                                "YKIHO-1", Map.of(), Optional.empty(), false, true, null, null),
                        SourceRef.DataMode.LIVE,
                        SourceRef.DataMode.LIVE,
                        new HolidayCalendar(date -> false) {
                            @Override
                            public boolean isHoliday(java.time.LocalDate date) {
                                return true;
                            }
                        });

        Facility hospital = service.findNearby(LAT, LNG, 1000, false, FacilityType.HOSPITAL).getFirst();

        assertThat(hospital.operation().isOpenNow()).isFalse();
        assertThat(hospital.operation().status())
                .isEqualTo(FacilityOperation.OperationStatus.CLOSED);
    }

    @Test
    @DisplayName("hospital detail fan-out is bounded at sixteen in flight")
    void hospitalDetailFetchesAreBoundedAtSixteen() {
        var props =
                new PublicApiProperties("", "https://x", "https://x", "https://x", "https://x", "https://x", "https://x", "https://x");
        var dataMode = new DataModeProperties(DataModeProperties.DataMode.FIXTURE);
        var loader = new FixtureLoader(new ObjectMapper());
        // 20 rows, all within radius, so the fan-out could run 20 at once if it were unbounded — the
        // latch below proves it plateaus at exactly 16, which fails if the bound reverts to 4 (the
        // latch never reaches 0) or is lifted (the max climbs to 20).
        List<HospitalApiClient.RawHospital> hospitals =
                IntStream.range(0, 20)
                        .mapToObj(
                                i ->
                                        new HospitalApiClient.RawHospital(
                                                "YKIHO-" + i,
                                                "Hospital " + i,
                                                "Seoul",
                                                null,
                                                null,
                                                null,
                                                LAT,
                                                LNG,
                                                10.0 + i))
                        .toList();
        var listClient =
                new HospitalApiClient(null, props, dataMode, loader) {
                    @Override
                    public HospitalBatch findNear(double lat, double lng, int radiusMeters) {
                        return new HospitalBatch(hospitals, SourceRef.DataMode.LIVE);
                    }
                };
        var active = new AtomicInteger();
        var maximum = new AtomicInteger();
        var firstSixteen = new CountDownLatch(16);
        var detailClient =
                new HospitalDetailApiClient(null, props, dataMode, loader) {
                    @Override
                    public HospitalDetailBatch findByYkiho(String ykiho) {
                        int inFlight = active.incrementAndGet();
                        maximum.accumulateAndGet(inFlight, Math::max);
                        firstSixteen.countDown();
                        try {
                            firstSixteen.await(1, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(e);
                        } finally {
                            active.decrementAndGet();
                        }
                        return new HospitalDetailBatch(
                                new HospitalDetail(
                                        ykiho, Map.of(1, List.of("0830", "1700")), Optional.empty(), false, false, null, null),
                                SourceRef.DataMode.LIVE);
                    }
                };
        var service =
                new FacilityService(
                        new PharmacyApiClient(null, props, dataMode, loader),
                        listClient,
                        detailClient,
                        new EmergencyRoomApiClient(null, props, dataMode, loader),
                        new HolidayCalendar(date -> false),
                        FRIDAY_AFTERNOON);

        List<Facility> found = service.findNearby(LAT, LNG, 1000, false, FacilityType.HOSPITAL);

        assertThat(found).hasSize(20);
        assertThat(maximum).hasValue(16);
    }

    @Test
    @DisplayName("fetches details for only the nearest hospitals, never one call per row in a dense radius")
    void hospitalDetailFetchIsCappedAtNearest() {
        var props =
                new PublicApiProperties("", "https://x", "https://x", "https://x", "https://x", "https://x", "https://x", "https://x");
        var dataMode = new DataModeProperties(DataModeProperties.DataMode.FIXTURE);
        var loader = new FixtureLoader(new ObjectMapper());
        // One closest nursing hospital plus 69 acute-care candidates: the nursing row must not spend
        // a detail call or consume one of the public result limit's slots.
        List<HospitalApiClient.RawHospital> many =
                IntStream.range(0, 70)
                        .mapToObj(
                                i ->
                                        new HospitalApiClient.RawHospital(
                                                "YKIHO-" + i,
                                                i == 0 ? "Nursing hospital" : "Hospital " + i,
                                                "Seoul",
                                                null,
                                                null,
                                                i == 0 ? "28" : "11", // 종별코드: 28=요양병원, 11=종합병원
                                                LAT, LNG, 10.0 + i))
                        .toList();
        var listClient =
                new HospitalApiClient(null, props, dataMode, loader) {
                    @Override
                    public HospitalBatch findNear(double lat, double lng, int radiusMeters) {
                        return new HospitalBatch(many, SourceRef.DataMode.LIVE);
                    }
                };
        var detailCalls = new AtomicInteger();
        var nursingDetailCalls = new AtomicInteger();
        var detailClient =
                new HospitalDetailApiClient(null, props, dataMode, loader) {
                    @Override
                    public HospitalDetailBatch findByYkiho(String ykiho) {
                        detailCalls.incrementAndGet();
                        if ("YKIHO-0".equals(ykiho)) {
                            nursingDetailCalls.incrementAndGet();
                        }
                        return new HospitalDetailBatch(
                                new HospitalDetail(
                                        ykiho, Map.of(1, List.of("0830", "1700")), Optional.empty(),
                                        false, false, null, null),
                                SourceRef.DataMode.LIVE);
                    }
                };
        var service =
                new FacilityService(
                        new PharmacyApiClient(null, props, dataMode, loader),
                        listClient,
                        detailClient,
                        new EmergencyRoomApiClient(null, props, dataMode, loader),
                        new HolidayCalendar(date -> false),
                        FRIDAY_AFTERNOON);

        List<Facility> found = service.findNearby(LAT, LNG, 2000, false, FacilityType.HOSPITAL, 10);

        // The public `limit` controls both cards and detail calls, so changing it cannot silently
        // restore an unbounded fan-out behind a seemingly small map response.
        assertThat(detailCalls).hasValue(10);
        assertThat(nursingDetailCalls).hasValue(0);
        assertThat(found).hasSize(10);
        assertThat(found).noneMatch(f -> "Nursing hospital".equals(f.nameKo()));
        assertThat(found).allSatisfy(f -> assertThat(f.distanceMeters()).isLessThanOrEqualTo(20.0));
    }

    @Test
    @DisplayName("open-now hospitals inspect 100 candidates so a farther open hospital is not hidden")
    void hospitalOpenNowLooksPastReturnedLimitWithinCandidateCap() {
        var props =
                new PublicApiProperties("", "https://x", "https://x", "https://x", "https://x", "https://x", "https://x", "https://x");
        var dataMode = new DataModeProperties(DataModeProperties.DataMode.FIXTURE);
        var loader = new FixtureLoader(new ObjectMapper());
        List<HospitalApiClient.RawHospital> many =
                IntStream.rangeClosed(0, 120)
                        .mapToObj(
                                i ->
                                        new HospitalApiClient.RawHospital(
                                                "YKIHO-" + i,
                                                i == 0 ? "Nursing hospital" : "Hospital " + i,
                                                "Seoul",
                                                null,
                                                null,
                                                i == 0 ? "28" : "11",
                                                LAT,
                                                LNG,
                                                10.0 + i))
                        .toList();
        var listClient =
                new HospitalApiClient(null, props, dataMode, loader) {
                    @Override
                    public HospitalBatch findNear(double lat, double lng, int radiusMeters) {
                        return new HospitalBatch(many, SourceRef.DataMode.LIVE);
                    }
                };
        var detailCalls = new AtomicInteger();
        var detailClient =
                new HospitalDetailApiClient(null, props, dataMode, loader) {
                    @Override
                    public HospitalDetailBatch findByYkiho(String ykiho) {
                        detailCalls.incrementAndGet();
                        boolean fartherCandidateIsOpen = "YKIHO-100".equals(ykiho);
                        return new HospitalDetailBatch(
                                new HospitalDetail(
                                        ykiho,
                                        Map.of(
                                                5,
                                                fartherCandidateIsOpen
                                                        ? List.of("0830", "1700")
                                                        : List.of("0830", "1300")),
                                        Optional.empty(),
                                        false,
                                        false,
                                        null,
                                        null),
                                SourceRef.DataMode.LIVE);
                    }
                };
        var service =
                new FacilityService(
                        new PharmacyApiClient(null, props, dataMode, loader),
                        listClient,
                        detailClient,
                        new EmergencyRoomApiClient(null, props, dataMode, loader),
                        new HolidayCalendar(date -> false),
                        FRIDAY_AFTERNOON);

        List<Facility> found = service.findNearby(LAT, LNG, 2000, true, FacilityType.HOSPITAL, 10);

        // Removing the wider candidate set makes this empty; removing its cap calls all 120 acute rows.
        assertThat(found).extracting(Facility::nameKo).containsExactly("Hospital 100");
        assertThat(detailCalls).hasValue(100);
    }

    @Test
    @DisplayName("HIRA's holiday-closed flag closes a hospital only when the calendar identifies a holiday")
    void hospitalHonoursHolidayClosure() {
        var props =
                new PublicApiProperties("", "https://x", "https://x", "https://x", "https://x", "https://x", "https://x", "https://x");
        var dataMode = new DataModeProperties(DataModeProperties.DataMode.FIXTURE);
        var loader = new FixtureLoader(new ObjectMapper());
        var service =
                new FacilityService(
                        new PharmacyApiClient(null, props, dataMode, loader),
                        new HospitalApiClient(null, props, dataMode, loader),
                        new HospitalDetailApiClient(null, props, dataMode, loader),
                        new EmergencyRoomApiClient(null, props, dataMode, loader),
                        officialFixtureCalendar(),
                        CHILDRENS_DAY_AFTERNOON);

        Facility hospital =
                hospitalNamed(
                        service.findNearby(LAT, LNG, 1000, false, FacilityType.HOSPITAL), "강북삼성병원");

        assertThat(hospital.operation().isOpenNow()).isFalse();
        assertThat(hospital.operation().status())
                .isEqualTo(FacilityOperation.OperationStatus.CLOSED);
    }

    @Test
    @DisplayName("emergency rooms calculate caller-specific metres and leave hours unknown")
    void returnsEmergencyRoomsWithoutInventingHours() {
        List<Facility> found =
                serviceAt(FRIDAY_AFTERNOON)
                        .findNearby(LAT, LNG, 1000, false, FacilityType.EMERGENCY_ROOM);

        // The list is grid-centred and shared, so cards must use Haversine from this caller instead
        // of NMC's origin-relative distance. The operation assertion guards null != closed.
        assertThat(found).hasSize(2);
        assertThat(found).allSatisfy(f -> assertThat(f.type()).isEqualTo(FacilityType.EMERGENCY_ROOM));
        assertThat(found.get(0).id()).isEqualTo("facility:nmc-emergency:A1100006");
        assertThat(found.get(0).distanceMeters()).isBetween(900.0, 920.0);
        assertThat(found).allSatisfy(f -> {
            assertThat(f.operation().isOpenNow()).isNull();
            assertThat(f.operation().status())
                    .isEqualTo(FacilityOperation.OperationStatus.UNKNOWN);
            assertThat(f.source().dataMode()).isEqualTo(SourceRef.DataMode.FIXTURE);
        });
    }

    @Test
    @DisplayName("open_now never presents an emergency room as closed when its hours are unknown")
    void openNowExcludesUnknownEmergencyRooms() {
        List<Facility> found =
                serviceAt(FRIDAY_AFTERNOON)
                        .findNearby(LAT, LNG, 1000, true, FacilityType.EMERGENCY_ROOM);

        assertThat(found).isEmpty();
    }
}
