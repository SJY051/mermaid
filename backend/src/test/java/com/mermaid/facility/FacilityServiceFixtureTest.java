package com.mermaid.facility;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mermaid.common.FixtureLoader;
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

    private FacilityService serviceAt(Clock clock) {
        var props =
                new PublicApiProperties("", "https://x", "https://x", "https://x", "https://x", "https://x", "https://x");
        var dataMode = new DataModeProperties(DataModeProperties.DataMode.FIXTURE);
        var loader = new FixtureLoader(new ObjectMapper());
        return new FacilityService(
                new PharmacyApiClient(null, props, dataMode, loader),
                new HospitalApiClient(null, props, dataMode, loader),
                new HospitalDetailApiClient(null, props, dataMode, loader),
                new HolidayCalendar(),
                clock);
    }

    private FacilityService serviceWithDetail(
            Clock clock,
            HospitalDetailApiClient.HospitalDetail detail,
            SourceRef.DataMode listOrigin,
            SourceRef.DataMode detailOrigin) {
        var props =
                new PublicApiProperties("", "https://x", "https://x", "https://x", "https://x", "https://x", "https://x");
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
                new HolidayCalendar(),
                clock);
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
                        "https://x", "https://x", "https://x", "https://x", "https://x");
        var dataMode = new DataModeProperties(DataModeProperties.DataMode.HYBRID);
        var loader = new FixtureLoader(new ObjectMapper());
        var client = org.springframework.web.reactive.function.client.WebClient.create();
        return new FacilityService(
                new PharmacyApiClient(client, props, dataMode, loader),
                new HospitalApiClient(client, props, dataMode, loader),
                new HospitalDetailApiClient(client, props, dataMode, loader),
                new HolidayCalendar(),
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
    @DisplayName("hospital fixture hours are official, fixture-labelled, and closed during lunch")
    void hospitalsUseOfficialHoursAndLunchClosure() {
        Facility afternoon =
                serviceAt(FRIDAY_AFTERNOON)
                        .findNearby(LAT, LNG, 1000, false, FacilityType.HOSPITAL)
                        .getFirst();
        Facility lunch =
                serviceAt(FRIDAY_LUNCH)
                        .findNearby(LAT, LNG, 1000, false, FacilityType.HOSPITAL)
                        .getFirst();

        assertThat(afternoon.id()).startsWith("facility:hira:");
        assertThat(afternoon.operation().isOpenNow()).isTrue();
        assertThat(afternoon.operation().statusConfidence())
                .isEqualTo(FacilityOperation.StatusConfidence.OFFICIAL_SCHEDULE);
        assertThat(afternoon.source().dataMode()).isEqualTo(SourceRef.DataMode.FIXTURE);
        assertThat(lunch.operation().isOpenNow()).isFalse();
    }

    @Test
    @DisplayName("the radius filter drops hospitals beyond the bound, reading HIRA distance as metres (SC-002)")
    void hospitalRadiusExcludesByMetres() {
        var service = serviceAt(FRIDAY_AFTERNOON);

        // The three fixture rows sit at 903.8 m (아미나요양병원), 932.2 m (강북삼성병원), and 974.2 m
        // (서울적십자병원) from 서울시청. A 950 m radius must keep the first two and drop the third —
        // which only works if HIRA's decimal string is read as metres, not the pharmacy API's km.
        List<Facility> wide = service.findNearby(LAT, LNG, 1000, false, FacilityType.HOSPITAL);
        List<Facility> narrow = service.findNearby(LAT, LNG, 950, false, FacilityType.HOSPITAL);

        assertThat(wide).hasSize(3);
        assertThat(narrow).hasSize(2);
        assertThat(narrow).allSatisfy(f -> assertThat(f.distanceMeters()).isLessThanOrEqualTo(950.0));
        assertThat(narrow).noneMatch(f -> "서울적십자병원".equals(f.nameKo()));
        assertThat(wide)
                .filteredOn(f -> "서울적십자병원".equals(f.nameKo()))
                .singleElement()
                .satisfies(f -> assertThat(f.distanceMeters()).isBetween(974.0, 975.0));
    }

    @Test
    @DisplayName("a hospital with no published detail schedule remains unknown, not closed")
    void hospitalWithoutDetailScheduleIsUnknown() {
        var service =
                serviceWithDetail(
                        FRIDAY_AFTERNOON,
                        new HospitalDetailApiClient.HospitalDetail(
                                "YKIHO-1", Map.of(), Optional.empty(), false, false),
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
                                false),
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
                                "YKIHO-1", Map.of(1, List.of("0830", "1700")), Optional.empty(), false, false),
                        SourceRef.DataMode.LIVE,
                        SourceRef.DataMode.FIXTURE);

        Facility hospital = service.findNearby(LAT, LNG, 1000, false, FacilityType.HOSPITAL).getFirst();

        assertThat(hospital.operation().isOpenNow()).isNull();
        assertThat(hospital.source().dataMode()).isEqualTo(SourceRef.DataMode.FIXTURE);
    }

    @Test
    @DisplayName("an explicit Sunday closure wins over a contradictory Sunday time range")
    void hospitalExplicitSundayClosureWinsOverSundayHours() {
        Clock sunday = Clock.fixed(Instant.parse("2026-07-12T05:00:00Z"), ZoneId.of("UTC"));
        var service =
                serviceWithDetail(
                        sunday,
                        new HospitalDetailApiClient.HospitalDetail(
                                "YKIHO-1", Map.of(7, List.of("0830", "1700")), Optional.empty(), true, false),
                        SourceRef.DataMode.LIVE,
                        SourceRef.DataMode.LIVE);

        Facility hospital = service.findNearby(LAT, LNG, 1000, false, FacilityType.HOSPITAL).getFirst();

        assertThat(hospital.operation().isOpenNow()).isFalse();
    }

    @Test
    @DisplayName("hospital detail fan-out uses the bounded concurrency of four")
    void hospitalDetailFetchesAreBoundedAtFour() {
        var props =
                new PublicApiProperties("", "https://x", "https://x", "https://x", "https://x", "https://x", "https://x");
        var dataMode = new DataModeProperties(DataModeProperties.DataMode.FIXTURE);
        var loader = new FixtureLoader(new ObjectMapper());
        List<HospitalApiClient.RawHospital> hospitals =
                IntStream.range(0, 8)
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
        var firstFour = new CountDownLatch(4);
        var detailClient =
                new HospitalDetailApiClient(null, props, dataMode, loader) {
                    @Override
                    public HospitalDetailBatch findByYkiho(String ykiho) {
                        int inFlight = active.incrementAndGet();
                        maximum.accumulateAndGet(inFlight, Math::max);
                        firstFour.countDown();
                        try {
                            firstFour.await(1, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(e);
                        } finally {
                            active.decrementAndGet();
                        }
                        return new HospitalDetailBatch(
                                new HospitalDetail(
                                        ykiho, Map.of(1, List.of("0830", "1700")), Optional.empty(), false, false),
                                SourceRef.DataMode.LIVE);
                    }
                };
        var service =
                new FacilityService(
                        new PharmacyApiClient(null, props, dataMode, loader),
                        listClient,
                        detailClient,
                        new HolidayCalendar(),
                        FRIDAY_AFTERNOON);

        List<Facility> found = service.findNearby(LAT, LNG, 1000, false, FacilityType.HOSPITAL);

        assertThat(found).hasSize(8);
        assertThat(maximum).hasValue(4);
    }

    @Test
    @DisplayName("HIRA's holiday-closed flag closes a hospital only when the calendar identifies a holiday")
    void hospitalHonoursHolidayClosure() {
        var props =
                new PublicApiProperties("", "https://x", "https://x", "https://x", "https://x", "https://x", "https://x");
        var dataMode = new DataModeProperties(DataModeProperties.DataMode.FIXTURE);
        var loader = new FixtureLoader(new ObjectMapper());
        var service =
                new FacilityService(
                        new PharmacyApiClient(null, props, dataMode, loader),
                        new HospitalApiClient(null, props, dataMode, loader),
                        new HospitalDetailApiClient(null, props, dataMode, loader),
                        new HolidayCalendar() {
                            @Override
                            public boolean isHoliday(java.time.LocalDate date) {
                                return true;
                            }
                        },
                        FRIDAY_AFTERNOON);

        Facility hospital = service.findNearby(LAT, LNG, 1000, false, FacilityType.HOSPITAL).getFirst();

        assertThat(hospital.operation().isOpenNow()).isFalse();
        assertThat(hospital.operation().status())
                .isEqualTo(FacilityOperation.OperationStatus.CLOSED);
    }
}
