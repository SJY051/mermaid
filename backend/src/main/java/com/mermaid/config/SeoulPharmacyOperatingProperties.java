package com.mermaid.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** Seoul Open Data Plaza operating-hours endpoint, including its path-based API key. */
@ConfigurationProperties(prefix = "mermaid.public-api")
public record SeoulPharmacyOperatingProperties(String seoulPharmacyOperatingUrl) {

    @ConstructorBinding
    public SeoulPharmacyOperatingProperties {}

    public boolean isConfigured() {
        return seoulPharmacyOperatingUrl != null && !seoulPharmacyOperatingUrl.isBlank();
    }
}
