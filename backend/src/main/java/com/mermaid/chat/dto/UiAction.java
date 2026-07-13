package com.mermaid.chat.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Objects;

/**
 * What the assistant asks the UI to do (spec §2-11).
 *
 * <p>These are <i>fields inside the response</i>, not provider tool calls. A tool-call message
 * carries {@code tool_calls} and leaves {@code content} empty, so it can never also carry the
 * schema-constrained JSON we need. Modelling the intent as data sidesteps that entirely, and it
 * keeps the browser from ever seeing a provider-specific tool format.
 *
 * <p>A closed allowlist on purpose. The model cannot ask the UI to open an arbitrary URL, run
 * code, or navigate anywhere we did not name here.
 */
// `As.PROPERTY`, not `EXISTING_PROPERTY`. The latter assumes the object already carries the
// discriminator and therefore writes nothing on serialisation — and a record's extra `type()`
// method is not a record component, so Jackson never emits it either. The result parsed fine and
// serialised without `type`, which meant the frontend's `action.type === 'OPEN_FACILITY_MAP'`
// never matched and the map never opened. Caught by calling the live endpoint, not by a unit test.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = UiAction.OpenFacilityMap.class, name = "OPEN_FACILITY_MAP"),
    @JsonSubTypes.Type(value = UiAction.ApplyFacilityFilters.class, name = "APPLY_FACILITY_FILTERS"),
    @JsonSubTypes.Type(value = UiAction.OpenDrugDetail.class, name = "OPEN_DRUG_DETAIL"),
    @JsonSubTypes.Type(value = UiAction.ShowEmergencyCall.class, name = "SHOW_EMERGENCY_CALL"),
    @JsonSubTypes.Type(value = UiAction.AskClarifyingQuestion.class, name = "ASK_CLARIFYING_QUESTION"),
})
public sealed interface UiAction {

    /** For Java call sites. Jackson writes the discriminator itself, so keep it out of the JSON. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    String type();

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OpenFacilityMap(MapPayload payload) implements UiAction {
        public OpenFacilityMap {
            Objects.requireNonNull(payload, "payload");
        }

        @Override
        public String type() {
            return "OPEN_FACILITY_MAP";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ApplyFacilityFilters(MapPayload payload) implements UiAction {
        public ApplyFacilityFilters {
            Objects.requireNonNull(payload, "payload");
        }

        @Override
        public String type() {
            return "APPLY_FACILITY_FILTERS";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OpenDrugDetail(DrugPayload payload) implements UiAction {
        @Override
        public String type() {
            return "OPEN_DRUG_DETAIL";
        }
    }

    /** Required whenever {@code urgency.level == EMERGENCY} — post-processing invariant #4. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ShowEmergencyCall(EmergencyPayload payload) implements UiAction {
        @Override
        public String type() {
            return "SHOW_EMERGENCY_CALL";
        }

        public static ShowEmergencyCall korea119() {
            return new ShowEmergencyCall(new EmergencyPayload("119", "Call 119 (emergency services)"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AskClarifyingQuestion(QuestionPayload payload) implements UiAction {
        @Override
        public String type() {
            return "ASK_CLARIFYING_QUESTION";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MapPayload(List<String> types, boolean openNow, int radiusM) {
        public MapPayload {
            Objects.requireNonNull(types, "types");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DrugPayload(String drugId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmergencyPayload(String phone, String label) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record QuestionPayload(String question) {}
}
