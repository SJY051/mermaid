package com.mermaid.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

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
        String holidayBaseUrl,
        String hospitalBaseUrl,
        String hospitalDetailBaseUrl,
        String easyDrugBaseUrl,
        String drugPermissionBaseUrl,
        String durBaseUrl) {

    @ConstructorBinding
    public PublicApiProperties {}

    /** Existing direct callers inherit the documented holiday endpoint. */
    public PublicApiProperties(
            String serviceKey,
            String pharmacyBaseUrl,
            String hospitalBaseUrl,
            String hospitalDetailBaseUrl,
            String easyDrugBaseUrl,
            String drugPermissionBaseUrl,
            String durBaseUrl) {
        this(
                serviceKey,
                pharmacyBaseUrl,
                "https://apis.data.go.kr/B090041/openapi/service/SpcdeInfoService",
                hospitalBaseUrl,
                hospitalDetailBaseUrl,
                easyDrugBaseUrl,
                drugPermissionBaseUrl,
                durBaseUrl);
    }

    public boolean isConfigured() {
        return serviceKey != null && !serviceKey.isBlank();
    }
}
