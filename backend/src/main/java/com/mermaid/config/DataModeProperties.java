package com.mermaid.config;

import com.fasterxml.jackson.annotation.JsonValue;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where facts come from (spec §2-14).
 *
 * <p>Two problems, one switch. The pharmacy API allows <b>1,000 calls a day</b>, so five people
 * developing against it exhaust the quota before lunch. And on demo day a government service that is
 * down would end the presentation.
 *
 * <p>{@code fixture} data is never presented as live: every {@link com.mermaid.common.SourceRef}
 * carries its own {@code dataMode}, and the UI shows it.
 */
@ConfigurationProperties(prefix = "mermaid")
public record DataModeProperties(DataMode dataMode) {

    public DataModeProperties {
        if (dataMode == null) {
            dataMode = DataMode.HYBRID;
        }
    }

    public enum DataMode {
        /** Call the real APIs. A failure is an error the user sees. */
        LIVE("live"),
        /** Call the real APIs; on failure fall back to fixtures and say so. The dev default. */
        HYBRID("hybrid"),
        /** Never touch the network. Reproducible demos and CI. */
        FIXTURE("fixture");

        private final String wire;

        DataMode(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    public boolean isFixtureOnly() {
        return dataMode == DataMode.FIXTURE;
    }

    /** In {@code hybrid} a provider failure is survivable; in {@code live} it is not. */
    public boolean allowsFallback() {
        return dataMode == DataMode.HYBRID;
    }
}
