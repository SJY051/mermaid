package com.mermaid.facility;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mermaid.common.FixtureLoader;
import com.mermaid.common.SourceRef;
import com.mermaid.config.DataModeProperties;
import com.mermaid.config.PublicApiProperties;
import com.mermaid.facility.domain.DutyTable;
import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
