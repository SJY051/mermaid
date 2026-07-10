package com.mermaid.facility;

import com.fasterxml.jackson.databind.JsonNode;
import com.mermaid.common.FixtureLoader;
import com.mermaid.common.PublicApiException;
import com.mermaid.common.PublicApiResponse;
import com.mermaid.common.PublicApiUriBuilder;
import com.mermaid.config.DataModeProperties;
import com.mermaid.config.PublicApiProperties;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
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
    private static final int MAX_ROWS = 100;
    private static final String FIXTURE = "pharmacy.json";

    private final WebClient publicApiWebClient;
    private final PublicApiProperties properties;
    private final DataModeProperties dataMode;
    private final FixtureLoader fixtures;

    /**
     * Pharmacies near a point, distance-sorted.
     *
     * <p>Cached on the rounded coordinate: two people on the same street corner share one upstream
     * call. Three decimal places is roughly a 100 m grid.
     */
    @Cacheable(
            value = "pharmaciesNear",
            key = "T(java.lang.Math).round(#lat * 1000) + ':' + T(java.lang.Math).round(#lng * 1000)")
    public List<RawPharmacy> findNear(double lat, double lng) {
        if (dataMode.isFixtureOnly()) {
            return parse(fixtures.load(FIXTURE));
        }
        if (!properties.isConfigured()) {
            log.warn("DATA_GO_KR_SERVICE_KEY is not set — falling back to fixture data");
            return parse(fixtures.load(FIXTURE));
        }

        try {
            JsonNode raw =
                    publicApiWebClient
                            .get()
                            .uri(uriFor(lat, lng))
                            .retrieve()
                            .bodyToMono(JsonNode.class)
                            .block();
            return parse(raw);
        } catch (Exception e) {
            if (dataMode.allowsFallback()) {
                // hybrid: a demo must survive a government outage, and the source metadata will
                // tell the user this row came from a fixture.
                log.warn("pharmacy lookup failed, falling back to fixture: {}", e.getMessage());
                return parse(fixtures.load(FIXTURE));
            }
            throw new PublicApiException("Pharmacy lookup failed near " + lat + "," + lng, e);
        }
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
     * TODO(BE-2, DEV-202): fetch the weekly timetable.
     *
     * <p>{@code getParmacyBassInfoInqire?HPID=…} returns {@code dutyTime1s}…{@code dutyTime6c} (1=Mon
     * … 6=Sat; a day the pharmacy is closed has <b>no field at all</b>, which is why {@code
     * WeeklyHours} treats a missing index as closed). Feed those into {@link
     * com.mermaid.facility.domain.WeeklyHours#fromDutyTimes} and the operation status becomes
     * {@code OFFICIAL_SCHEDULE} instead of {@code INFERRED}.
     *
     * <p>It is one call per pharmacy — cache it by {@code hpid}. See {@code fixtures/pharmacy_basis.json}
     * for a real response; develop against that, not against the 1,000/day quota.
     */
    public java.util.Map<Integer, String[]> weeklyHours(String hpid) {
        return java.util.Map.of();
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
}
