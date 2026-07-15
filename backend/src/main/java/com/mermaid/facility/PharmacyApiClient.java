package com.mermaid.facility;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mermaid.common.FixtureLoader;
import com.mermaid.common.GeoUtils;
import com.mermaid.common.Parallel;
import com.mermaid.common.PublicApiException;
import com.mermaid.common.PublicApiResponse;
import com.mermaid.common.PublicApiUriBuilder;
import com.mermaid.common.SourceRef;
import com.mermaid.config.DataModeProperties;
import com.mermaid.config.HiraPharmacyProperties;
import com.mermaid.config.PublicApiProperties;
import com.mermaid.facility.domain.DutyTable;
import java.io.StringReader;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Pharmacy directory adapter: HIRA first (data.go.kr 15001673), NMC fallback (15000576).
 *
 * <p>HIRA's {@code getParmacyBasisList} accepts the caller's radius and returns stable {@code ykiho}
 * identifiers that also work with the existing HIRA detail adapter. The response is not sorted by
 * distance: at Seoul City Hall, a live 1 km request returned 131 rows and page 2 contained nearer
 * pharmacies than page 1 (verified 2026-07-16). Every page must be read before ranking.
 *
 * <p>What the NMC fallback actually returns (verified 2026-07-10, {@code fixtures/pharmacy.json}) —
 * this differs from what every write-up about it says:
 *
 * <ul>
 *   <li>The operation is {@code getParmacyLcinfoInqire}. Yes, "Parmacy"; the typo is theirs.
 *   <li>It takes {@code WGS84_LON}/{@code WGS84_LAT} and <b>no radius</b>. Results come back
 *       distance-sorted and we clip them ourselves.
 *   <li>It returns <b>no weekly timetable</b>. Only {@code startTime}/{@code endTime}, whose meaning
 *       ("today" or "typical"?) is still unconfirmed. The real {@code dutyTime1s}…{@code dutyTime6c}
 *       table lives in {@code getParmacyBassInfoInqire}, one call per pharmacy.
 *   <li>{@code distance} is in <b>kilometres</b> (0.14 = 140 m), and the coordinate fields are
 *       {@code latitude}/{@code longitude}, not {@code wgs84Lat}/{@code wgs84Lon}.
 * </ul>
 *
 * <p>NMC's development quota is <b>1,000 calls/day</b>. HIRA has more headroom, but a 10 km Seoul
 * request still spans four 1,000-row pages, so {@link Cacheable} and {@link DataModeProperties} are
 * not optional here.
 */
@Slf4j
@Component
public class PharmacyApiClient {

    private static final String DEFAULT_HIRA_BASE_URL =
            "https://apis.data.go.kr/B551182/pharmacyInfoService";
    private static final String HIRA_OP_BY_LOCATION = "getParmacyBasisList";
    private static final String NMC_OP_BY_LOCATION = "getParmacyLcinfoInqire";
    private static final String OP_BY_BASIS = "getParmacyBassInfoInqire";

    /** Live HIRA accepted and returned all 131 rows for a 1,000-row request on 2026-07-16. */
    private static final int PAGE_SIZE = 1000;

    /** 20,000 rows is well above the 3,410 pharmacies observed within 10 km of Seoul City Hall. */
    private static final int MAX_PAGES = 20;
    private static final int LIST_PAGE_CONCURRENCY = 4;
    private static final int DEFAULT_RADIUS_METERS = 10_000;
    private static final int GRID_DECIMALS = 1000;
    private static final int GRID_MARGIN_METERS = 100;
    private static final String FIXTURE = "pharmacy.json";
    private static final String BASIS_FIXTURE = "pharmacy_basis.json";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final WebClient publicApiWebClient;
    private final PublicApiProperties properties;
    private final HiraPharmacyProperties hiraProperties;
    private final DataModeProperties dataMode;
    private final FixtureLoader fixtures;

    @Autowired
    public PharmacyApiClient(
            WebClient publicApiWebClient,
            PublicApiProperties properties,
            HiraPharmacyProperties hiraProperties,
            DataModeProperties dataMode,
            FixtureLoader fixtures) {
        this.publicApiWebClient = publicApiWebClient;
        this.properties = properties;
        this.hiraProperties = hiraProperties;
        this.dataMode = dataMode;
        this.fixtures = fixtures;
    }

