package com.mermaid.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** HIRA's pharmacy directory endpoint, kept separate from the NMC pharmacy fallback URL. */
@ConfigurationProperties(prefix = "mermaid.public-api")
public record HiraPharmacyProperties(String hiraPharmacyBaseUrl) {}
