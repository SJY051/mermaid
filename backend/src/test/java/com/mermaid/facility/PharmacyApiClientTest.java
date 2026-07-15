package com.mermaid.facility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mermaid.common.FixtureLoader;
import com.mermaid.common.PublicApiException;
import com.mermaid.common.SourceRef;
import com.mermaid.config.DataModeProperties;
import com.mermaid.config.PublicApiProperties;
import com.mermaid.facility.domain.DutyTable;
import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/** Test checklist for DEV-202's official pharmacy weekly-timetable lookup. */
class PharmacyApiClientTest {

    private PharmacyApiClient fixtureClient() {
        var props =
                new PublicApiProperties(
                        "",
                        "https://x",
                        "https://x",
                        "https://x",
                        "https://x",
                        "https://x",
                        "https://x");
        return new PharmacyApiClient(
                null,
                props,
                new DataModeProperties(DataModeProperties.DataMode.FIXTURE),
                new FixtureLoader(new ObjectMapper()));
    }

    @Test
    @DisplayName("reads mixed string and numeric duty-time fields for the requested HPID")
    void readsOfficialWeeklyHoursFromFixture() {
        DutyTable table = fixtureClient().weeklyHours("C1110693");

        assertThat(table.byDay().get(1)).containsExactly("0900", "1900");
        assertThat(table.byDay().get(6)).containsExactly("1000", "1400");
        assertThat(table.byDay()).doesNotContainKey(7);
        assertThat(table.byDay()).doesNotContainKey(8);
        assertThat(table.origin()).isEqualTo(SourceRef.DataMode.FIXTURE);
    }

    @Test
    @DisplayName("a fixture-served batch is tagged FIXTURE, so the card never claims live")
    void fixtureBatchCarriesFixtureOrigin() {
        var batch = fixtureClient().findNear(37.5663, 126.9779);

        assertThat(batch.pharmacies()).isNotEmpty();
        assertThat(batch.origin()).isEqualTo(SourceRef.DataMode.FIXTURE);
    }

    @Test
    @DisplayName("does not give another pharmacy the fixture timetable")
    void ignoresFixtureForDifferentHpid() {
        assertThat(fixtureClient().weeklyHours("C1107705").byDay()).isEmpty();
    }

    @Test
    @DisplayName("requests getParmacyBassInfoInqire with HPID and _type=json")
    void requestsOfficialTimetableEndpoint() {
        URI uri = fixtureClient().weeklyHoursUriFor("C1110693");

        assertThat(uri.getPath()).endsWith("/getParmacyBassInfoInqire");
        assertThat(uri.getQuery()).contains("HPID=C1110693");
        assertThat(uri.getQuery()).contains("_type=json");
    }

    @Test
    @DisplayName("basisDetail reads identity, coordinates and the timetable for the requested HPID")
    void basisDetailReadsFullRecord() {
        var batch = fixtureClient().basisDetail("C1110693");

        assertThat(batch.origin()).isEqualTo(SourceRef.DataMode.FIXTURE);
        var detail = batch.detail();
        assertThat(detail).isNotNull();
        assertThat(detail.name()).isEqualTo("청실약국");
        assertThat(detail.address()).contains("무교로");
        assertThat(detail.phone()).isEqualTo("02-3789-6953");
        // wgs84Lat/wgs84Lon on the basis endpoint, not the location endpoint's latitude/longitude.
        assertThat(detail.latitude()).isEqualTo(37.5672818668855);
        assertThat(detail.longitude()).isEqualTo(126.978921749794);
        assertThat(detail.weeklyHours().byDay().get(1)).containsExactly("0900", "1900");
        assertThat(detail.weeklyHours().byDay().get(6)).containsExactly("1000", "1400");
    }

    @Test
    @DisplayName("basisDetail returns a null detail for an HPID the fixture does not carry")
    void basisDetailMissingHpidIsNull() {
        var batch = fixtureClient().basisDetail("C1107705");

        assertThat(batch.detail()).isNull();
        assertThat(batch.origin()).isEqualTo(SourceRef.DataMode.FIXTURE);
    }

