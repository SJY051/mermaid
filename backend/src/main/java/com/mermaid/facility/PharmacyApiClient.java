package com.mermaid.facility;

import com.fasterxml.jackson.databind.JsonNode;
import com.mermaid.common.PublicApiException;
import com.mermaid.common.PublicApiUriBuilder;
import com.mermaid.config.PublicApiProperties;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 국립중앙의료원 전국 약국 정보 조회 서비스 (data.go.kr 15000576).
 *
 * <p>Three facts that shape this class:
 *
 * <ul>
 *   <li>The operation is {@code getParmacyLcinfoInqire} — yes, "Parmacy", the typo is theirs.
 *   <li>It takes {@code WGS84_LON} and {@code WGS84_LAT} and <b>no radius</b>. Results arrive sorted
 *       by distance; we clip them ourselves in {@link FacilityService}.
 *   <li>It returns the whole weekly timetable ({@code dutyTime1s}…{@code dutyTime8c}) in the same
 *       response, so "open now" needs no second call.
 * </ul>
 *
 * <p>The development quota is <b>1,000 calls/day</b> — the tightest of all four APIs. Five people
 * refreshing a map will burn it before lunch, which is why {@link Cacheable} is not optional here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PharmacyApiClient {

    private static final String OPERATION = "getParmacyLcinfoInqire";
    private static final int MAX_ROWS = 100;

    private final WebClient publicApiWebClient;
    private final PublicApiProperties properties;

    /**
     * Pharmacies near a point, distance-sorted, with their weekly hours.
     *
     * <p>Cached on the rounded coordinate: two users on the same street corner share one upstream
     * call. Rounding to 3 decimals is roughly a 100m grid.
     */
    @Cacheable(value = "pharmaciesNear", key = "T(java.lang.Math).round(#lat * 1000) + ':' + T(java.lang.Math).round(#lng * 1000)")
    public List<RawPharmacy> findNear(double lat, double lng) {
        if (!properties.isConfigured()) {
            log.warn("DATA_GO_KR_SERVICE_KEY is not set — returning no pharmacies");
            return List.of();
        }

        URI uri =
                PublicApiUriBuilder.of(properties.pharmacyBaseUrl(), OPERATION)
                        .serviceKey(properties.serviceKey())
                        .param("WGS84_LON", lng)
                        .param("WGS84_LAT", lat)
                        .param("numOfRows", MAX_ROWS)
                        .param("pageNo", 1)
                        .param("_type", "json") // NOTE: underscore. The MFDS APIs want plain `type`.
                        .build();

        try {
            JsonNode body = publicApiWebClient.get().uri(uri).retrieve().bodyToMono(JsonNode.class).block();
            return parse(body);
        } catch (Exception e) {
            throw new PublicApiException("Pharmacy lookup failed near " + lat + "," + lng, e);
        }
    }

    /**
     * TODO(team): map {@code response.body.items.item[]} onto {@link RawPharmacy}.
     *
     * <p>Fields you need: {@code hpid}, {@code dutyName}, {@code dutyAddr}, {@code dutyTel1}, {@code
     * wgs84Lat}, {@code wgs84Lon}, and {@code dutyTime1s}/{@code dutyTime1c} … {@code dutyTime8s}/
     * {@code dutyTime8c}.
     *
     * <p>Two traps. When there is exactly one result, {@code item} is an object rather than an array.
     * And when the service key is wrong the response is a 200 carrying an error envelope, not a 4xx —
     * check {@code response.header.resultCode} and throw {@link PublicApiException} unless it is
     * {@code "00"}.
     *
     * <p>Write the test first: drop a real response into {@code src/test/resources/} and assert
     * against it. Do not spend the daily quota on debugging.
     */
    private List<RawPharmacy> parse(JsonNode body) {
        log.warn("PharmacyApiClient.parse is not implemented yet — returning no pharmacies");
        return List.of();
    }

    /**
     * One pharmacy, straight off the wire.
     *
     * @param dutyTimes index 1..8 → {open, close} HHMM strings; 1=Mon … 7=Sun, 8=holiday
     */
    public record RawPharmacy(
            String hpid,
            String name,
            String address,
            String phone,
            double latitude,
            double longitude,
            java.util.Map<Integer, String[]> dutyTimes) {}
}
