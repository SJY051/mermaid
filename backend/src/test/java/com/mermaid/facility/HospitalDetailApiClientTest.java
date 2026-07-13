package com.mermaid.facility;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mermaid.common.FixtureLoader;
import com.mermaid.common.SourceRef;
import com.mermaid.config.DataModeProperties;
import com.mermaid.config.PublicApiProperties;
import java.net.URI;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for DEV-203b's HIRA hospital-detail adapter. */
class HospitalDetailApiClientTest {

    private HospitalDetailApiClient fixtureClient() {
        return fixtureClient(null);
    }

    private HospitalDetailApiClient fixtureClient(JsonNode response) {
        return new HospitalDetailApiClient(
                null,
                new PublicApiProperties(
                        "",
                        "https://x",
                        "https://x",
                        "https://hira.example/MadmDtlInfoService2.8",
                        "https://x",
                        "https://x",
                        "https://x"),
                new DataModeProperties(DataModeProperties.DataMode.FIXTURE),
                response == null
                        ? new FixtureLoader(new ObjectMapper())
                        : new FixtureLoader(new ObjectMapper()) {
                            @Override
                            public JsonNode load(String name) {
                                return response;
                            }
                        });
    }

    @Test
    @DisplayName("reads weekly hours, lunch closure, and closure flags from the official detail fixture")
    void parsesHospitalDetailFixture() {
        HospitalDetailApiClient.HospitalDetailBatch batch = fixtureClient().findByYkiho("JDQ4MTg4MSM1MSMkMiMkMCMkMDAkMzgxMzUxIzUxIyQxIyQzIyQ4MiQyNjE4MzIjNTEjJDEjJDQjJDgz");
        HospitalDetailApiClient.HospitalDetail detail = batch.detail();

        assertThat(batch.origin()).isEqualTo(SourceRef.DataMode.FIXTURE);
        assertThat(detail.ykiho()).isEqualTo("JDQ4MTg4MSM1MSMkMiMkMCMkMDAkMzgxMzUxIzUxIyQxIyQzIyQ4MiQyNjE4MzIjNTEjJDEjJDQjJDgz");
        assertThat(detail.weekdayHours().get(1)).containsExactly("0830", "1700");
        assertThat(detail.weekdayHours().get(6)).containsExactly("0830", "1200");
        assertThat(detail.weekdayHours()).doesNotContainKey(7);
        assertThat(detail.lunchBreak()).contains(new HospitalDetailApiClient.LunchBreak(LocalTime.of(12, 30), LocalTime.of(13, 30)));
        assertThat(detail.sundayClosed()).isTrue();
        assertThat(detail.holidayClosed()).isTrue();
    }

    @Test
    @DisplayName("sends ykiho and _type=json to HIRA's versioned detail operation")
    void requestsHospitalDetailWithRequiredParameters() {
        URI uri = fixtureClient().uriFor("YKIHO-1");

        assertThat(uri.getPath()).endsWith("/getDtlInfo2.8");
        assertThat(uri.getQuery()).contains("ykiho=YKIHO-1");
        assertThat(uri.getQuery()).contains("_type=json");
    }

    @Test
    @DisplayName("leaves malformed lunchWeek unknown instead of guessing a closure")
    void doesNotGuessMalformedLunchHours() throws Exception {
        JsonNode response =
                new ObjectMapper()
                        .readTree(
                                """
                                {"response":{"header":{"resultCode":"00"},"body":{"items":{"item":
                                  {"lunchWeek":"12:30 to whenever"}
                                }}}}
                                """);

        HospitalDetailApiClient.HospitalDetail detail =
                fixtureClient(response).findByYkiho("YKIHO-1").detail();

        assertThat(detail.lunchBreak()).isEmpty();
    }

    @Test
    @DisplayName("leaves a backwards lunchWeek range unknown instead of treating it as a closure")
    void doesNotGuessBackwardsLunchHours() throws Exception {
        JsonNode response =
                new ObjectMapper()
                        .readTree(
                                """
                                {"response":{"header":{"resultCode":"00"},"body":{"items":{"item":
                                  {"lunchWeek":"13:30 ~ 12:30"}
                                }}}}
                                """);

        HospitalDetailApiClient.HospitalDetail detail =
                fixtureClient(response).findByYkiho("YKIHO-1").detail();

        assertThat(detail.lunchBreak()).isEmpty();
    }
}
