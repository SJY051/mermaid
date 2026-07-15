package com.mermaid.facility.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The facility id scheme (spec §4-3, FR-006). */
class FacilityTest {

    @Test
    @DisplayName("a ykiho with path-unsafe characters round-trips through the id segment encoding")
    void urlSafeSegmentRoundTripsPathUnsafeCharacters() {
        // A real HIRA ykiho decodes to text like "$481881#51#…" and is transmitted as base64,
        // whose alphabet includes '/', '+', '='. All three must be gone from the id segment, yet
        // decode back to the exact original for the future GET /facilities/{id} lookup.
        String ykiho = "ab/cd+ef==$#";

        String segment = Facility.urlSafeSegment(ykiho);

        assertThat(segment).doesNotContain("/").doesNotContain("+").doesNotContain("=");
        assertThat(Facility.decodeSegment(segment)).isEqualTo(ykiho);
        assertThat(Facility.idOf("hira", segment)).startsWith("facility:hira:");
    }
}
