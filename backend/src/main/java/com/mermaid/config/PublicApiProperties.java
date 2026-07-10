package com.mermaid.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * data.go.kr endpoints and the shared service key.
 *
 * <p>{@code serviceKey} MUST be the <b>Decoding</b> key. See {@link
 * com.mermaid.common.PublicApiUriBuilder} for the double-encoding trap.
 */
@ConfigurationProperties(prefix = "mermaid.public-api")
public record PublicApiProperties(
        String serviceKey,
        String pharmacyBaseUrl,
        String hospitalBaseUrl,
        String hospitalDetailBaseUrl,
        String easyDrugBaseUrl,
        String drugPermissionBaseUrl) {

    public boolean isConfigured() {
        return serviceKey != null && !serviceKey.isBlank();
    }
}
