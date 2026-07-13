package com.mermaid.facility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    private FacilityService serviceAt(Clock clock) {
        var props =
                new PublicApiProperties("", "https://x", "https://x", "https://x", "https://x", "https://x", "https://x");
        var dataMode = new DataModeProperties(DataModeProperties.DataMode.FIXTURE);
        var loader = new FixtureLoader(new ObjectMapper());
        var client = new PharmacyApiClient(null, props, dataMode, loader);
        return new FacilityService(client, new HolidayCalendar(), clock);
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
        var client =
                new PharmacyApiClient(
                        org.springframework.web.reactive.function.client.WebClient.create(),
                        props,
                        dataMode,
                        loader);
        return new FacilityService(client, new HolidayCalendar(), clock);
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
    @DisplayName("hospital search refuses rather than returning an empty list that reads as 'none nearby'")
    void hospitalsAreNotImplemented() {
        // This test used to assert the empty list and call it "not lying". It was the lie: a caller
        // cannot tell "no hospitals here" from "we never looked". 501 NOT_IMPLEMENTED tells them.
        assertThatThrownBy(
                        () -> serviceAt(FRIDAY_AFTERNOON)
                                .findNearby(LAT, LNG, 1000, false, FacilityType.HOSPITAL))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("DEV-203");
    }
}
