package com.mermaid.common;

import com.fasterxml.jackson.annotation.JsonValue;
import java.time.Instant;

/**
 * Where a fact came from (spec §2-14).
 *
 * <p>Every factual card the user sees carries one of these, and the UI shows it. Two reasons:
 * the demo has to be able to run on fixtures when a government API is down, and a fixture must
 * never be presented as live data.
 *
 * @param id referenced by {@code drugCard.sourceRefId}; post-processing invariant #1 checks it
 * @param recordId the provider's own id, or null if the record has none (then do not persist it)
 */
public record SourceRef(
        String id,
        String provider,
        String recordId,
        Instant retrievedAt,
        DataMode dataMode,
        String title) {

    /** Per-record provenance. Distinct from the app-wide {@code DataMode} setting. */
    public enum DataMode {
        LIVE("live"),
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
}