    /** Compatibility constructor for focused tests and existing direct callers. */
    public PharmacyApiClient(
            WebClient publicApiWebClient,
            PublicApiProperties properties,
            DataModeProperties dataMode,
            FixtureLoader fixtures) {
        this(
                publicApiWebClient,
                properties,
                new HiraPharmacyProperties(DEFAULT_HIRA_BASE_URL),
                dataMode,
                fixtures);
    }

    /**
     * Pharmacies near a point, tagged with where they actually came from.
     *
     * <p>Cached on the rounded coordinate: two people on the same street corner share one upstream
     * call. Three decimal places is roughly a 100 m grid. HIRA pages are not distance-sorted; the
     * service ranks the complete batch after recomputing distance from the caller.
     *
     * <p>The result carries a {@link SourceRef.DataMode} because provenance is per-fetch, not
     * per-app-mode: in {@code hybrid} a government outage falls back to fixtures, and §2-14 requires
     * that fixture data be labelled fixture on the card — deciding it from the app-wide switch would
     * stamp a fallback row {@code live}. The origin travels with the value, so a cached fallback stays
     * honestly labelled.
     */
    @Cacheable(
            value = "pharmaciesNear.v5",
            key =
                    "T(java.lang.Math).round(#lat * 1000) + ':' + T(java.lang.Math).round(#lng * 1000) + ':10000'")
    public PharmacyBatch findNear(double lat, double lng) {
        return findNear(lat, lng, DEFAULT_RADIUS_METERS);
    }

    @Cacheable(
            // v5 also carries the real fetch time. Reusing v4 would restamp a cached response as if
            // it had just been retrieved; older versions can also be incomplete or radius-mismatched.
            value = "pharmaciesNear.v5",
            key =
                    "T(java.lang.Math).round(#lat * 1000) + ':' + T(java.lang.Math).round(#lng * 1000) + ':' + #radiusMeters")
    public PharmacyBatch findNear(double lat, double lng, int radiusMeters) {
        if (dataMode.isFixtureOnly()) {
            return fixtureBatch();
        }
        if (!properties.isConfigured()) {
            log.warn("DATA_GO_KR_SERVICE_KEY is not set — falling back to fixture data");
            return fixtureBatch();
        }

        try {
            double centreLat = Math.round(lat * GRID_DECIMALS) / (double) GRID_DECIMALS;
            double centreLng = Math.round(lng * GRID_DECIMALS) / (double) GRID_DECIMALS;
            PharmacyBatch primary =
                    hiraBatch(
                            centreLat,
                            centreLng,
                            Math.addExact(radiusMeters, GRID_MARGIN_METERS));
            log.debug("pharmacy_lookup_completed provider=HIRA count={}", primary.pharmacies().size());
            return primary;
        } catch (Exception ignored) {
            // No exception text: WebClient failures may include a URI containing ServiceKey.
            log.warn("pharmacy_lookup_failed provider=HIRA action=TRY_NMC_FALLBACK");
        }

        try {
            PharmacyBatch fallback = nmcBatch(lat, lng, radiusMeters);
            log.debug(
                    "pharmacy_lookup_completed provider=NMC fallback=true count={}",
                    fallback.pharmacies().size());
            return fallback;
        } catch (Exception ignored) {
            if (dataMode.allowsFallback()) {
                // No exception text: WebClient failures may include a URI containing serviceKey.
                log.warn("pharmacy_lookup_failed provider=NMC action=USE_FIXTURE");
                return fixtureBatch();
            }
            // No cause and no coordinates: neither secrets nor precise caller location reaches logs.
            throw new PublicApiException("Both pharmacy directory providers failed");
        }
    }

