package com.mermaid.chat.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The frontend switches on {@code action.type}. If it is missing from the JSON, the map silently
 * never opens — no exception, no log, just a feature that does not exist.
 *
 * <p>These tests exist because the original {@code EXISTING_PROPERTY} config round-tripped through
 * Jackson without error while dropping the discriminator on the way out.
 */
class UiActionSerdeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("serialising an action writes its `type` discriminator")
    void writesTypeDiscriminator() throws Exception {
        UiAction action =
                new UiAction.OpenFacilityMap(new UiAction.MapPayload(List.of("pharmacy"), true, 500));

        String json = mapper.writeValueAsString(action);

        assertThat(json).contains("\"type\":\"OPEN_FACILITY_MAP\"");
        assertThat(json).contains("\"radiusM\":500");
    }

    @Test
    @DisplayName("the emergency action serialises with its type too (invariant 4 depends on it)")
    void emergencyActionKeepsType() throws Exception {
        String json = mapper.writeValueAsString(UiAction.ShowEmergencyCall.korea119());

        assertThat(json).contains("\"type\":\"SHOW_EMERGENCY_CALL\"");
        assertThat(json).contains("119");
    }

    @Test
    @DisplayName("round-trip preserves the concrete subtype")
    void roundTrip() throws Exception {
        UiAction original =
                new UiAction.OpenFacilityMap(new UiAction.MapPayload(List.of("hospital"), false, 2000));

        UiAction back = mapper.readValue(mapper.writeValueAsString(original), UiAction.class);

        assertThat(back).isInstanceOf(UiAction.OpenFacilityMap.class);
        assertThat(((UiAction.OpenFacilityMap) back).payload().radiusM()).isEqualTo(2000);
    }

    @Test
    @DisplayName("`type` appears exactly once — not duplicated by the accessor")
    void noDuplicateTypeKey() throws Exception {
        String json = mapper.writeValueAsString(UiAction.ShowEmergencyCall.korea119());

        int count = json.split("\"type\"", -1).length - 1;
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("an action outside the allowlist fails to deserialise")
    void unknownTypeRejected() {
        String json = "{\"type\":\"NAVIGATE_TO_URL\",\"payload\":{\"url\":\"https://evil.example\"}}";

        assertThat(
                        org.assertj.core.api.Assertions.catchThrowable(
                                () -> mapper.readValue(json, UiAction.class)))
                .isNotNull();
    }
}
