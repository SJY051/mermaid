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

/** HIRA hospital list adapter (DEV-203a). */
@Slf4j
@Component
@RequiredArgsConstructor
public class HospitalApiClient {

    private static final String OP_BY_LOCATION = "getHospBasisList";
    private static final int PAGE_SIZE = 100;
    // 20 pages = 2,000 hospitals, far past any real radius (the densest fixture radius is 440).
    // The cap protects one user request from a runaway/hostile totalCount turning into thousands
    // of sequential blocking calls — the service only shows the nearest handful anyway.
    static final int MAX_PAGES = 20;
    private static final String FIXTURE = "hospital_list.json";

    private final WebClient publicApiWebClient;
    private final PublicApiProperties properties;
    private final DataModeProperties dataMode;
    private final FixtureLoader fixtures;

    /**
     * Hospitals near a point. HIRA requires radius in metres; xPos is longitude and yPos is latitude.
     *
     * <p>Like the pharmacy directory, this shares a list lookup for callers on the same roughly
     * 100-m grid. The radius is part of the key because it changes HIRA's result set; the batch keeps
     * per-fetch provenance, so a cached hybrid fallback remains visibly fixture data.
     */
    @Cacheable(
            value = "hospitalsNear.v1",
            key =
                    "T(java.lang.Math).round(#lat * 1000) + ':' + T(java.lang.Math).round(#lng * 1000)"
                            + " + ':' + #radiusMeters")
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
        for (int pageNo = 2; pageNo <= lastPage; pageNo++) {
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
    public record HospitalBatch(List<RawHospital> hospitals, SourceRef.DataMode origin) {}

    private record HospitalPage(List<RawHospital> hospitals, int totalCount) {}
}
