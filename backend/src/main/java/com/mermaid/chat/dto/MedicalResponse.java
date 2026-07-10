package com.mermaid.chat.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonValue;
import com.mermaid.facility.domain.FacilityType;
import java.util.List;

/**
 * The shape every assistant turn must take (FR-03, spec §5-1).
 *
 * <p>Note there is no tool-calling here, by design. A model that emits a tool call puts it in {@code
 * tool_calls} and leaves {@code content} empty — so a tool call and schema-constrained JSON can
 * never occupy the same message (spec §2-1). The map is a <i>field</i> instead: fill {@code map} and
 * the frontend renders it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MedicalResponse(
        String reply,
        Urgency urgency,
        List<Medication> medications,
        MapDirective map,
        String disclaimer) {

    /** How fast the user needs to act. Drives SA-03. */
    public enum Urgency {
        SELF_CARE("self_care"),
        SEE_PHARMACIST("see_pharmacist"),
        SEE_DOCTOR("see_doctor"),
        EMERGENCY("emergency");

        private final String wire;

        Urgency(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }

        @JsonCreator
        public static Urgency from(String value) {
            for (Urgency u : values()) {
                if (u.wire.equalsIgnoreCase(value)) {
                    return u;
                }
            }
            // An unknown urgency must not crash the client (NFR-04). Fail safe, upward.
            return SEE_DOCTOR;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Medication(
            String koreanName,
            String englishIngredient,
            String purpose,
            String dosage,
            List<String> cautions,
            boolean prescriptionRequired,
            /** Non-null when the drug matches one of the profile's avoided ingredients (FR-04). */
            String allergyWarning) {}

    /**
     * Present when the assistant decided a map helps. Null means "no map".
     *
     * <p>The frontend passes these straight to {@code GET /api/v1/facilities}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MapDirective(FacilityType type, int radiusMeters, boolean openNow, String reason) {}
}
