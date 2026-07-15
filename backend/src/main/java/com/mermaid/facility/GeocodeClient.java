package com.mermaid.facility;

import com.fasterxml.jackson.databind.JsonNode;
import com.mermaid.common.ApiException;
import com.mermaid.common.ErrorCode;
import com.mermaid.config.NaverMapProperties;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/** Server-side Naver Geocoding client. The browser must never receive its client secret. */
@Slf4j
@Component
public class GeocodeClient {

    static final String API_KEY_ID_HEADER = "x-ncp-apigw-api-key-id";
    static final String API_KEY_HEADER = "x-ncp-apigw-api-key";
    private static final int MAX_RESULTS = 5;

    private final WebClient webClient;
    private final NaverMapProperties properties;

    public GeocodeClient(
            @Qualifier("publicApiWebClient") WebClient publicApiWebClient,
            NaverMapProperties properties) {
        this.properties = properties;
        this.webClient =
                publicApiWebClient
                        .mutate()
                        .baseUrl(properties.geocodeBaseUrl())
                        .defaultHeader(API_KEY_ID_HEADER, valueOrEmpty(properties.clientId()))
                        .defaultHeader(API_KEY_HEADER, valueOrEmpty(properties.clientSecret()))
                        .build();
    }

    public List<GeocodeResult> search(String query) {
        if (!properties.isConfigured()) {
            log.warn("geocode_lookup_failed outcome=NOT_CONFIGURED");
            throw unavailable();
        }

        try {
            JsonNode raw =
                    webClient
                            .get()
                            .uri(uriBuilder -> uriBuilder.queryParam("query", query).build())
                            .retrieve()
                            .bodyToMono(JsonNode.class)
                            .block();
            List<GeocodeResult> results = parse(raw);
            log.debug("geocode_lookup_completed status=OK count={}", results.size());
            return results;
        } catch (ApiException e) {
            log.warn("geocode_lookup_failed outcome=NON_OK_STATUS");
            throw e;
        } catch (Exception e) {
            // WebClient exceptions can include the request URI. The query is a person's location,
            // so log only a stable outcome and keep the exception out of the log line.
            log.warn("geocode_lookup_failed outcome=UPSTREAM_ERROR");
            throw unavailable();
        }
    }

    private List<GeocodeResult> parse(JsonNode raw) {
        if (raw == null || !"OK".equals(raw.path("status").asText())) {
            throw unavailable();
        }

        JsonNode addresses = raw.path("addresses");
        if (!addresses.isArray()) {
            throw unavailable();
        }

        List<GeocodeResult> results = new ArrayList<>();
        for (JsonNode address : addresses) {
            if (results.size() == MAX_RESULTS) {
                break;
            }

            // Naver names longitude x and latitude y, and sends both as strings. Translate them
            // exactly once at this boundary so those provider-specific names never leak inward.
            double longitude = Double.parseDouble(address.path("x").asText());
            double latitude = Double.parseDouble(address.path("y").asText());
            results.add(
                    new GeocodeResult(
                            address.path("roadAddress").asText(""),
                            address.path("jibunAddress").asText(""),
                            address.path("englishAddress").asText(""),
                            latitude,
                            longitude));
        }
        return List.copyOf(results);
    }

    private static ApiException unavailable() {
        return new ApiException(ErrorCode.SOURCE_UNAVAILABLE, "geocode upstream unavailable");
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
