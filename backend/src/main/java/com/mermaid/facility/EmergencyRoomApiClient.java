package com.mermaid.facility;

import com.fasterxml.jackson.databind.JsonNode;
import com.mermaid.common.FixtureLoader;
import com.mermaid.common.PublicApiException;
import com.mermaid.common.PublicApiResponse;
import com.mermaid.common.PublicApiUriBuilder;
import com.mermaid.common.SourceRef;
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

/** National Medical Center emergency-facility location service (data.go.kr 15000563). */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmergencyRoomApiClient {

    private static final String OP_BY_LOCATION = "getEgytLcinfoInqire";
    private static final int MAX_ROWS = 100;
    /** Three decimal places form the ~100-m grid shared by nearby callers. */
    static final int GRID_DECIMALS = 1000;
    private static final String FIXTURE = "emergency_room.json";

    private final WebClient publicApiWebClient;
    private final PublicApiProperties properties;
    private final DataModeProperties dataMode;
    private final FixtureLoader fixtures;

    /**
     * Fetch and cache from the grid centre, not the first caller's exact point. {@link FacilityService}
     * recalculates distance from each request origin, so callers in this roughly 100-m cell share a
     * stable upstream list without inheriting someone else's NMC {@code distance} value.
     */
    @Cacheable(
            value = "emergencyRoomsNear.v1",
            key = "T(com.mermaid.facility.EmergencyRoomApiClient).cacheKeyFor(#lat, #lng)")
    public EmergencyRoomBatch findNear(double lat, double lng) {
        if (dataMode.isFixtureOnly()) {
            return fixtureBatch();
        }
        if (!properties.isConfigured()) {
            log.warn("DATA_GO_KR_SERVICE_KEY is not set — falling back to fixture emergency rooms");
            return fixtureBatch();
        }

        double centreLat = Math.round(lat * GRID_DECIMALS) / (double) GRID_DECIMALS;
        double centreLng = Math.round(lng * GRID_DECIMALS) / (double) GRID_DECIMALS;
        try {
            return new EmergencyRoomBatch(parse(fetch(centreLat, centreLng)), SourceRef.DataMode.LIVE);
        } catch (Exception e) {
            if (dataMode.allowsFallback()) {
                log.warn("emergency-room lookup failed, falling back to fixture: {}", e.getMessage());
                return fixtureBatch();
            }
            throw new PublicApiException("Emergency-room lookup failed near " + lat + "," + lng, e);
        }
    }

    URI uriFor(double lat, double lng) {
        return PublicApiUriBuilder.of(properties.emergencyRoomBaseUrl(), OP_BY_LOCATION)
                .serviceKey(properties.serviceKey())
                .param("WGS84_LON", lng)
                .param("WGS84_LAT", lat)
                .param("numOfRows", MAX_ROWS)
                .param("pageNo", 1)
                .param("_type", "json")
                .build();
    }

    /** Separated so the grid-centred request can be checked without a live NMC call. */
    protected JsonNode fetch(double lat, double lng) {
        return publicApiWebClient.get().uri(uriFor(lat, lng)).retrieve().bodyToMono(JsonNode.class).block();
    }

    private EmergencyRoomBatch fixtureBatch() {
        return new EmergencyRoomBatch(parse(fixtures.load(FIXTURE)), SourceRef.DataMode.FIXTURE);
    }

    /** Public because Spring SpEL resolves static cache-key methods through {@link Class#getMethods()}. */
    public static String cacheKeyFor(double lat, double lng) {
        return Math.round(lat * 1000) + ":" + Math.round(lng * 1000);
    }

    List<RawEmergencyRoom> parse(JsonNode raw) {
        PublicApiResponse response = PublicApiResponse.of(raw).requireOk();
        List<RawEmergencyRoom> out = new ArrayList<>();
        for (JsonNode row : response.items()) {
            String hpid = PublicApiResponse.text(row, "hpid");
            Double latitude = PublicApiResponse.number(row, "latitude");
            Double longitude = PublicApiResponse.number(row, "longitude");
            if (hpid == null
                    || hpid.isBlank()
                    || latitude == null
                    || longitude == null
                    || !Double.isFinite(latitude)
                    || !Double.isFinite(longitude)) {
                continue;
            }
            out.add(
                    new RawEmergencyRoom(
                            hpid,
                            PublicApiResponse.text(row, "dutyName"),
                            PublicApiResponse.text(row, "dutyAddr"),
                            PublicApiResponse.text(row, "dutyTel1"),
                            latitude,
                            longitude));
        }
        return out;
    }

    public record EmergencyRoomBatch(List<RawEmergencyRoom> emergencyRooms, SourceRef.DataMode origin) {}

    public record RawEmergencyRoom(
            String hpid,
            String name,
            String address,
            String phone,
            double latitude,
            double longitude) {}
}