    /**
     * Compatibility path for {@code open_now=true} searches.
     *
     * <p>The HIRA pharmacy directory publishes no weekly schedule and its {@code ykiho} cannot be
     * used as NMC's {@code HPID}. Keep NMC as this query's source until a separately reviewed hours
     * adapter can replace it; otherwise every HIRA row without hospital-detail hours becomes unknown
     * and is silently removed by the open-now filter.
     */
    @Cacheable(
            value = "pharmaciesNearOpenNow.v1",
            key =
                    "T(java.lang.Math).round(#lat * 1000) + ':' + T(java.lang.Math).round(#lng * 1000) + ':' + #radiusMeters")
    public PharmacyBatch findNearForOpenNow(double lat, double lng, int radiusMeters) {
        if (dataMode.isFixtureOnly()) {
            return fixtureBatch();
        }
        if (!properties.isConfigured()) {
            log.warn("DATA_GO_KR_SERVICE_KEY is not set — falling back to fixture data");
            return fixtureBatch();
        }

        try {
            PharmacyBatch batch = nmcBatch(lat, lng, radiusMeters);
            log.debug(
                    "pharmacy_lookup_completed provider=NMC open_now=true count={}",
                    batch.pharmacies().size());
            return batch;
        } catch (Exception ignored) {
            if (dataMode.allowsFallback()) {
                // No exception text: WebClient failures may include a URI containing serviceKey.
                log.warn("pharmacy_lookup_failed provider=NMC open_now=true action=USE_FIXTURE");
                return fixtureBatch();
            }
            throw new PublicApiException("NMC open-now pharmacy directory failed");
        }
    }

    private PharmacyBatch nmcBatch(double lat, double lng, int radiusMeters) {
        JsonNode raw =
                publicApiWebClient
                        .get()
                        .uri(uriFor(lat, lng))
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .block();
        List<RawPharmacy> nmcPharmacies = parseNmc(raw);
        requireNmcLocationQuality(nmcPharmacies, lat, lng, radiusMeters);
        return new PharmacyBatch(
                nmcPharmacies, SourceRef.DataMode.LIVE, PharmacyProvider.NMC);
    }

    private PharmacyBatch fixtureBatch() {
        return new PharmacyBatch(
                parseNmc(fixtures.load(FIXTURE)),
                SourceRef.DataMode.FIXTURE,
                PharmacyProvider.NMC);
    }

    private PharmacyBatch hiraBatch(double lat, double lng, int radiusMeters) {
        HiraPage first = parseHiraPage(fetchHiraPage(lat, lng, radiusMeters, 1));
        List<RawPharmacy> pharmacies = new ArrayList<>(first.pharmacies());
        int pages = (int) Math.ceil(first.totalCount() / (double) PAGE_SIZE);
        int lastPage = Math.min(pages, MAX_PAGES);
        if (pages > MAX_PAGES) {
            log.warn(
                    "HIRA reported {} pharmacies ({} pages); capping at {} pages",
                    first.totalCount(),
                    pages,
                    MAX_PAGES);
        }
        Parallel.map(
                        IntStream.rangeClosed(2, lastPage).boxed().toList(),
                        LIST_PAGE_CONCURRENCY,
                        pageNo ->
                                parseHiraPage(fetchHiraPage(lat, lng, radiusMeters, pageNo))
                                        .pharmacies())
                .forEach(pharmacies::addAll);
        return new PharmacyBatch(
                pharmacies, SourceRef.DataMode.LIVE, PharmacyProvider.HIRA);
    }

    /** HIRA is primary because it accepts an explicit radius; x is longitude and y is latitude. */
    URI hiraUriFor(double lat, double lng) {
        return hiraUriFor(lat, lng, DEFAULT_RADIUS_METERS, 1);
    }

    URI hiraUriFor(double lat, double lng, int radiusMeters, int pageNo) {
        return PublicApiUriBuilder.of(hiraProperties.hiraPharmacyBaseUrl(), HIRA_OP_BY_LOCATION)
                // This legacy HIRA operation documents the key with an uppercase S.
                .param("ServiceKey", properties.serviceKey())
                .param("xPos", lng)
                .param("yPos", lat)
                .param("radius", radiusMeters)
                .param("numOfRows", PAGE_SIZE)
                .param("pageNo", pageNo)
                .build();
    }

