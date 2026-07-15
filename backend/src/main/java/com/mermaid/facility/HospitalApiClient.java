package com.mermaid.facility;

import com.fasterxml.jackson.databind.JsonNode;
import com.mermaid.common.FixtureLoader;
import com.mermaid.common.Parallel;
import com.mermaid.common.PublicApiException;
import com.mermaid.common.PublicApiResponse;
import com.mermaid.common.PublicApiUriBuilder;
import com.mermaid.common.SourceRef;
import com.mermaid.config.DataModeProperties;
import com.mermaid.config.PublicApiProperties;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/** HIRA hospital list adapter (DEV-203a). */
@Slf4j
@Component
public class HospitalApiClient {

    private static final String OP_BY_LOCATION = "getHospBasisList";
    private static final int PAGE_SIZE = 100;
    // 20 pages = 2,000 hospitals, far past any real radius (the densest fixture radius is 440).
    // The cap protects one user request from a runaway/hostile totalCount turning into thousands
    // of blocking calls — the service only shows the nearest handful anyway.
    static final int MAX_PAGES = 20;
    /**
     * How many list pages may be in flight at once.
     *
     * <p>Every page after the first is fetched concurrently, because HIRA does not sort by distance
     * and we cannot stop early. Measured live at Seoul City Hall, radius 2,000 m: {@code totalCount}
     * is 717 (8 pages), page 2 carries 100 hospitals all nearer than page 1's farthest, and page 2's
     * nearest (139 m) beats page 1's (176 m). Reading only the pages we felt like reading would drop
     * the closest hospital on the map.
     *
     * <p>Reading those 8 pages one after another against four at a time, cold, alternating between the
     * two builds inside one window (2026-07-14 21:5x KST):
     *
     * <pre>
     *   list alone (limit=1)  sequential  23.9 / 35.0 / 25.0 s
     *                         concurrent   9.9 / 10.0 / 10.2 s
     *   whole open_now=true   sequential  58.5 / 30.0 / 60.9 / 60.9 s
     *                         concurrent  39.3 / 22.6 / 14.5 / 28.4 s
     * </pre>
     *
     * <p><b>Measure both builds in the same window or do not measure at all.</b> HIRA's own latency
     * drifts by more than this change is worth: the identical sequential build read the same 8 pages in
     * 20.7 s an hour earlier and 23.9-35.0 s here. Comparing a number taken now against one taken then
     * is how you conclude a speed-up made things slower — which is exactly what happened on the first
     * attempt at this measurement.
     *
     * <p>Left at four while the detail fan-out runs at 16, because the two are different sizes: the
     * list is at most a handful of pages (8 for a 2 km radius, capped at 20 by {@link #MAX_PAGES}), so
     * more concurrency saves little here, whereas the 100-call detail fan-out earns it. The evidence
     * that HIRA scales almost linearly with concurrency lives on {@code HOSPITAL_DETAIL_CONCURRENCY};
     * if the page count ever grows, raise this against a measured number rather than by feel.
     */
    private static final int LIST_PAGE_CONCURRENCY = 4;

    /** ~100 m grid the cache key rounds to (3 decimal places of latitude). */
    private static final int GRID_DECIMALS = 1000;
    /**
     * How far the fetched radius is widened so a caller anywhere in the grid cell still gets every
     * hospital inside their own circle. The cell is ~111 m × ~88 m at Seoul's latitude, so its centre
     * is at most ~73 m from any corner; 100 m clears that with margin. Without this, a caller at the
     * cell edge would be served the cell centre's smaller circle and silently miss open hospitals
     * (§2-3). Package-visible so a test can assert the fetched radius includes it.
     */
    static final int GRID_MARGIN_METERS = 100;

    private static final String FIXTURE = "hospital_list.json";

    private final WebClient publicApiWebClient;
    private final PublicApiProperties properties;
    private final DataModeProperties dataMode;
    private final FixtureLoader fixtures;
    private final Clock clock;

    @Autowired
    public HospitalApiClient(
            WebClient publicApiWebClient,
            PublicApiProperties properties,
            DataModeProperties dataMode,
            FixtureLoader fixtures,
            Clock clock) {
        this.publicApiWebClient = publicApiWebClient;
        this.properties = properties;
        this.dataMode = dataMode;
        this.fixtures = fixtures;
        this.clock = clock;
    }

