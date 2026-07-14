package com.mermaid.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Server-side credentials and endpoint for Naver Geocoding. */
@ConfigurationProperties(prefix = "mermaid.naver-map")
public record NaverMapProperties(String clientId, String clientSecret, String geocodeBaseUrl) {

    public boolean isConfigured() {
        return clientId != null
                && !clientId.isBlank()
                && clientSecret != null
                && !clientSecret.isBlank();
    }
}
