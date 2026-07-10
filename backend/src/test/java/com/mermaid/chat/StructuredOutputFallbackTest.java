package com.mermaid.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mermaid.chat.dto.MedicalResponse;
import com.mermaid.facility.domain.FacilityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * We point the proxy at any OpenAI-compatible endpoint and swap models freely. Some of them will
 * ignore the schema. TC-03 says the client must survive that.
 */
class StructuredOutputFallbackTest {

    private final StructuredOutputFallback fallback = new StructuredOutputFallback(new ObjectMapper());

    @Test
    @DisplayName("well-formed JSON round-trips")
    void parsesValidJson() {
        String json =
                """
                {"reply":"Rest and fluids.","urgency":"self_care","medications":[],
                 "map":null,"disclaimer":"Not medical advice."}
                """;

        MedicalResponse r = fallback.coerce(json);

        assertThat(r.reply()).isEqualTo("Rest and fluids.");
        assertThat(r.urgency()).isEqualTo(MedicalResponse.Urgency.SELF_CARE);
        assertThat(r.map()).isNull();
    }

    @Test
    @DisplayName("plain prose becomes the reply instead of an exception (TC-03)")
    void coercesProse() {
        MedicalResponse r = fallback.coerce("You should probably see a pharmacist.");

        assertThat(r.reply()).isEqualTo("You should probably see a pharmacist.");
        assertThat(r.medications()).isEmpty();
        assertThat(r.map()).isNull();
        assertThat(r.disclaimer()).isEqualTo(StructuredOutputFallback.DISCLAIMER);
    }

    @Test
    @DisplayName("JSON wrapped in a markdown fence is unwrapped")
    void stripsMarkdownFence() {
        String fenced =
                """
                ```json
                {"reply":"Take with water.","urgency":"see_pharmacist"}
                ```
                """;

        MedicalResponse r = fallback.coerce(fenced);

        assertThat(r.reply()).isEqualTo("Take with water.");
        assertThat(r.urgency()).isEqualTo(MedicalResponse.Urgency.SEE_PHARMACIST);
    }

    @Test
    @DisplayName("a missing disclaimer is always filled in (SA-02)")
    void alwaysHasDisclaimer() {
        MedicalResponse r = fallback.coerce("{\"reply\":\"hi\"}");
        assertThat(r.disclaimer()).isEqualTo(StructuredOutputFallback.DISCLAIMER);
    }

    @Test
    @DisplayName("an unknown urgency degrades upward, never downward")
    void unknownUrgencyFailsSafe() {
        MedicalResponse r = fallback.coerce("{\"reply\":\"hi\",\"urgency\":\"mild_probably\"}");
        assertThat(r.urgency()).isEqualTo(MedicalResponse.Urgency.SEE_DOCTOR);
    }

    @Test
    @DisplayName("null and blank content produce a usable response")
    void handlesEmpty() {
        assertThat(fallback.coerce(null).disclaimer()).isNotBlank();
        assertThat(fallback.coerce("   ").reply()).isNotBlank();
    }

    @Test
    @DisplayName("a map directive parses into the shape the frontend expects")
    void parsesMapDirective() {
        String json =
                """
                {"reply":"There is a pharmacy nearby.","urgency":"see_pharmacist",
                 "map":{"type":"pharmacy","radiusMeters":500,"openNow":true,"reason":"late"}}
                """;

        MedicalResponse r = fallback.coerce(json);

        assertThat(r.map()).isNotNull();
        assertThat(r.map().type()).isEqualTo(FacilityType.PHARMACY);
        assertThat(r.map().radiusMeters()).isEqualTo(500);
        assertThat(r.map().openNow()).isTrue();
    }

    @Test
    @DisplayName("unknown extra fields from a chatty model are ignored, not fatal")
    void ignoresUnknownFields() {
        MedicalResponse r = fallback.coerce("{\"reply\":\"hi\",\"confidence\":0.9,\"sources\":[]}");
        assertThat(r.reply()).isEqualTo("hi");
    }
}
