package com.mermaid.facility;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mermaid.common.FixtureLoader;
import com.mermaid.common.SourceRef;
import com.mermaid.config.DataModeProperties;
import com.mermaid.config.PublicApiProperties;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for DEV-203a's verified HIRA hospital-list adapter. */
class HospitalApiClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HospitalApiClient fixtureClient() {
        return fixtureClient(null);
    }

    private HospitalApiClient fixtureClient(JsonNode response) {
        var props =
                new PublicApiProperties(
                        "",
                        "https://x",
                        "https://hira.example/hospInfoServicev2",
                        "https://x",
                        "https://x",
                        "https://x",
                        "https://x");
        FixtureLoader loader =
                response == null
                        ? new FixtureLoader(MAPPER)
                        : new FixtureLoader(MAPPER) {
                            @Override
                            public JsonNode load(String name) {
                                return response;
                            }
                        };
        return new HospitalApiClient(
                null,
                props,
                new DataModeProperties(DataModeProperties.DataMode.FIXTURE),
                loader);
    }

    @Test
    @DisplayName("reads the 39-digit HIRA metre distance without losing coordinate order")
    void parsesHospitalListFixture() {
        HospitalApiClient.HospitalBatch batch = fixtureClient().findNear(37.5663, 126.9779, 1000);
        List<HospitalApiClient.RawHospital> hospitals = batch.hospitals();

        assertThat(hospitals).hasSize(3);
        assertThat(batch.origin()).isEqualTo(SourceRef.DataMode.FIXTURE);
        HospitalApiClient.RawHospital first = hospitals.get(0);
        assertThat(first.ykiho()).isNotBlank();
        assertThat(first.postcode()).isEqualTo("03181");
        assertThat(first.longitude()).isEqualTo(126.96775);
        assertThat(first.latitude()).isEqualTo(37.5684083);
        assertThat(first.distanceMeters()).isBetween(932.0, 933.0);
    }

    @Test
    @DisplayName("sends longitude as xPos, latitude as yPos, mandatory radius, and _type=json")
    void requestsHospitalListWithRequiredParameters() {
        URI uri = fixtureClient().uriFor(37.5663, 126.9779, 1000);

        assertThat(uri.getPath()).endsWith("/getHospBasisList");
        assertThat(uri.getQuery()).contains("xPos=126.9779");
        assertThat(uri.getQuery()).contains("yPos=37.5663");
        assertThat(uri.getQuery()).contains("radius=1000");
        assertThat(uri.getQuery()).contains("_type=json");
    }

    @Test
    @DisplayName("drops blank identifiers and non-finite coordinates before querying hospital detail")
    void excludesUnaddressableHospitalRows() throws Exception {
        JsonNode response =
                MAPPER.readTree(
                        """
                        {"response":{"header":{"resultCode":"00"},"body":{"items":{"item":[
                          {"ykiho":"valid","yadmNm":"Valid hospital","XPos":"126.9","YPos":37.5},
                          {"ykiho":"","yadmNm":"Blank id","XPos":"126.9","YPos":37.5},
                          {"ykiho":"infinite-longitude","yadmNm":"Infinite longitude","XPos":"Infinity","YPos":37.5},
                          {"ykiho":"nan-latitude","yadmNm":"NaN latitude","XPos":"126.9","YPos":"NaN"}
                        ]}}}}
                        """);

        List<HospitalApiClient.RawHospital> hospitals =
                fixtureClient(response).findNear(37.5, 126.9, 1000).hospitals();

        assertThat(hospitals)
                .singleElement()
                .extracting(HospitalApiClient.RawHospital::ykiho)
                .isEqualTo("valid");
    }
}
