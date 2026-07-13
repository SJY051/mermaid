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
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/** HIRA hospital list adapter (DEV-203a). */
@Slf4j
@Component
@RequiredArgsConstructor
public class HospitalApiClient {

    private static final String OP_BY_LOCATION = "getHospBasisList";
    private static final String FIXTURE = "hospital_list.json";

    private final WebClient publicApiWebClient;
    private final PublicApiProperties properties;
    private final DataModeProperties dataMode;
    private final FixtureLoader fixtures;

    /**
     * Hospitals near a point. HIRA requires radius in metres; xPos is longitude and yPos is latitude.
     */
    public HospitalBatch findNear(double lat, double lng, int radiusMeters) {
        if (dataMode.isFixtureOnly()) {
            return fixtureBatch();
        }
        if (!properties.isConfigured()) {
            log.warn("DATA_GO_KR_SERVICE_KEY is not set — falling back to hospital fixture data");
            return fixtureBatch();
        }

        try {
            JsonNode raw =
                    publicApiWebClient
                            .get()
                            .uri(uriFor(lat, lng, radiusMeters))
                            .retrieve()
                            .bodyToMono(JsonNode.class)
                            .block();
            return new HospitalBatch(parse(raw), SourceRef.DataMode.LIVE);
        } catch (Exception e) {
            if (dataMode.allowsFallback()) {
                log.warn("hospital lookup failed, falling back to fixture: {}", e.getMessage());
                return fixtureBatch();
            }
            throw new PublicApiException("Hospital lookup failed near " + lat + "," + lng, e);
        }
    }

    private HospitalBatch fixtureBatch() {
        return new HospitalBatch(parse(fixtures.load(FIXTURE)), SourceRef.DataMode.FIXTURE);
    }

    /** Builds the HIRA list request with its mandatory radius and `_type=json` parameter. */
    URI uriFor(double lat, double lng, int radiusMeters) {
        return PublicApiUriBuilder.of(properties.hospitalBaseUrl(), OP_BY_LOCATION)
                .serviceKey(properties.serviceKey())
                .param("xPos", lng)
                .param("yPos", lat)
                .param("radius", radiusMeters)
                .param("_type", "json")
                .build();
    }

    /** Parses the HIRA list fixture/API response into normalized raw hospital data. */
    private List<RawHospital> parse(JsonNode raw) {
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
                            PublicApiResponse.text(row, "clCdNm"),
                            latitude,
                            longitude,
                            PublicApiResponse.number(row, "distance")));
        }
        return hospitals;
    }

    /** A single HIRA list record; official hours arrive only in DEV-203b's detail adapter. */
    public record RawHospital(
            String ykiho,
            String nameKo,
            String addressKo,
            String postcode,
            String phone,
            String classification,
            double latitude,
            double longitude,
            Double distanceMeters) {}

    /** Hospital rows plus the source of this fetch, so hybrid fallback is never labelled live. */
    public record HospitalBatch(List<RawHospital> hospitals, SourceRef.DataMode origin) {}
}
