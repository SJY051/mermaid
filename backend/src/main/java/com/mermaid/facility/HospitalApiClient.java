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
    private static final int PAGE_SIZE = 100;
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
            return liveBatch(lat, lng, radiusMeters);
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
        return new HospitalBatch(parsePage(fixtures.load(FIXTURE)).hospitals(), SourceRef.DataMode.FIXTURE);
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
        for (int pageNo = 2; (pageNo - 1) * PAGE_SIZE < first.totalCount(); pageNo++) {
            hospitals.addAll(parsePage(fetchPage(lat, lng, radiusMeters, pageNo)).hospitals());
        }
        return new HospitalBatch(hospitals, SourceRef.DataMode.LIVE);
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
                            PublicApiResponse.text(row, "clCdNm"),
                            latitude,
                            longitude,
                            PublicApiResponse.number(row, "distance")));
        }
        return new HospitalPage(hospitals, response.totalCount());
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

    private record HospitalPage(List<RawHospital> hospitals, int totalCount) {}
}