    @Test
    @DisplayName("a matching row without a name or coordinates is rejected, not a partial 200 card")
    void basisDetailRejectsIncompleteRow() throws Exception {
        // The row carries the requested hpid but no dutyName and no wgs84Lat/Lon. Returning it would
        // build a verified-looking Facility with null identity/coordinates — reject it as not found.
        String envelope =
                """
                {"response":{"header":{"resultCode":"00","resultMsg":"OK"},
                 "body":{"items":{"item":{"hpid":"C1110693","dutyAddr":"서울 중구","dutyTel1":"02-000-0000"}}}}}
                """;
        JsonNode raw = new ObjectMapper().readTree(envelope);

        assertThat(fixtureClient().parseBasisDetail(raw, "C1110693", SourceRef.DataMode.LIVE)).isNull();

        // Name and coordinates present, but no address: still rejected (address is a required field).
        String noAddress =
                """
                {"response":{"header":{"resultCode":"00","resultMsg":"OK"},
                 "body":{"items":{"item":{"hpid":"C1110693","dutyName":"청실약국","dutyTel1":"02-000-0000",
                   "wgs84Lat":37.56,"wgs84Lon":126.97}}}}}
                """;
        assertThat(
                        fixtureClient()
                                .parseBasisDetail(
                                        new ObjectMapper().readTree(noAddress),
                                        "C1110693",
                                        SourceRef.DataMode.LIVE))
                .isNull();

        // A whitespace-only address is equally unusable for a person trying to find the pharmacy.
        String blankAddress =
                """
                {"response":{"header":{"resultCode":"00","resultMsg":"OK"},
                 "body":{"items":{"item":{"hpid":"C1110693","dutyName":"청실약국","dutyAddr":"  ",
                   "dutyTel1":"02-000-0000","wgs84Lat":37.56,"wgs84Lon":126.97}}}}}
                """;
        assertThat(
                        fixtureClient()
                                .parseBasisDetail(
                                        new ObjectMapper().readTree(blankAddress),
                                        "C1110693",
                                        SourceRef.DataMode.LIVE))
                .isNull();
    }

    @Test
    @DisplayName("hybrid: a live outage for an hpid the fixture lacks fails unavailable, not a cached 404")
    void hybridDetailOutageForUnknownHpidIsUnavailable() {
        // pharmacyBaseUrl is a closed port, so the live call fails; HYBRID would normally fall back to
        // the fixture. The fixture holds only C1110693, so an outage for any other well-formed hpid
        // must surface as unavailable (retryable, not cached) rather than a 404 that outlives it.
        var props =
                new PublicApiProperties(
                        "decoding-key", "http://127.0.0.1:1", "https://x", "https://x", "https://x",
                        "https://x", "https://x");
        var client =
                new PharmacyApiClient(
                        WebClient.create(),
                        props,
                        new DataModeProperties(DataModeProperties.DataMode.HYBRID),
                        new FixtureLoader(new ObjectMapper()));

        assertThatThrownBy(() -> client.basisDetail("C9999999")).isInstanceOf(PublicApiException.class);

        // The one pharmacy the fixture does hold still falls back to fixture data.
        var batch = client.basisDetail("C1110693");
        assertThat(batch.detail()).isNotNull();
        assertThat(batch.origin()).isEqualTo(SourceRef.DataMode.FIXTURE);
    }

    @Test
    @DisplayName("a live detail failure drops the URI-bearing cause before it can be logged (§2-7)")
    void basisDetailLiveFailureHasNoCause() {
        // pharmacyBaseUrl points at a closed port; in LIVE mode there is no fixture fallback, so the
        // connection error becomes a PublicApiException. Re-adding the WebClient cause (which carries
        // the request URI and its serviceKey) turns hasNoCause() red.
        var props =
                new PublicApiProperties(
                        "decoding-key", "http://127.0.0.1:1", "https://x", "https://x", "https://x",
                        "https://x", "https://x");
        var client =
                new PharmacyApiClient(
                        WebClient.create(),
                        props,
                        new DataModeProperties(DataModeProperties.DataMode.LIVE),
                        new FixtureLoader(new ObjectMapper()));

        assertThatThrownBy(() -> client.basisDetail("C1110693"))
                .isInstanceOf(PublicApiException.class)
                .hasMessage("Pharmacy detail lookup failed for C1110693")
                .hasNoCause();
    }
}
