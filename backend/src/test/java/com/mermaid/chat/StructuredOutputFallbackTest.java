package com.mermaid.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mermaid.chat.dto.MermAidAnswer;
import com.mermaid.chat.dto.UiAction;
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
                {"schemaVersion":"1.0","answerId":"a1","language":"en","dataStatus":"live",
                 "urgency":{"level":"routine","title":"Rest","message":"Take it easy.",
                            "reasonCodes":[],"actions":[]},
                 "summary":"Rest and fluids.","clarifyingQuestions":[],"guidance":[],
                 "drugs":[],"uiActions":[],"sourceRefs":[],"warnings":[],
                 "disclaimer":"Not medical advice."}
                """;

        MermAidAnswer r = fallback.coerce(json);

        assertThat(r.summary()).isEqualTo("Rest and fluids.");
        assertThat(r.urgency().level()).isEqualTo(MermAidAnswer.Urgency.Level.ROUTINE);
        assertThat(r.dataStatus()).isEqualTo(MermAidAnswer.DataStatus.LIVE);
    }

    @Test
    @DisplayName("plain prose becomes the summary instead of an exception (TC-03)")
    void coercesProse() {
        MermAidAnswer r = fallback.coerce("You should probably see a pharmacist.");

        assertThat(r.summary()).isEqualTo("You should probably see a pharmacist.");
        assertThat(r.disclaimer()).isEqualTo(StructuredOutputFallback.DISCLAIMER);
    }

    @Test
    @DisplayName("a prose fallback names no drug, cites no source, requests no action")
    void proseFallbackClaimsNothing() {
        MermAidAnswer r = fallback.coerce("Take some 타이레놀, it is completely safe.");

        // The model said a drug name. We do not repeat it as structured data, because nothing
        // verified it. The sentence survives as prose; the claim does not become a card.
        assertThat(r.drugs()).isEmpty();
        assertThat(r.sourceRefs()).isEmpty();
        assertThat(r.uiActions()).isEmpty();
        assertThat(r.dataStatus()).isEqualTo(MermAidAnswer.DataStatus.UNAVAILABLE);
    }

    @Test
    @DisplayName("JSON wrapped in a markdown fence is unwrapped")
    void stripsMarkdownFence() {
        String fenced =
                """
                ```json
                {"summary":"Take with water.","urgency":{"level":"routine","title":"t","message":"m",
                 "reasonCodes":[],"actions":[]}}
                ```
                """;

        MermAidAnswer r = fallback.coerce(fenced);

        assertThat(r.summary()).isEqualTo("Take with water.");
        assertThat(r.urgency().level()).isEqualTo(MermAidAnswer.Urgency.Level.ROUTINE);
    }

    @Test
    @DisplayName("a missing disclaimer is always filled in (SA-02)")
    void alwaysHasDisclaimer() {
        MermAidAnswer r = fallback.coerce("{\"summary\":\"hi\"}");
        assertThat(r.disclaimer()).isEqualTo(StructuredOutputFallback.DISCLAIMER);
    }

    @Test
    @DisplayName("an unknown urgency degrades upward, never downward")
    void unknownUrgencyFailsSafe() {
        MermAidAnswer r =
                fallback.coerce(
                        "{\"summary\":\"hi\",\"urgency\":{\"level\":\"mild_probably\",\"title\":\"t\","
                                + "\"message\":\"m\",\"reasonCodes\":[],\"actions\":[]}}");
        assertThat(r.urgency().level()).isEqualTo(MermAidAnswer.Urgency.Level.URGENT);
    }

    @Test
    @DisplayName("an unknown allergy status degrades to UNKNOWN, never to no_match_found")
    void unknownAllergyStatusFailsSafe() {
        assertThat(com.mermaid.chat.dto.AllergyCheck.Status.from("probably_fine"))
                .isEqualTo(com.mermaid.chat.dto.AllergyCheck.Status.UNKNOWN);
    }

    @Test
    @DisplayName("null and blank content produce a usable answer")
    void handlesEmpty() {
        assertThat(fallback.coerce(null).disclaimer()).isNotBlank();
        assertThat(fallback.coerce("   ").summary()).isNotBlank();
    }

    @Test
    @DisplayName("a ui_action parses into the sealed type the frontend expects")
    void parsesUiAction() {
        String json =
                """
                {"summary":"There is a pharmacy nearby.",
                 "uiActions":[{"type":"OPEN_FACILITY_MAP",
                               "payload":{"types":["pharmacy"],"openNow":true,"radiusM":500}}]}
                """;

        MermAidAnswer r = fallback.coerce(json);

        assertThat(r.uiActions()).hasSize(1);
        assertThat(r.uiActions().get(0)).isInstanceOf(UiAction.OpenFacilityMap.class);
        UiAction.OpenFacilityMap map = (UiAction.OpenFacilityMap) r.uiActions().get(0);
        assertThat(map.payload().radiusM()).isEqualTo(500);
        assertThat(map.payload().openNow()).isTrue();
    }

    @Test
    @DisplayName("an action type outside the allowlist does not parse into an action")
    void rejectsUnknownAction() {
        String json =
                """
                {"summary":"hi","uiActions":[{"type":"NAVIGATE_TO_URL",
                                              "payload":{"url":"https://evil.example"}}]}
                """;

        // Jackson cannot resolve the subtype, so coerce() falls back to a safe prose answer.
        // Either way, no action reaches the browser.
        MermAidAnswer r = fallback.coerce(json);
        assertThat(r.uiActions()).isEmpty();
    }

    @Test
    @DisplayName("unknown extra fields from a chatty model are ignored, not fatal")
    void ignoresUnknownFields() {
        MermAidAnswer r = fallback.coerce("{\"summary\":\"hi\",\"confidence\":0.9,\"sources\":[]}");
        assertThat(r.summary()).isEqualTo("hi");
    }
}