    /** Compatibility constructor for focused unit tests that do not need a controllable fetch time. */
    public HospitalApiClient(
            WebClient publicApiWebClient,
            PublicApiProperties properties,
            DataModeProperties dataMode,
            FixtureLoader fixtures) {
        this(publicApiWebClient, properties, dataMode, fixtures, Clock.systemUTC());
    }

    /**
     * Hospitals near a point. HIRA requires radius in metres; xPos is longitude and yPos is latitude.
     *
     * <p>Cached on a ~100 m coordinate grid plus radius, so two people on the same block share one
     * upstream fetch instead of each waiting out the page walk. HIRA's own {@code distance} is
     * relative to the requested origin — meaningless once the fetch is grid-centred — but it is a
     * measured figure, not an authoritative one: on 100 live rows it matched our own Haversine within
     * a mean of 4 m and flipped zero radius verdicts (2026-07-14). So the fetch is centred on the grid
     * cell, {@link FacilityService} recomputes each distance from the caller's true coordinate, and
     * the fetched radius is widened by one cell ({@link #GRID_MARGIN_METERS}) so an edge-of-cell caller
     * still receives every hospital within their own circle.
     *
     * <p>The batch keeps per-fetch provenance, so a cached hybrid fallback remains visibly fixture data.
     */
    @Cacheable(
            // v2: the key changed from exact coordinates to a grid cell. A v1 entry would answer a
            // grid-cell key it was never stored under, so a new name retires every stale entry at once.
            value = "hospitalsNear.v2",
            key =
                    "T(java.lang.Math).round(#lat * 1000) + ':' + T(java.lang.Math).round(#lng * 1000) + ':' + #radiusMeters")
    public HospitalBatch findNear(double lat, double lng, int radiusMeters) {
        if (dataMode.isFixtureOnly()) {
            return fixtureBatch();
        }
        if (!properties.isConfigured()) {
            log.warn("DATA_GO_KR_SERVICE_KEY is not set — falling back to hospital fixture data");
            return fixtureBatch();
        }

        // Fetch from the cell centre, not this caller's exact point, so every caller the key rounds
        // together sees the identical batch; widen the radius by one cell so none of them loses a
        // hospital that sits inside their circle but outside the centre's.
        double centreLat = Math.round(lat * GRID_DECIMALS) / (double) GRID_DECIMALS;
        double centreLng = Math.round(lng * GRID_DECIMALS) / (double) GRID_DECIMALS;
        int fetchRadius = radiusMeters + GRID_MARGIN_METERS;
        try {
            return liveBatch(centreLat, centreLng, fetchRadius);
        } catch (Exception e) {
            if (dataMode.allowsFallback()) {
                log.warn("hospital lookup failed, falling back to fixture: {}", e.getMessage());
                return fixtureBatch();
            }
            throw new PublicApiException("Hospital lookup failed near " + lat + "," + lng, e);
        }
    }

    private HospitalBatch fixtureBatch() {
        // Fixture mode deliberately reads one captured page: query parameters are ignored offline,
        // so paging would repeat these same rows and fabricate duplicates.
        return new HospitalBatch(
                parsePage(fixtures.load(FIXTURE)).hospitals(), SourceRef.DataMode.FIXTURE, Instant.now(clock));
    }

    /** Builds the HIRA list request with its mandatory radius and `_type=json` parameter. */
    URI uriFor(double lat, double lng, int radiusMeters) {
        return uriFor(lat, lng, radiusMeters, 1);
    }

    /** One bounded HIRA page; the live lookup follows totalCount until every page is collected. */
    URI uriFor(double lat, double lng, int radiusMeters, int pageNo) {
        return PublicApiUriBuilder.of(properties.hospitalBaseUrl(), OP_BY_LOCATION)
                .serviceKey(properties.serviceKey())
                .param("xPos", lng)
                .param("yPos", lat)
                .param("radius", radiusMeters)
                .param("numOfRows", PAGE_SIZE)
                .param("pageNo", pageNo)
                .param("_type", "json")
                .build();
    }

