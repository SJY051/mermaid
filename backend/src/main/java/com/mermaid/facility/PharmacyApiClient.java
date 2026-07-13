package com.mermaid.facility;

import com.fasterxml.jackson.databind.JsonNode;
import com.mermaid.common.FixtureLoader;
import com.mermaid.common.PublicApiException;
import com.mermaid.common.PublicApiResponse;
import com.mermaid.common.PublicApiUriBuilder;
import com.mermaid.common.SourceRef;
import com.mermaid.config.DataModeProperties;
import com.mermaid.config.PublicApiProperties;
import com.mermaid.facility.domain.DutyTable;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 국립중앙의료원 전국 약국 정보 조회 서비스 (data.go.kr 15000576).
 *
 * <p>What the live API actually returns (verified 2026-07-10, {@code fixtures/pharmacy.json}) — this
 * differs from what every write-up about it says:
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
 * <p>The development quota is <b>1,000 calls/day</b> — the tightest of all our APIs. Five people
 * refreshing a map will burn it before lunch, which is why {@link Cacheable} and {@link
 * DataModeProperties} are not optional here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PharmacyApiClient {

    private static final String OP_BY_LOCATION = "getParmacyLcinfoInqire";
    private static final String OP_BY_BASIS = "getParmacyBassInfoInqire";
    private static final int MAX_ROWS = 100;
    private static final String FIXTURE = "pharmacy.json";
    private static final String BASIS_FIXTURE = "pharmacy_basis.json";

    private final WebClient publicApiWebClient;
    private final PublicApiProperties properties;
    private final DataModeProperties dataMode;
    private final FixtureLoader fixtures;

    /**
     * Pharmacies near a point, distance-sorted, tagged with where they actually came from.
     *
     * <p>Cached on the rounded coordinate: two people on the same street corner share one upstream
     * call. Three decimal places is roughly a 100 m grid.
     *
     * <p>The result carries a {@link SourceRef.DataMode} because provenance is per-fetch, not
     * per-app-mode: in {@code hybrid} a government outage falls back to fixtures, and §2-14 requires
     * that fixture data be labelled fixture on the card — deciding it from the app-wide switch would
     * stamp a fallback row {@code live}. The origin travels with the value, so a cached fallback stays
     * honestly labelled.
     */
    @Cacheable(
            // v2: the cached value changed from List<RawPharmacy> to PharmacyBatch. Under the old
            // name a six-hour-old Redis entry would still deserialize as a List, then throw
            // ClassCastException when the cache hands it back as a PharmacyBatch. A new name sidesteps
            // every stale entry at once.
            value = "pharmaciesNear.v2",
            key = "T(java.lang.Math).round(#lat * 1000) + ':' + T(java.lang.Math).round(#lng * 1000)")
    public PharmacyBatch findNear(double lat, double lng) {
        if (dataMode.isFixtureOnly()) {
            return fixtureBatch();
        }
        if (!properties.isConfigured()) {
            log.warn("DATA_GO_KR_SERVICE_KEY is not set — falling back to fixture data");
            return fixtureBatch();
        }

        try {
            JsonNode raw =
                    publicApiWebClient
                            .get()
                            .uri(uriFor(lat, lng))
                            .retrieve()
                            .bodyToMono(JsonNode.class)
                            .block();
            return new PharmacyBatch(parse(raw), SourceRef.DataMode.LIVE);
        } catch (Exception e) {
            if (dataMode.allowsFallback()) {
                // hybrid: a demo must survive a government outage. The batch is tagged FIXTURE so the
                // card can say so — the source metadata is the only thing that keeps it from lying.
                log.warn("pharmacy lookup failed, falling back to fixture: {}", e.getMessage());
                return fixtureBatch();
            }
            throw new PublicApiException("Pharmacy lookup failed near " + lat + "," + lng, e);
        }
    }

    private PharmacyBatch fixtureBatch() {
        return new PharmacyBatch(parse(fixtures.load(FIXTURE)), SourceRef.DataMode.FIXTURE);
    }

    URI uriFor(double lat, double lng) {
        return PublicApiUriBuilder.of(properties.pharmacyBaseUrl(), OP_BY_LOCATION)
                .serviceKey(properties.serviceKey())
                .param("WGS84_LON", lng)
                .param("WGS84_LAT", lat)
                .param("numOfRows", MAX_ROWS)
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
    private List<RawPharmacy> parse(JsonNode raw) {
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
        } catch (Exception e) {
            if (dataMode.allowsFallback()) {
                log.warn(
                        "pharmacy weekly-hours lookup failed for {}, falling back to fixture: {}",
                        hpid,
                        e.getMessage());
                return parseWeeklyHours(
                        fixtures.load(BASIS_FIXTURE), hpid, SourceRef.DataMode.FIXTURE);
            }
            throw new PublicApiException("Pharmacy weekly-hours lookup failed for " + hpid, e);
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
     * @param distanceKm the API's own {@code distance}, in <b>kilometres</b>. Null when absent.
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
    public record PharmacyBatch(List<RawPharmacy> pharmacies, SourceRef.DataMode origin) {}
}
