package com.mermaid.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Rollout gates for DEV-603; general explanation requires the model-planning gate. */
@ConfigurationProperties(prefix = "mermaid.chat.risk-tier")
public record RiskTierFeatureProperties(
        boolean generalExplanationEnabled,
        boolean ambiguousPlanningEnabled,
        boolean t5PolicyEnabled) {

    public RiskTierFeatureProperties {
        if (generalExplanationEnabled && !ambiguousPlanningEnabled) {
            throw new IllegalArgumentException(
                    "general explanation requires ambiguous planning");
        }
    }

    public static RiskTierFeatureProperties allEnabled() {
        return new RiskTierFeatureProperties(true, true, true);
    }
}