    private HospitalBatch liveBatch(double lat, double lng, int radiusMeters) {
        HospitalPage first = parsePage(fetchPage(lat, lng, radiusMeters, 1));
        List<RawHospital> hospitals = new ArrayList<>(first.hospitals());
        // Compute the page count in floating point so a maliciously large totalCount cannot overflow
        // the loop bound, then clamp to MAX_PAGES.
        int pages = (int) Math.ceil(first.totalCount() / (double) PAGE_SIZE);
        int lastPage = Math.min(pages, MAX_PAGES);
        if (pages > MAX_PAGES) {
            log.warn(
                    "HIRA reported {} hospitals ({} pages); capping at {} pages",
                    first.totalCount(),
                    pages,
                    MAX_PAGES);
        }
        // Page 1 has to land first — it carries the totalCount that tells us how many more there are.
        // The rest are independent, so they go together. Parallel.map emits in input order, so the
        // rows stay in page order and the fixtures/tests stay deterministic. parsePage always returns
        // a list (empty at worst), never null: a null here would be dropped and shift every later page.
        Parallel.map(
                        IntStream.rangeClosed(2, lastPage).boxed().toList(),
                        LIST_PAGE_CONCURRENCY,
                        pageNo -> parsePage(fetchPage(lat, lng, radiusMeters, pageNo)).hospitals())
                .forEach(hospitals::addAll);

        return new HospitalBatch(hospitals, SourceRef.DataMode.LIVE, Instant.now(clock));
    }

    /** Separated for page-level tests; production calls HIRA with the page-specific URI. */
    protected JsonNode fetchPage(double lat, double lng, int radiusMeters, int pageNo) {
        return publicApiWebClient
                .get()
                .uri(uriFor(lat, lng, radiusMeters, pageNo))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
    }

    /** Parses one HIRA list page into normalized rows plus its upstream total. */
    private HospitalPage parsePage(JsonNode raw) {
        PublicApiResponse response = PublicApiResponse.of(raw).requireOk();

        List<RawHospital> hospitals = new ArrayList<>();
        for (JsonNode row : response.items()) {
            String ykiho = PublicApiResponse.text(row, "ykiho");
            Double longitude = PublicApiResponse.number(row, "XPos");
            Double latitude = PublicApiResponse.number(row, "YPos");
            if (ykiho == null
                    || ykiho.isBlank()
                    || longitude == null
                    || !Double.isFinite(longitude)
                    || latitude == null
                    || !Double.isFinite(latitude)) {
                // Without HIRA's stable id or a coordinate the facility cannot be placed, selected,
                // or queried for details. Dropping it is more honest than inventing either value.
                continue;
            }
            hospitals.add(
                    new RawHospital(
                            ykiho,
                            PublicApiResponse.text(row, "yadmNm"),
                            PublicApiResponse.text(row, "addr"),
                            PublicApiResponse.text(row, "postNo"),
                            PublicApiResponse.text(row, "telno"),
                            // clCd is the stable 종별코드; it arrives as a string ("01") or a number
                            // (28), so read it as text. clCdNm is only a display label — not a filter key.
                            PublicApiResponse.text(row, "clCd"),
                            latitude,
                            longitude,
                            PublicApiResponse.number(row, "distance")));
        }
        return new HospitalPage(hospitals, response.totalCount());
    }

    /**
     * A single HIRA list record; official hours arrive only in DEV-203b's detail adapter.
     *
     * @param classificationCode HIRA 종별코드 (`clCd`), the stable facility-type key — e.g. {@code 28}
     *     is 요양병원. Filter on this, never on the display label {@code clCdNm}, which HIRA can
     *     re-word without notice.
     */
    public record RawHospital(
            String ykiho,
            String nameKo,
            String addressKo,
            String postcode,
            String phone,
            String classificationCode,
            double latitude,
            double longitude,
            Double distanceMeters) {}

    /** Hospital rows plus the source of this fetch, so hybrid fallback is never labelled live. */
    public record HospitalBatch(List<RawHospital> hospitals, SourceRef.DataMode origin, Instant retrievedAt) {
        public HospitalBatch(List<RawHospital> hospitals, SourceRef.DataMode origin) {
            this(hospitals, origin, Instant.now());
        }
    }

    private record HospitalPage(List<RawHospital> hospitals, int totalCount) {}
}