    protected String fetchHiraPage(double lat, double lng, int radiusMeters, int pageNo) {
        return publicApiWebClient
                .get()
                .uri(hiraUriFor(lat, lng, radiusMeters, pageNo))
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    /** NMC's coordinate operation remains the live fallback and keeps its existing request shape. */
    URI uriFor(double lat, double lng) {
        return PublicApiUriBuilder.of(properties.pharmacyBaseUrl(), NMC_OP_BY_LOCATION)
                .serviceKey(properties.serviceKey())
                .param("WGS84_LON", lng)
                .param("WGS84_LAT", lat)
                .param("numOfRows", 100)
                .param("pageNo", 1)
                .param("_type", "json") // NOTE: underscore. The MFDS services want plain `type`.
                .build();
    }

    /**
     * Maps {@code response.body.items.item[]} onto {@link RawPharmacy}.
     *
     * <p>{@link PublicApiResponse} absorbs the envelope difference, the object-vs-array shape of a
     * single result, and the mixed string/number field types. A non-{@code "00"} result code throws
     * rather than yielding an empty list that reads as "no pharmacies nearby".
     */
    private List<RawPharmacy> parseNmc(JsonNode raw) {
        PublicApiResponse response = PublicApiResponse.of(raw).requireOk();

        List<RawPharmacy> out = new ArrayList<>();
        for (JsonNode row : response.items()) {
            Double lat = PublicApiResponse.number(row, "latitude");
            Double lng = PublicApiResponse.number(row, "longitude");
            String hpid = PublicApiResponse.text(row, "hpid");
            if (hpid == null || lat == null || lng == null) {
                // Without an official record id or a location we cannot place it on a map or ever
                // refer to it again. Drop it rather than invent an id (spec §4-3).
                continue;
            }
            out.add(
                    new RawPharmacy(
                            hpid,
                            PublicApiResponse.text(row, "dutyName"),
                            PublicApiResponse.text(row, "dutyAddr"),
                            PublicApiResponse.text(row, "dutyTel1"),
                            lat,
                            lng,
                            PublicApiResponse.number(row, "distance"),
                            PublicApiResponse.text(row, "startTime"),
                            PublicApiResponse.text(row, "endTime")));
        }
        return out;
    }

    /**
     * NMC can answer 200 with rows unrelated to the requested coordinate (issue #97). A genuinely
     * empty response remains an honest empty result; a non-empty batch with no row inside the caller's
     * radius is an upstream quality failure, not evidence that no nearby pharmacy exists.
     */
    private static void requireNmcLocationQuality(
            List<RawPharmacy> pharmacies,
            double originLat,
            double originLng,
            int radiusMeters) {
        if (pharmacies.isEmpty()) {
            return;
        }
        double nearestMeters =
                pharmacies.stream()
                        .mapToDouble(
                                pharmacy ->
                                        nmcDistanceMeters(pharmacy, originLat, originLng))
                        .min()
                        .orElse(Double.POSITIVE_INFINITY);
        if (nearestMeters > radiusMeters) {
            log.warn(
                    "pharmacy_lookup_rejected provider=NMC reason=OUTSIDE_RADIUS count={} nearest_m={} requested_radius_m={}",
                    pharmacies.size(),
                    Math.round(nearestMeters),
                    radiusMeters);
            throw new PublicApiException(
                    "NMC returned "
                            + pharmacies.size()
                            + " pharmacy rows but the nearest was "
                            + Math.round(nearestMeters)
                            + "m from a "
                            + radiusMeters
                            + "m request");
        }
    }

    private static double nmcDistanceMeters(
            RawPharmacy pharmacy, double originLat, double originLng) {
        // Issue #97 proved that a successful NMC response can ignore the requested location. The
        // coordinates are the independently checkable fact; trusting the same row's distance would
        // let a second bad field defeat this quality gate.
        return GeoUtils.haversineMeters(
                originLat,
                originLng,
                pharmacy.latitude(),
                pharmacy.longitude());
    }

    /** HIRA documents XML but some gateway deployments honour `_type=json`; accept both honestly. */
    private HiraPage parseHiraPage(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new PublicApiException("Empty HIRA pharmacy response body");
        }
        try {
            if (raw.stripLeading().startsWith("<")) {
                return parseHiraXmlPage(raw);
            }
            return parseHiraJsonPage(JSON.readTree(raw));
        } catch (PublicApiException e) {
            throw e;
        } catch (Exception e) {
            throw new PublicApiException("Invalid HIRA pharmacy response", e);
        }
    }

    private HiraPage parseHiraJsonPage(JsonNode raw) {
        PublicApiResponse response = PublicApiResponse.of(raw).requireOk();
        List<RawPharmacy> pharmacies = new ArrayList<>();
        for (JsonNode row : response.items()) {
            addHiraRow(
                    pharmacies,
                    PublicApiResponse.text(row, "ykiho"),
                    PublicApiResponse.text(row, "yadmNm"),
                    PublicApiResponse.text(row, "addr"),
                    PublicApiResponse.text(row, "telno"),
                    PublicApiResponse.number(row, "YPos"),
                    PublicApiResponse.number(row, "XPos"),
                    PublicApiResponse.number(row, "distance"));
        }
        return new HiraPage(pharmacies, response.totalCount());
    }

