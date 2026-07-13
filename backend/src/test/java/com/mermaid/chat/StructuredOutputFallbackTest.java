package com.mermaid.chat;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mermaid.chat.dto.MermAidAnswer;
import com.mermaid.chat.dto.UiAction;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

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
    @DisplayName("a null action element is rejected, never serialized to crash the browser (#60)")
    void rejectsNullActionElements() {
        // ChatScreen maps uiActions and reads action.type immediately; a null element crashes render
        // before the safety/disclaimer UI draws. The fail-closed guard must cover uiActions and the
        // nested urgency actions, not only drugs/guidance.
        String nullUiAction =
                """
                {"schemaVersion":"1.0","answerId":"a1","language":"en","dataStatus":"live",
                 "urgency":{"level":"routine","title":"t","message":"m","reasonCodes":[],"actions":[]},
                 "summary":"ok","clarifyingQuestions":[],"guidance":[],
                 "drugs":[],"uiActions":[null],"sourceRefs":[],"warnings":[],"disclaimer":"d"}
                """;
        assertThat(fallback.coerce(nullUiAction).answerId()).isEqualTo("local-fallback");

        String nullUrgencyAction =
                """
                {"schemaVersion":"1.0","answerId":"a1","language":"en","dataStatus":"live",
                 "urgency":{"level":"routine","title":"t","message":"m","reasonCodes":[],"actions":[null]},
                 "summary":"ok","clarifyingQuestions":[],"guidance":[],
                 "drugs":[],"uiActions":[],"sourceRefs":[],"warnings":[],"disclaimer":"d"}
                """;
        assertThat(fallback.coerce(nullUrgencyAction).answerId()).isEqualTo("local-fallback");
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
    @DisplayName("a null drug element fails closed instead of reaching the validator")
    void rejectsNullDrugElement() {
        MermAidAnswer r = fallback.coerce(
                """
                {"schemaVersion":"1.0","answerId":"a1","language":"en","dataStatus":"unavailable",
                 "urgency":{"level":"routine","title":"t","message":"m","reasonCodes":[],"actions":[]},
                 "summary":"Do not expose this model answer.","clarifyingQuestions":[],"guidance":[],
                 "drugs":[
                   {"id":"d1","productNameKo":"검증된약","productNameEn":"Verified Drug",
                    "ingredients":[],"indicationSummary":"i","directionsSummary":"d","warnings":[],
                    "prescriptionStatus":"otc",
                    "allergyCheck":{"status":"unknown","matchedIngredients":[],"message":"m"},
                    "sourceRefId":"src:1"},
                   null],
                 "uiActions":[],"sourceRefs":[],"warnings":[],"disclaimer":"d"}
                """);

        assertThat(r.answerId()).isEqualTo("local-fallback");
        assertThat(r.summary()).contains("could not verify").doesNotContain("Do not expose");
        assertThat(r.drugs()).isEmpty();
    }

    @Test
    @DisplayName("a null guidance element fails closed independently of the drug list")
    void rejectsNullGuidanceElement() {
        MermAidAnswer r = fallback.coerce(
                """
                {"schemaVersion":"1.0","answerId":"a1","language":"en","dataStatus":"unavailable",
                 "urgency":{"level":"routine","title":"t","message":"m","reasonCodes":[],"actions":[]},
                 "summary":"","clarifyingQuestions":[],
                 "guidance":[
                   {"id":"g1","title":"t","body":"b","evidence":"general_safety","sourceRefIds":[]},
                   null],
                 "drugs":[],"uiActions":[],"sourceRefs":[],"warnings":[],"disclaimer":"d"}
                """);

        assertThat(r.answerId()).isEqualTo("local-fallback");
        assertThat(r.summary()).contains("could not verify").doesNotContain("\"guidance\":[null]");
        assertThat(r.guidance()).isEmpty();
    }

    @Test
    @DisplayName("coercion failures are logged only as a stable code and count")
    void coercionFailuresDoNotLeakModelText() {
        Logger logger = (Logger) LoggerFactory.getLogger(StructuredOutputFallback.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            fallback.coerce(
                    "{\"summary\":\"hi\",\"uiActions\":[{\"type\":"
                            + "\"LEAK_SENTINEL\\r\\nFORGED_LOG_LINE\",\"payload\":{}}]}");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        List<ILoggingEvent> warnings = appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .toList();
        assertThat(warnings).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage())
                    .isEqualTo("model_answer_rejected code=COERCION_FAILED count=1");
            assertThat(Arrays.deepToString(event.getArgumentArray()))
                    .doesNotContain("LEAK_SENTINEL", "FORGED_LOG_LINE", "\r", "\n");
        });
    }

    @Test
    @DisplayName("unknown extra fields from a chatty model are ignored, not fatal")
    void ignoresUnknownFields() {
        MermAidAnswer r = fallback.coerce("{\"summary\":\"hi\",\"confidence\":0.9,\"sources\":[]}");
        assertThat(r.summary()).isEqualTo("hi");
    }
}
