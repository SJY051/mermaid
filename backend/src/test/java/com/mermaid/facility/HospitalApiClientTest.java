package com.mermaid.facility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mermaid.common.FixtureLoader;
import com.mermaid.common.SourceRef;
import com.mermaid.config.DataModeProperties;
import com.mermaid.config.PublicApiProperties;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

        // Page 1 still has to come first — it is the only page that reports totalCount, so nothing
        // else can be scheduled until it lands. The pages after it now go together, so the order they
        // are *requested* in is deliberately not asserted; the order they come *back* in is.
        assertThat(client.requestedPages).element(0).isEqualTo(1);
        assertThat(client.requestedPages).containsExactlyInAnyOrder(1, 2, 3);
        assertThat(batch.hospitals())
                .extracting(HospitalApiClient.RawHospital::ykiho)
                .containsExactly("page-1", "page-2", "page-3");
    }

    @Test
    @DisplayName("pages after the first are fetched concurrently, in bounded flight, still in page order")
    void fetchesLaterPagesConcurrentlyAndInOrder() throws Exception {
        List<JsonNode> pages = new ArrayList<>();
        // totalCount 900 = 9 pages: one to learn the count, eight to race.
        for (int i = 1; i <= 9; i++) {
            pages.add(page(900, "page-" + i, 126.90 + i / 1000.0));
        }
        var client = new BlockingPaginatedHospitalClient(pages);

        HospitalApiClient.HospitalBatch batch = client.findNear(37.5, 126.9, 1000);

        // Sequential paging never has two calls in flight, so this is 1 before the change and 4 after.
        // An unbounded fan-out would make it 8. Both mutations turn this red.
        assertThat(client.maximumConcurrentRequests).hasValue(4);
        // Concurrency must not scramble the rows: Parallel.map emits in input order, so page 7's
        // hospital still lands after page 6's even when page 7's call returns first.
        assertThat(batch.hospitals())
                .extracting(HospitalApiClient.RawHospital::ykiho)
                .containsExactly(
                        "page-1", "page-2", "page-3", "page-4", "page-5", "page-6", "page-7", "page-8",
                        "page-9");
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

    @Test
    @DisplayName("fetches from the grid-cell centre with the radius widened by one cell")
    void fetchesGridCentredWithWidenedRadius() throws Exception {
        var client = new PaginatedHospitalClient(List.of(page(1, "h", 126.9)));

        // Caller at 37.565499, 126.977501 rounds to cell centre 37.565, 126.978.
        client.findNear(37.565499, 126.977501, 2000);

        assertThat(client.fetchedLat).isCloseTo(37.565, within(1e-9));
        assertThat(client.fetchedLng).isCloseTo(126.978, within(1e-9));
        // Removing the one-cell widening drops this to 2000 and an edge-of-cell caller silently loses
        // hospitals inside their own radius (§2-3).
        assertThat(client.fetchedRadius).isEqualTo(2000 + HospitalApiClient.GRID_MARGIN_METERS);
    }

    private static JsonNode page(int totalCount, String ykiho, double longitude) throws Exception {
        return MAPPER.readTree(
                """
                {"response":{"header":{"resultCode":"00"},"body":{"totalCount":%d,"items":{"item":
                  {"ykiho":"%s","yadmNm":"Hospital","XPos":%s,"YPos":37.5}
                }}}}
                """.formatted(totalCount, ykiho, longitude));
    }

    private static class PaginatedHospitalClient extends HospitalApiClient {

        private final List<JsonNode> pages;
        // Pages after the first are fetched from several threads at once, so this cannot be a bare
        // ArrayList any more — concurrent add() would race and lose requests.
        private final List<Integer> requestedPages = Collections.synchronizedList(new ArrayList<>());
        // Page 1 runs alone first and every page carries the same origin/radius, so a plain field is
        // enough to capture what actually reached HIRA.
        private volatile double fetchedLat;
        private volatile double fetchedLng;
        private volatile int fetchedRadius;

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
            fetchedLat = lat;
            fetchedLng = lng;
            fetchedRadius = radiusMeters;
            requestedPages.add(pageNo);
            return pages.get(pageNo - 1);
        }
    }

    /**
     * Holds every page-2-and-later call open until four of them are in flight, so the high-water mark
     * of concurrent calls is observable. With a sequential loop the fourth never arrives and the latch
     * times out, leaving the mark at 1 — which is exactly the regression this guards.
     */
    private static final class BlockingPaginatedHospitalClient extends PaginatedHospitalClient {

        private final AtomicInteger activeRequests = new AtomicInteger();
        private final AtomicInteger maximumConcurrentRequests = new AtomicInteger();
        private final CountDownLatch fourInFlight = new CountDownLatch(4);

        private BlockingPaginatedHospitalClient(List<JsonNode> pages) {
            super(pages);
        }

        @Override
        protected JsonNode fetchPage(double lat, double lng, int radiusMeters, int pageNo) {
            if (pageNo == 1) {
                return super.fetchPage(lat, lng, radiusMeters, pageNo);
            }
            int active = activeRequests.incrementAndGet();
            maximumConcurrentRequests.accumulateAndGet(active, Math::max);
            fourInFlight.countDown();
            try {
                fourInFlight.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            } finally {
                activeRequests.decrementAndGet();
            }
            return super.fetchPage(lat, lng, radiusMeters, pageNo);
        }
    }
}
