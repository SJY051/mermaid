package com.mermaid.facility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mermaid.common.PublicApiException;
import com.mermaid.config.DataModeProperties;
import com.mermaid.config.PublicApiProperties;
import java.net.URI;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HolidayApiClientTest {

    private static final PublicApiProperties PROPERTIES =
            new PublicApiProperties(
                    "",
                    "https://x",
                    "https://holiday.example",
                    "https://x",
                    "https://x",
                    "https://x",
                    "https://x",
                    "https://x");

    private HolidayApiClient fixtureClient() {
        return new HolidayApiClient(
                null, PROPERTIES, new DataModeProperties(DataModeProperties.DataMode.FIXTURE));
    }

    @Test
    @DisplayName("reads official 2026 public holidays from the captured XML fixture")
    void readsOfficialHolidaysFromXmlFixture() {
        HolidayCalendar calendar = new HolidayCalendar(fixtureClient());

        assertThat(calendar.isHoliday(LocalDate.of(2026, 5, 5))).isTrue();
        assertThat(calendar.isHoliday(LocalDate.of(2026, 10, 5))).isTrue();
        // The official provider marks both as Y. Do not replace the response with our own holiday policy.
        assertThat(calendar.isHoliday(LocalDate.of(2026, 5, 1))).isTrue();
        assertThat(calendar.isHoliday(LocalDate.of(2026, 7, 17))).isTrue();
        assertThat(calendar.isHoliday(LocalDate.of(2026, 7, 15))).isFalse();
    }

    @Test
    @DisplayName("requests the XML public-holiday operation for a whole solar year")
    void buildsOfficialHolidayUri() {
        URI uri = fixtureClient().uriFor(2026);

        assertThat(uri.getPath()).endsWith("/getRestDeInfo");
        assertThat(uri.getQuery()).contains("solYear=2026");
        assertThat(uri.getQuery()).contains("pageNo=1");
        assertThat(uri.getQuery()).contains("numOfRows=100");
        assertThat(uri.getQuery()).doesNotContain("_type");
    }

    @Test
    @DisplayName("does not treat non-holiday special days as public holidays")
    void excludesNonHolidayRows() {
        var year =
                fixtureClient()
                        .parse(
                                """
                                <response><header><resultCode>00</resultCode></header><body><items>
                                  <item><isHoliday>N</isHoliday><locdate>20260715</locdate></item>
                                  <item><isHoliday>Y</isHoliday><locdate>20260815</locdate></item>
                                </items></body></response>
                                """);

        assertThat(year.isHoliday(LocalDate.of(2026, 7, 15))).isFalse();
        assertThat(year.isHoliday(LocalDate.of(2026, 8, 15))).isTrue();
    }

    @Test
    @DisplayName("rejects XML doctypes instead of resolving external entities")
    void rejectsXmlDoctypes() {
        assertThatThrownBy(
                        () ->
                                fixtureClient()
                                        .parse(
                                                """
                                                <!DOCTYPE response [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
                                                <response><header><resultCode>00</resultCode></header></response>
                                                """))
                .isInstanceOf(PublicApiException.class);
    }

    @Test
    @DisplayName("does not replace a failed live calendar with a fixture decision")
    void liveFailureDoesNotMixFixtureCalendarWithLiveFacilities() {
        var client =
                new HolidayApiClient(
                        null,
                        configuredProperties(),
                        new DataModeProperties(DataModeProperties.DataMode.HYBRID)) {
                    @Override
                    protected String fetch(int year) {
                        throw new IllegalStateException("upstream unavailable");
                    }
                };

        assertThatThrownBy(() -> client.holidaysFor(2026))
                .isInstanceOf(PublicApiException.class)
                .hasNoCause();
    }

    @Test
    @DisplayName("does not invent fixture dates for a year that was not captured")
    void missingFixtureYearFailsLoudly() {
        assertThatThrownBy(() -> fixtureClient().holidaysFor(2027))
                .isInstanceOf(PublicApiException.class);
    }

    @Test
    @DisplayName("fixture and live calendar values use different Redis cache keys")
    void fixtureAndLiveCalendarsDoNotShareCacheKeys() {
        var live =
                new HolidayApiClient(
                        null,
                        configuredProperties(),
                        new DataModeProperties(DataModeProperties.DataMode.LIVE));

        assertThat(fixtureClient().cacheKeyFor(2026)).isNotEqualTo(live.cacheKeyFor(2026));
    }

    @Test
    @DisplayName("a keyless hybrid fixture fallback cannot populate a configured hybrid live cache")
    void keylessAndConfiguredHybridCalendarsDoNotShareCacheKeys() {
        var keylessHybrid =
                new HolidayApiClient(
                        null,
                        PROPERTIES,
                        new DataModeProperties(DataModeProperties.DataMode.HYBRID));
        var configuredHybrid =
                new HolidayApiClient(
                        null,
                        configuredProperties(),
                        new DataModeProperties(DataModeProperties.DataMode.HYBRID));

        assertThat(keylessHybrid.cacheKeyFor(2026))
                .isNotEqualTo(configuredHybrid.cacheKeyFor(2026));
    }

    private static PublicApiProperties configuredProperties() {
        return new PublicApiProperties(
                "decoding-key",
                "https://x",
                "https://holiday.example",
                "https://x",
                "https://x",
                "https://x",
                "https://x",
                "https://x");
    }
}