    private HiraPage parseHiraXmlPage(String raw) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        Document document =
                factory.newDocumentBuilder().parse(new InputSource(new StringReader(raw)));
        String resultCode = firstText(document, "resultCode");
        if (!"00".equals(resultCode)) {
            throw new PublicApiException(
                    "HIRA pharmacy API returned resultCode="
                            + resultCode
                            + " ("
                            + firstText(document, "resultMsg")
                            + ")");
        }

        List<RawPharmacy> pharmacies = new ArrayList<>();
        NodeList items = document.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            Element row = (Element) items.item(i);
            addHiraRow(
                    pharmacies,
                    childText(row, "ykiho"),
                    childText(row, "yadmNm"),
                    childText(row, "addr"),
                    childText(row, "telno"),
                    number(childText(row, "YPos")),
                    number(childText(row, "XPos")),
                    number(childText(row, "distance")));
        }
        Double totalCount = number(firstText(document, "totalCount"));
        return new HiraPage(
                pharmacies, totalCount == null ? pharmacies.size() : totalCount.intValue());
    }

    private static void addHiraRow(
            List<RawPharmacy> pharmacies,
            String ykiho,
            String name,
            String address,
            String phone,
            Double latitude,
            Double longitude,
            Double distanceMeters) {
        if (ykiho == null
                || ykiho.isBlank()
                || name == null
                || name.isBlank()
                || latitude == null
                || !Double.isFinite(latitude)
                || longitude == null
                || !Double.isFinite(longitude)) {
            return;
        }
        Double distanceKm =
                distanceMeters == null || !Double.isFinite(distanceMeters)
                        ? null
                        : distanceMeters / 1000.0;
        pharmacies.add(
                new RawPharmacy(
                        ykiho,
                        name,
                        address,
                        phone,
                        latitude,
                        longitude,
                        distanceKm,
                        null,
                        null));
    }

    private static String firstText(Document document, String tag) {
        NodeList nodes = document.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent().trim();
    }

    private static String childText(Element row, String tag) {
        NodeList nodes = row.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent().trim();
    }

    private static Double number(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record HiraPage(List<RawPharmacy> pharmacies, int totalCount) {}

    /**
     * Reconstructs one HIRA pharmacy by the stable id and name carried by a list-result id.
     *
     * <p>The API ignores a {@code ykiho} request parameter, but its documented {@code yadmNm} filter
     * returns identity rows. The name narrows the lookup; the server still accepts only the row whose
     * {@code ykiho} matches, so a renamed or ambiguous pharmacy becomes 404 rather than the wrong
     * verified card.
     */
    @Cacheable(value = "hiraPharmacyIdentity.v2", key = "#ykiho + ':' + #name")
    public HiraIdentityBatch hiraIdentity(String ykiho, String name) {
        if (dataMode.isFixtureOnly() || !properties.isConfigured()) {
            throw new PublicApiException("HIRA pharmacy identity lookup is unavailable");
        }

        try {
            HiraPage first = parseHiraPage(fetchHiraNamePage(name, 1));
            RawPharmacy match = matchingHiraRow(first.pharmacies(), ykiho);
            int pages = (int) Math.ceil(first.totalCount() / (double) PAGE_SIZE);
            if (pages > MAX_PAGES) {
                throw new PublicApiException("HIRA pharmacy name query was not selective");
            }
            if (match == null && pages > 1) {
                match =
                        Parallel.map(
                                        IntStream.rangeClosed(2, pages).boxed().toList(),
                                        LIST_PAGE_CONCURRENCY,
                                        pageNo ->
                                                parseHiraPage(fetchHiraNamePage(name, pageNo))
                                                        .pharmacies())
                                .stream()
                                .flatMap(List::stream)
                                .filter(row -> ykiho.equals(row.hpid()))
                                .findFirst()
                                .orElse(null);
            }
            return new HiraIdentityBatch(match, SourceRef.DataMode.LIVE, Instant.now());
        } catch (PublicApiException e) {
            throw e;
        } catch (Exception ignored) {
            // No cause: WebClient failures may contain a URI with ServiceKey and the pharmacy name.
            throw new PublicApiException("HIRA pharmacy identity lookup failed");
        }
    }

    URI hiraNameUriFor(String name, int pageNo) {
        return PublicApiUriBuilder.of(hiraProperties.hiraPharmacyBaseUrl(), HIRA_OP_BY_LOCATION)
                .param("ServiceKey", properties.serviceKey())
                .param("yadmNm", name)
                .param("numOfRows", PAGE_SIZE)
                .param("pageNo", pageNo)
                .build();
    }

    protected String fetchHiraNamePage(String name, int pageNo) {
        return publicApiWebClient
                .get()
                .uri(hiraNameUriFor(name, pageNo))
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    private static RawPharmacy matchingHiraRow(List<RawPharmacy> rows, String ykiho) {
        return rows.stream().filter(row -> ykiho.equals(row.hpid())).findFirst().orElse(null);
    }

    /**
     * The official weekly timetable for one pharmacy, keyed by day.
     *
     * <p>{@code getParmacyBassInfoInqire?HPID=…} returns {@code dutyTime1s}…{@code dutyTime8c} (1=Mon
     * … 7=Sun, 8=공휴일; a day the pharmacy is closed has <b>no field at all</b>, which is why {@code
     * WeeklyHours} treats a missing index as closed). Feeding the result into {@link
     * com.mermaid.facility.domain.WeeklyHours#fromDutyTimes} lets the operation status become
     * {@code OFFICIAL_SCHEDULE} instead of {@code INFERRED}.
     *
     * <p>It is one call per pharmacy — cached by {@code hpid}. See {@code fixtures/pharmacy_basis.json}
     * for a real response; develop against that, not against the 1,000/day quota.
     *
     * <p>Returns a {@link DutyTable} record rather than a bare {@code Map} because this is a cache
     * value: {@code CacheConfig}'s JSON serializer cannot round-trip a bare {@code Map<Integer, …>}
     * (its {@code Integer} keys read back as {@code String}) nor a {@code String[]} value (its type id
     * is rejected), and {@code cache.type=simple} tests never see either (§11). See {@code DutyTable}.
     */
    @Cacheable(value = "pharmacyWeeklyHours", key = "#hpid")
    public DutyTable weeklyHours(String hpid) {
        if (dataMode.isFixtureOnly()) {
            return parseWeeklyHours(fixtures.load(BASIS_FIXTURE), hpid, SourceRef.DataMode.FIXTURE);
        }
        if (!properties.isConfigured()) {
            log.warn("DATA_GO_KR_SERVICE_KEY is not set — falling back to fixture weekly hours");
            return parseWeeklyHours(fixtures.load(BASIS_FIXTURE), hpid, SourceRef.DataMode.FIXTURE);
        }

        try {
            JsonNode raw =
                    publicApiWebClient
                            .get()
                            .uri(weeklyHoursUriFor(hpid))
                            .retrieve()
                            .bodyToMono(JsonNode.class)
                            .block();
            return parseWeeklyHours(raw, hpid, SourceRef.DataMode.LIVE);
        } catch (Exception ignored) {
            if (dataMode.allowsFallback()) {
                // No exception text: it can carry the request URI and its serviceKey (§2-7).
                log.warn("pharmacy weekly-hours lookup failed for {}, falling back to fixture", hpid);
                return parseWeeklyHours(
                        fixtures.load(BASIS_FIXTURE), hpid, SourceRef.DataMode.FIXTURE);
            }
            // No cause: it can carry the request URI and its serviceKey (§2-7). hpid is a public id.
            throw new PublicApiException("Pharmacy weekly-hours lookup failed for " + hpid);
        }
    }

    URI weeklyHoursUriFor(String hpid) {
        return PublicApiUriBuilder.of(properties.pharmacyBaseUrl(), OP_BY_BASIS)
                .serviceKey(properties.serviceKey())
                .param("HPID", hpid)
                .param("_type", "json")
                .build();
    }

    /**
     * A single pharmacy's full basis record for the detail-by-id path (DEV-205, UI-03).
     *
     * <p>{@code getParmacyBassInfoInqire?HPID=…} is the one pharmacy call that echoes name, address,
     * phone, {@code wgs84Lat}/{@code wgs84Lon} and the weekly timetable together — so unlike a hospital
     * (whose HIRA detail omits identity entirely), a pharmacy is fully reconstructable from its {@code
     * hpid} alone, with no origin coordinate and no list lookup. {@link #weeklyHours} hits the same
     * endpoint but keeps only the timetable; the detail path needs the identity fields too.
     *
     * <p>Returns a batch whose {@code detail} is {@code null} when no row matches the requested {@code
     * hpid} — the caller turns that into a 404 rather than a blank card. Cached per {@code hpid}; a
     * negative result is cached too, which spares the 1,000/day quota a repeated lookup of an id that
     * upstream does not know.
     */
    @Cacheable(value = "pharmacyBasisDetail", key = "#hpid")
    public PharmacyDetailBatch basisDetail(String hpid) {
        if (dataMode.isFixtureOnly()) {
            return fixtureDetail(hpid);
        }
        if (!properties.isConfigured()) {
            log.warn("DATA_GO_KR_SERVICE_KEY is not set — falling back to fixture pharmacy detail");
            return fixtureDetail(hpid);
        }

        try {
            JsonNode raw =
                    publicApiWebClient
                            .get()
                            .uri(weeklyHoursUriFor(hpid))
                            .retrieve()
                            .bodyToMono(JsonNode.class)
                            .block();
            return new PharmacyDetailBatch(
                    parseBasisDetail(raw, hpid, SourceRef.DataMode.LIVE), SourceRef.DataMode.LIVE);
        } catch (Exception ignored) {
            if (dataMode.allowsFallback()) {
                PharmacyDetailBatch fallback = fixtureDetail(hpid);
                if (fallback.detail() != null) {
                    // No exception text: it can carry the request URI and its serviceKey (§2-7).
                    log.warn("pharmacy detail lookup failed for {}, falling back to fixture", hpid);
                    return fallback;
                }
                // The basis fixture holds one pharmacy, so it cannot answer for this hpid. A live
                // outage here means "we could not check", not "no such pharmacy": fall through to fail
                // as SOURCE_UNAVAILABLE rather than returning a 404 that @Cacheable would pin for the
                // 6h TTL, hiding a real pharmacy long after the provider recovers.
            }
            // No cause: it can carry the request URI and its serviceKey (§2-7). hpid is a public id.
            throw new PublicApiException("Pharmacy detail lookup failed for " + hpid);
        }
    }

    private PharmacyDetailBatch fixtureDetail(String hpid) {
        return new PharmacyDetailBatch(
                parseBasisDetail(fixtures.load(BASIS_FIXTURE), hpid, SourceRef.DataMode.FIXTURE),
                SourceRef.DataMode.FIXTURE);
    }

    /**
     * Reads the identity fields and the weekly timetable for one pharmacy. Returns {@code null} when
     * no row carries the requested {@code hpid}, so the detail path can answer 404 instead of guessing.
     */
    // Package-visible so a test can feed a partial row without a dedicated fixture.
    PharmacyDetail parseBasisDetail(JsonNode raw, String hpid, SourceRef.DataMode origin) {
        PublicApiResponse response = PublicApiResponse.of(raw).requireOk();

        for (JsonNode row : response.items()) {
            if (!hpid.equals(PublicApiResponse.text(row, "hpid"))) {
                continue;
            }

            Map<Integer, List<String>> hours = new HashMap<>();
            for (int day = 1; day <= 8; day++) {
                String start = PublicApiResponse.text(row, "dutyTime" + day + "s");
                String end = PublicApiResponse.text(row, "dutyTime" + day + "c");
                if (start != null && end != null) {
                    hours.put(day, List.of(start, end));
                }
            }
            String name = PublicApiResponse.text(row, "dutyName");
            String address = PublicApiResponse.text(row, "dutyAddr");
            Double latitude = PublicApiResponse.number(row, "wgs84Lat");
            Double longitude = PublicApiResponse.number(row, "wgs84Lon");
            if (name == null || name.isBlank()
                    || address == null || address.isBlank()
                    || latitude == null || longitude == null) {
                // Without a name, address, or location this is not the "fully reconstructed pharmacy"
                // the detail endpoint (and API_명세서.md) promises. Treat it as not found rather than a
                // 200 card with null identity fields that would look verified (spec §2-9). Phone and
                // the timetable stay best-effort — a card renders without them.
                return null;
            }
            return new PharmacyDetail(
                    hpid,
                    name,
                    address,
                    PublicApiResponse.text(row, "dutyTel1"),
                    latitude,
                    longitude,
                    new DutyTable(Map.copyOf(hours), origin));
        }

        return null;
    }

    /**
     * Extracts dutyTime1s/dutyTime1c through dutyTime8s/dutyTime8c for the requested pharmacy.
     * Missing day fields must remain absent so WeeklyHours treats those days as closed.
     */
    private DutyTable parseWeeklyHours(JsonNode raw, String hpid, SourceRef.DataMode origin) {
        PublicApiResponse response = PublicApiResponse.of(raw).requireOk();

        for (JsonNode row : response.items()) {
            if (!hpid.equals(PublicApiResponse.text(row, "hpid"))) {
                continue;
            }

            Map<Integer, List<String>> hours = new HashMap<>();
            for (int day = 1; day <= 8; day++) {
                String start = PublicApiResponse.text(row, "dutyTime" + day + "s");
                String end = PublicApiResponse.text(row, "dutyTime" + day + "c");

                if (start != null && end != null) {
                    hours.put(day, List.of(start, end));
                }
            }
            return new DutyTable(hours, origin);
        }

        return DutyTable.empty(origin);
    }

    /**
     * One pharmacy as the location endpoint returns it.
     *
     * @param hpid provider record id: NMC {@code hpid}, or HIRA encrypted {@code ykiho}
     * @param distanceKm normalized to kilometres at the adapter boundary. Null when absent.
     * @param startTime {@code "0900"} or {@code 900} on the wire; meaning unconfirmed — treat as
     *     {@code INFERRED}, not as the published schedule
     */
    public record RawPharmacy(
            String hpid,
            String name,
            String address,
            String phone,
            double latitude,
            double longitude,
            Double distanceKm,
            String startTime,
            String endTime) {}

    /**
     * A batch of pharmacies plus the provenance the whole fetch shares.
     *
     * @param origin {@code LIVE} when the rows came from the API, {@code FIXTURE} when a fixture
     *     answered — in {@code fixture} mode, when the key is unset, or on a {@code hybrid} fallback.
     *     The caller stamps this onto each card's {@link SourceRef} instead of guessing from the
     *     app-wide mode (§2-14).
     */
    public enum PharmacyProvider {
        HIRA(
                "hira-pharmacy",
                "건강보험심사평가원 약국정보서비스",
                "Health Insurance Review & Assessment Service — pharmacy directory"),
        NMC(
                "nmc",
                "국립중앙의료원 전국 약국 정보",
                "National Medical Center — pharmacy directory");

        private final String key;
        private final String sourceName;
        private final String sourceDescription;

        PharmacyProvider(String key, String sourceName, String sourceDescription) {
            this.key = key;
            this.sourceName = sourceName;
            this.sourceDescription = sourceDescription;
        }

        public String key() {
            return key;
        }

        public String sourceName() {
            return sourceName;
        }

        public String sourceDescription() {
            return sourceDescription;
        }
    }

    public record PharmacyBatch(
            List<RawPharmacy> pharmacies,
            SourceRef.DataMode origin,
            PharmacyProvider provider,
            Instant retrievedAt) {

        public PharmacyBatch(
                List<RawPharmacy> pharmacies,
                SourceRef.DataMode origin,
                PharmacyProvider provider) {
            this(pharmacies, origin, provider, Instant.now());
        }

        /** Compatibility constructor: historical callers and fixtures are NMC-sourced. */
        public PharmacyBatch(List<RawPharmacy> pharmacies, SourceRef.DataMode origin) {
            this(pharmacies, origin, PharmacyProvider.NMC, Instant.now());
        }
    }

    /**
     * One NMC pharmacy's full basis record for the legacy detail-by-id path.
     *
     * @param latitude {@code wgs84Lat} — the basis endpoint's coordinate field, not the location
     *     endpoint's {@code latitude}. Null when the row omits it.
     * @param weeklyHours the official 1..8 duty table (1=Mon … 7=Sun, 8=공휴일)
     */
    public record PharmacyDetail(
            String hpid,
            String name,
            String address,
            String phone,
            Double latitude,
            Double longitude,
            DutyTable weeklyHours) {}

    /** NMC pharmacy detail plus provenance; {@code detail} is null when HPID did not match. */
    public record PharmacyDetailBatch(PharmacyDetail detail, SourceRef.DataMode origin) {}

    /** HIRA identity reconstructed from the name-filtered directory plus provenance. */
    public record HiraIdentityBatch(
            RawPharmacy pharmacy, SourceRef.DataMode origin, Instant retrievedAt) {

        public HiraIdentityBatch(RawPharmacy pharmacy, SourceRef.DataMode origin) {
            this(pharmacy, origin, Instant.now());
        }
    }
}
