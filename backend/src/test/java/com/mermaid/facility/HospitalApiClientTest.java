package com.mermaid.facility;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mermaid.common.FixtureLoader;
import com.mermaid.common.SourceRef;
import com.mermaid.config.DataModeProperties;
import com.mermaid.config.PublicApiProperties;
import java.net.URI;
import java.util.ArrayList;
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
        // HIRA mixes JSON strings and numbers for clCd in one response; downstream policy compares
        // the normalised code, never the changeable clCdNm label.
        assertThat(hospitals)
                .extracting(HospitalApiClient.RawHospital::classificationCode)
                .containsExactly("01", "11", "28");
    }

    @Test
    @DisplayName("sends longitude as xPos, latitude as yPos, mandatory radius, and _type=json")
    void requestsHospitalListWithRequiredParameters() {
        URI uri = fixtureClient().uriFor(37.5663, 126.9779, 1000);

        assertThat(uri.getPath()).endsWith("/getHospBasisList");
        assertThat(uri.getQuery()).contains("xPos=126.9779");
        assertThat(uri.getQuery()).contains("yPos=37.5663");
        assertThat(uri.getQuery()).contains("radius=1000");
        assertThat(uri.getQuery()).contains("numOfRows=100");
        assertThat(uri.getQuery()).contains("pageNo=1");
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

    @Test
    @DisplayName("fetches every HIRA page before the service filters and sorts hospitals")
    void fetchesAllHospitalPages() throws Exception {
        var client =
                new PaginatedHospitalClient(
                        List.of(
                                page(201, "page-1", 126.91),
                                page(201, "page-2", 126.92),
                                page(201, "page-3", 126.93)));

        HospitalApiClient.HospitalBatch batch = client.findNear(37.5, 126.9, 1000);

        assertThat(client.requestedPages).containsExactly(1, 2, 3);
        assertThat(batch.hospitals())
                .extracting(HospitalApiClient.RawHospital::ykiho)
                .containsExactly("page-1", "page-2", "page-3");
    }

    @Test
    @DisplayName("stops paging at the hard cap even when HIRA reports far more pages")
    void stopsPagingAtMaxPages() throws Exception {
        List<JsonNode> manyPages = new ArrayList<>();
        for (int i = 1; i <= HospitalApiClient.MAX_PAGES + 5; i++) {
            // totalCount 100,000 = 1,000 pages; the cap must stop us long before then.
            manyPages.add(page(100_000, "page-" + i, 126.90 + i / 1000.0));
        }
        var client = new PaginatedHospitalClient(manyPages);

        HospitalApiClient.HospitalBatch batch = client.findNear(37.5, 126.9, 1000);

        assertThat(client.requestedPages).hasSize(HospitalApiClient.MAX_PAGES);
        assertThat(client.requestedPages)
                .contains(HospitalApiClient.MAX_PAGES)
                .doesNotContain(HospitalApiClient.MAX_PAGES + 1);
        assertThat(batch.hospitals()).hasSize(HospitalApiClient.MAX_PAGES);
    }

    private static JsonNode page(int totalCount, String ykiho, double longitude) throws Exception {
        return MAPPER.readTree(
                """
                {"response":{"header":{"resultCode":"00"},"body":{"totalCount":%d,"items":{"item":
                  {"ykiho":"%s","yadmNm":"Hospital","XPos":%s,"YPos":37.5}
                }}}}
                """.formatted(totalCount, ykiho, longitude));
    }

    private static final class PaginatedHospitalClient extends HospitalApiClient {

        private final List<JsonNode> pages;
        private final List<Integer> requestedPages = new ArrayList<>();

        private PaginatedHospitalClient(List<JsonNode> pages) {
            super(
                    null,
                    new PublicApiProperties(
                            "configured-key",
                            "https://x",
                            "https://x",
                            "https://hira.example/hospInfoServicev2",
                            "https://x",
                            "https://x",
                            "https://x",
                            "https://x"),
                    new DataModeProperties(DataModeProperties.DataMode.LIVE),
                    new FixtureLoader(MAPPER));
            this.pages = pages;
        }

        @Override
        protected JsonNode fetchPage(double lat, double lng, int radiusMeters, int pageNo) {
            requestedPages.add(pageNo);
            return pages.get(pageNo - 1);
        }
    }
}
