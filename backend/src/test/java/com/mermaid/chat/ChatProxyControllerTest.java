package com.mermaid.chat;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mermaid.chat.DrugContextRetriever.DrugContext;
import com.mermaid.chat.DrugContextRetriever.GroundedDrug;
import com.mermaid.chat.dto.AllergyCheck;
import com.mermaid.chat.dto.MermAidAnswer;
import com.mermaid.chat.dto.UiAction;
import com.mermaid.common.SourceRef;
import com.mermaid.drug.IngredientNormalizer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Mono;

/**
 * The gates on the answer path, exercised end to end without Spring or a network.
 *
 * <p>The one that matters most: <b>invariant 6 was inert until the RAG flow existed.</b> With nothing
 * retrieved, {@code retrievedProductNames} was empty and any answer naming a drug was refused — so the
 * gate could never be observed doing its job. These tests retrieve something, then watch it reject a
 * medicine the model made up beside it.
 */
class ChatProxyControllerTest {

    private static final Instant WHEN = Instant.parse("2026-07-10T05:00:00Z");

    private static final SourceRef TYLENOL_SOURCE = new SourceRef(
            "src:mfds:202005623", "식품의약품안전처", "202005623",
            WHEN, SourceRef.DataMode.FIXTURE, "MFDS licence information");

    private static final String TYLENOL = "어린이타이레놀산160밀리그램(아세트아미노펜)";

    /**
     * The same shape Spring Boot auto-configures. It has to be: {@link SourceRef#retrievedAt} is an
     * {@code Instant}, and a mapper without {@code JavaTimeModule} fails to serialise the answer — the
     * client then receives an empty object and renders a blank card.
     */
    private final ObjectMapper mapper =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    // ── harness ────────────────────────────────────────────────────────────────────────────────

    /** Captures the messages we would have sent upstream, and replies with whatever the test wants. */
    private static final class FakeUpstream extends ChatProxyService {
        private final String replyContent;
        final AtomicReference<JsonNode> sentRequest = new AtomicReference<>();

        FakeUpstream(String replyContent) {
            super(null, null, null, null, new ObjectMapper());
            this.replyContent = replyContent;
        }

        @Override
        public Mono<JsonNode> complete(JsonNode clientRequest, List<String> extraSystemMessages) {
            ObjectMapper m = new ObjectMapper();
            var envelope = m.createObjectNode();
            var message = m.createObjectNode().put("role", "assistant").put("content", replyContent);
            var choice = m.createObjectNode().put("index", 0);
            choice.set("message", message);
            envelope.set("choices", m.createArrayNode().add(choice));
            sentRequest.set(clientRequest);
            return Mono.just(envelope);
        }
    }

    private ChatProxyController controller(String modelReply, DrugContext context) {
        var retriever = new DrugContextRetriever(null, null, null, mapper) {
            @Override
            public DrugContext retrieve(
                    String userText, String allUserText, MermaidRequestExtension.StructuredExclusions exclusions) {
                return context;
            }
        };
        return new ChatProxyController(
                new FakeUpstream(modelReply),
                retriever,
                new StructuredOutputFallback(mapper),
                new AnswerValidator(new IngredientNormalizer()),
                new EmergencyTriage(),
                mapper);
    }

    private static DrugContext contextWith(String... productNames) {
        return contextWith(AllergyCheck.noMatch(), productNames);
    }

    /** The same, but carrying the server's own allergy verdict for each retrieved product. */
    private static DrugContext contextWith(AllergyCheck serverCheck, String... productNames) {
        return contextWithDosage(serverCheck, null, productNames);
    }

    /** The same, and carrying the ministry's 용법용량 — what the model's directions are checked against. */
    private static DrugContext contextWithDosage(
            AllergyCheck serverCheck, String officialDosageKo, String... productNames) {
        Map<String, GroundedDrug> grounded = new LinkedHashMap<>();
        for (String productName : productNames) {
            grounded.put(
                    productName,
                    new GroundedDrug(
                            TYLENOL_SOURCE.id(), Set.of(), serverCheck, officialDosageKo));
        }
        return new DrugContext("DRUG_CONTEXT: …", grounded, List.of(TYLENOL_SOURCE));
    }

    private static DrugContext emptyContext() {
        return new DrugContext("DRUG_CONTEXT: nothing", Map.of(), List.of());
    }

    private JsonNode request(String userText) {
        var messages = mapper.createArrayNode();
        messages.addObject().put("role", "user").put("content", userText);
        var req = mapper.createObjectNode();
        req.set("messages", messages);
        return req;
    }

    private JsonNode requestWithUnverifiedAllergen(String userText, String allergen) {
        ObjectNode request = (ObjectNode) request(userText);
        request.putObject("mermaid").putArray("unverified_allergens").add(allergen);
        return request;
    }

    @SuppressWarnings("unchecked")
    private MermAidAnswer answerOf(Object response) throws Exception {
        JsonNode body = ((ResponseEntity<JsonNode>) response).getBody();
        String content = body.path("choices").path(0).path("message").path("content").asText();
        return mapper.readValue(content, MermAidAnswer.class);
    }

    private static String modelAnswer(String drugsJson, String sourceRefsJson) {
        return """
            {"schemaVersion":"1.0","answerId":"a1","language":"en","dataStatus":"live",
             "urgency":{"level":"routine","title":"t","message":"m","reasonCodes":[],"actions":[]},
             "summary":"Here is what I found.","clarifyingQuestions":[],"guidance":[],
             "drugs":%s,"uiActions":[],"sourceRefs":%s,"warnings":[],"disclaimer":"d"}
            """.formatted(drugsJson, sourceRefsJson);
    }

    @Test
    @DisplayName("the server-authored allergy clarification bypasses the model unchanged")
    void unresolvedAllergyCannotBeSuppressedOrRewordedByTheModel() throws Exception {
        MermAidAnswer answer = answerOf(controller(modelAnswer("[]", "[]"), DrugContext.allergyClarification())
                .completions(request("I am allergic but I do not know the ingredient name")));

        assertThat(answer.answerId()).isEqualTo("allergy-clarification");
        assertThat(answer.clarifyingQuestions()).containsExactly(AllergyClarification.QUESTION);
        assertThat(answer.drugs()).isEmpty();
    }

    @Test
    @DisplayName("FR-017: the server appends the unverified-allergen caveat to every final answer")
    void unverifiedAllergensAlwaysAppendServerCaveat() throws Exception {
        String replyWithModelWarning = modelAnswer("[]", "[]")
                .replace("\"warnings\":[]", "\"warnings\":[\"Model warning\"]");
        String caveat = ChatProxyController.unverifiedAllergenCaveat(Set.of("Yellow dye"));

        MermAidAnswer answer = answerOf(controller(replyWithModelWarning, emptyContext())
                .completions(requestWithUnverifiedAllergen("can I take this?", "Yellow dye")));

        assertThat(answer.warnings()).containsExactly("Model warning", caveat);
        assertThat(String.join(" ", answer.warnings())).doesNotContain("safe");

        MermAidAnswer emergency = answerOf(controller(replyWithModelWarning, emptyContext())
                .completions(requestWithUnverifiedAllergen(
                        "I have crushing chest pain and cannot breathe", "Yellow dye")));
        assertThat(emergency.warnings()).contains(caveat);
    }

    @Test
    @DisplayName("FR-017: the caveat names the typed allergens, so it cannot soften a verified block")
    void theCaveatNamesOnlyWhatTheUserTyped() throws Exception {
        // A session can carry both: ingredients picked from the reviewed list, and names typed free-
        // hand. A blanket "the named allergens were checked by name only" then sits beside a card the
        // server stamped BLOCKED for a reviewed ingredient, and reads as though that block, too, were
        // just a name we matched and should be confirmed before believing. It says which names it
        // means, and says the rest of the page still stands.
        String caveat = ChatProxyController.unverifiedAllergenCaveat(Set.of("Yellow dye"));

        assertThat(caveat)
                .contains("Yellow dye")
                .contains("you typed")
                .doesNotContain("safe");
        assertThat(caveat.toLowerCase()).contains("does not change any other warning");
    }

    @Test
    @DisplayName("the server's allergy verdict wins: a model cannot write no_match_found over it")
    void serverOwnsTheAllergyVerdict() throws Exception {
        // The model is handed the check and asked to carry it. Nothing stopped it from carrying it
        // wrongly — and a card that changes only `allergyCheck` keeps the same product, ingredients
        // and source, so every other invariant passes and the person reads "no match found" for a
        // drug the server blocked (§2-2). The card here does exactly that; the server overrules it.
        AllergyCheck serverBlocked = new AllergyCheck(
                AllergyCheck.Status.BLOCKED,
                List.of("Acetaminophen Granules"),
                "Contains Acetaminophen Granules, which you asked to avoid.");

        MermAidAnswer answer = answerOf(controller(
                        modelAnswer(drugCard(TYLENOL, "src:mfds:202005623"), "[]"),
                        contextWith(serverBlocked, TYLENOL))
                .completions(request("can I take 타이레놀?")));

        assertThat(answer.drugs()).hasSize(1);
        AllergyCheck shown = answer.drugs().get(0).allergyCheck();
        assertThat(shown.status()).isEqualTo(AllergyCheck.Status.BLOCKED);
        assertThat(shown.matchedIngredients()).containsExactly("Acetaminophen Granules");
        assertThat(shown.message()).doesNotContain("ok").doesNotContain("safe");
    }

    private static String drugCard(String productNameKo, String sourceRefId) {
        return drugCard(productNameKo, sourceRefId, "1일 4회");
    }

    private static String drugCard(String productNameKo, String sourceRefId, String directions) {
        return """
            [{"productNameKo":"%s","productNameEn":null,"ingredients":[],
              "indicationSummary":"fever","directionsSummary":%s,"warnings":[],
              "prescriptionStatus":"otc",
              "allergyCheck":{"status":"no_match_found","matchedIngredients":[],"message":"ok"},
              "sourceRefId":"%s"}]
            """.formatted(productNameKo, directions == null ? "null" : "\"" + directions + "\"", sourceRefId);
    }

    // ── invariant 7: the dose is the ministry's, verbatim, or there is no dose ─────────────────

    @Nested
    @DisplayName("the directions gate")
    class DirectionsGate {

        /** 식약처's real 용법용량 for 타이레놀정500밀리그람. Note the 12 — it is an AGE. */
        private static final String OFFICIAL = "만 12세 이상 소아 및 성인: 1회 1~2정씩 1일 3~4회 필요시 복용합니다.";

        @Test
        @DisplayName("the model's dose is discarded and the ministry's own text takes its place")
        void serverWritesTheDose() throws Exception {
            // The card keeps its real product, ingredients and source — every other invariant passes
            // — and tells the reader to take four times the label's dose, under a footer naming
            // 식약처. Being *told* not to invent a dose was never an invariant. The model's sentence
            // is not checked here; it is not read at all.
            MermAidAnswer answer = answerOf(controller(
                            modelAnswer(
                                    drugCard(TYLENOL, "src:mfds:202005623",
                                            "Take 8 tablets every 2 hours."),
                                    "[]"),
                            contextWithDosage(AllergyCheck.noMatch(), OFFICIAL, TYLENOL))
                    .completions(request("can I take 타이레놀?")));

            assertThat(answer.drugs()).hasSize(1);
            assertThat(answer.drugs().get(0).directionsSummary()).isEqualTo(OFFICIAL);
            // Degrade, don't annihilate: the rest of the card is grounded and stays.
            assertThat(answer.drugs().get(0).indicationSummary()).isEqualTo("fever");
        }

        @Test
        @DisplayName("a plausible dose that reuses a label number in the wrong role is discarded too")
        void reusedLabelNumberIsDiscarded() throws Exception {
            // This is the one that killed the first fix. "12" IS in the label — as 만 12세, an age —
            // so a number-membership check let "Take 12 tablets once daily" through. A digit does not
            // carry its role, and recovering the role means parsing Korean dosage prose, which is the
            // same defect in a lab coat. The model has no dosing authority at all now, so a sentence
            // that would have passed every check still never reaches the card.
            MermAidAnswer answer = answerOf(controller(
                            modelAnswer(
                                    drugCard(TYLENOL, "src:mfds:202005623",
                                            "Take 12 tablets once daily."),
                                    "[]"),
                            contextWithDosage(AllergyCheck.noMatch(), OFFICIAL, TYLENOL))
                    .completions(request("can I take 타이레놀?")));

            assertThat(answer.drugs().get(0).directionsSummary())
                    .isEqualTo(OFFICIAL)
                    .doesNotContain("12 tablets");
        }

        @Test
        @DisplayName("even a faithful translation is replaced — we do not ship a dose we cannot verify")
        void faithfulTranslationIsAlsoReplaced() throws Exception {
            // This one is CORRECT. It still goes: we have no way to tell it from the wrong one, and
            // "usually right" is not a property a dose may have. The pharmacist reads the Korean.
            MermAidAnswer answer = answerOf(controller(
                            modelAnswer(
                                    drugCard(TYLENOL, "src:mfds:202005623",
                                            "Adults and children over 12: 1-2 tablets, 3 to 4 times a day."),
                                    "[]"),
                            contextWithDosage(AllergyCheck.noMatch(), OFFICIAL, TYLENOL))
                    .completions(request("can I take 타이레놀?")));

            assertThat(answer.drugs().get(0).directionsSummary()).isEqualTo(OFFICIAL);
        }

        @Test
        @DisplayName("no ministry dosing text means no dose on the card at all")
        void withoutOfficialTextThereIsNoDose() throws Exception {
            // Nothing retrieved is not permission to guess. The card says so rather than going quiet.
            MermAidAnswer answer = answerOf(controller(
                            modelAnswer(
                                    drugCard(TYLENOL, "src:mfds:202005623", "Take 2 tablets 3 times a day."),
                                    "[]"),
                            contextWithDosage(AllergyCheck.noMatch(), null, TYLENOL))
                    .completions(request("can I take 타이레놀?")));

            assertThat(answer.drugs().get(0).directionsSummary()).isNull();
        }
    }

    // ── the hallucination gate ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("invariant 6, now that pass 1 gives it something to check against")
    class HallucinationGate {

        @Test
        @DisplayName("a drug we retrieved is shown")
        void retrievedDrugPasses() throws Exception {
            var response = controller(
                            modelAnswer(drugCard(TYLENOL, "src:mfds:202005623"), "[]"),
                            contextWith(TYLENOL))
                    .completions(request("I have a headache"));

            MermAidAnswer answer = answerOf(response);
            assertThat(answer.drugs()).hasSize(1);
            assertThat(answer.drugs().get(0).productNameKo()).isEqualTo(TYLENOL);
        }

        @Test
        @DisplayName("a drug we did not retrieve is refused, however plausible its name")
        void inventedDrugIsRefused() throws Exception {
            var response = controller(
                            modelAnswer(drugCard("타이레놀정500밀리그람", "src:mfds:202005623"), "[]"),
                            contextWith(TYLENOL))
                    .completions(request("I have a headache"));

            MermAidAnswer answer = answerOf(response);
            assertThat(answer.drugs()).isEmpty();
            assertThat(answer.summary()).contains("could not verify");
        }

        @Test
        @DisplayName("with nothing retrieved, naming any drug is refused")
        void emptyContextRefusesEveryDrug() throws Exception {
            var response = controller(
                            modelAnswer(drugCard(TYLENOL, "src:mfds:202005623"), "[]"), emptyContext())
                    .completions(request("hello"));

            assertThat(answerOf(response).drugs()).isEmpty();
        }

        @Test
        @DisplayName("with nothing retrieved, a drug-free reply is still delivered")
        void emptyContextStillAnswers() throws Exception {
            var response = controller(modelAnswer("[]", "[]"), emptyContext())
                    .completions(request("where is the nearest pharmacy?"));

            MermAidAnswer answer = answerOf(response);
            assertThat(answer.summary()).isEqualTo("Here is what I found.");
            assertThat(answer.drugs()).isEmpty();
        }
    }

    // ── provenance ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("the server owns provenance, not the model")
    class Provenance {

        @Test
        @DisplayName("the model's sourceRefs are discarded and replaced with what we retrieved")
        void sourceRefsAreOverwritten() throws Exception {
            String invented =
                    """
                    [{"id":"src:mfds:999","provider":"made up","recordId":"999",
                      "retrievedAt":"2020-01-01T00:00:00Z","dataMode":"live","title":"nonsense"}]
                    """;
            var response = controller(
                            modelAnswer(drugCard(TYLENOL, "src:mfds:202005623"), invented), contextWith(TYLENOL))
                    .completions(request("I have a headache"));

            assertThat(answerOf(response).sourceRefs()).containsExactly(TYLENOL_SOURCE);
        }

        @Test
        @DisplayName("a model claiming `live` over fixture data is corrected, not trusted")
        void dataStatusComesFromTheSources() throws Exception {
            // The reply says dataStatus=live. The one source we hold is a fixture.
            var response = controller(
                            modelAnswer(drugCard(TYLENOL, "src:mfds:202005623"), "[]"), contextWith(TYLENOL))
                    .completions(request("I have a headache"));

            assertThat(answerOf(response).dataStatus()).isEqualTo(MermAidAnswer.DataStatus.FIXTURE);
        }

        @Test
        @DisplayName("nothing grounded means `unavailable`, never `live`")
        void ungroundedIsUnavailable() throws Exception {
            var response = controller(modelAnswer("[]", "[]"), emptyContext())
                    .completions(request("hello"));

            assertThat(answerOf(response).dataStatus()).isEqualTo(MermAidAnswer.DataStatus.UNAVAILABLE);
        }

        @Test
        @DisplayName("the same action asked for three times is one action")
        void duplicateUiActionsCollapse() throws Exception {
            // A live answer really did carry OPEN_FACILITY_MAP once per drug it described.
            String threeMaps = """
                [{"type":"OPEN_FACILITY_MAP","payload":{"types":["pharmacy"],"openNow":true,"radiusM":1000}},
                 {"type":"OPEN_FACILITY_MAP","payload":{"types":["pharmacy"],"openNow":true,"radiusM":1000}},
                 {"type":"OPEN_FACILITY_MAP","payload":{"types":["pharmacy"],"openNow":true,"radiusM":1000}}]
                """;
            var response = controller(
                            modelAnswer("[]", "[]").replace("\"uiActions\":[]", "\"uiActions\":" + threeMaps),
                            emptyContext())
                    .completions(request("where is the nearest pharmacy?"));

            assertThat(answerOf(response).uiActions()).hasSize(1);
        }

        @Test
        @DisplayName("two map requests that differ are both kept")
        void differentActionsSurvive() throws Exception {
            String twoMaps = """
                [{"type":"OPEN_FACILITY_MAP","payload":{"types":["pharmacy"],"openNow":true,"radiusM":1000}},
                 {"type":"OPEN_FACILITY_MAP","payload":{"types":["pharmacy"],"openNow":true,"radiusM":3000}}]
                """;
            var response = controller(
                            modelAnswer("[]", "[]").replace("\"uiActions\":[]", "\"uiActions\":" + twoMaps),
                            emptyContext())
                    .completions(request("pharmacies near me"));

            assertThat(answerOf(response).uiActions()).hasSize(2);
        }

        @Test
        @DisplayName("a map action with null types is refused before it reaches the browser")
        void nullMapTypesAreRefused() throws Exception {
            String invalidMap =
                    """
                    [{"type":"OPEN_FACILITY_MAP",
                      "payload":{"types":null,"openNow":true,"radiusM":1000}}]
                    """;
            var response = controller(
                            modelAnswer("[]", "[]")
                                    .replace("\"uiActions\":[]", "\"uiActions\":" + invalidMap),
                            emptyContext())
                    .completions(request("where is the nearest pharmacy?"));

            MermAidAnswer answer = answerOf(response);
            assertThat(answer.answerId()).isEqualTo("local-fallback");
            assertThat(answer.summary()).contains("could not verify");
            assertThat(answer.uiActions()).isEmpty();
        }

        @Test
        @DisplayName("a drug citing a source id we do not hold is refused (invariant 1)")
        void danglingCitationIsRefused() throws Exception {
            var response = controller(
                            modelAnswer(drugCard(TYLENOL, "src:mfds:not-a-real-id"), "[]"), contextWith(TYLENOL))
                    .completions(request("I have a headache"));

            assertThat(answerOf(response).drugs()).isEmpty();
        }
    }

    // ── the paths that never reach the model ───────────────────────────────────────────────────

    @Nested
    @DisplayName("emergency screening still runs first")
    class Emergency {

        @Test
        @DisplayName("a red flag answers from code, with no drugs and a 119 action")
        void redFlagShortCircuits() throws Exception {
            var upstream = new FakeUpstream(modelAnswer("[]", "[]"));
            var controller = new ChatProxyController(
                    upstream,
                    new DrugContextRetriever(null, null, null, mapper) {
                        @Override
                        public DrugContext retrieve(
                                String userText, String allUserText, MermaidRequestExtension.StructuredExclusions exclusions) {
                            throw new AssertionError("retrieval must not run for an emergency");
                        }
                    },
                    new StructuredOutputFallback(mapper),
                    new AnswerValidator(new IngredientNormalizer()),
                    new EmergencyTriage(),
                    mapper);

            var response = controller.completions(request("crushing chest pain and I cannot breathe"));

            MermAidAnswer answer = answerOf(response);
            assertThat(answer.urgency().level()).isEqualTo(MermAidAnswer.Urgency.Level.EMERGENCY);
            assertThat(answer.drugs()).isEmpty();
            assertThat(answer.uiActions()).anyMatch(a -> a instanceof UiAction.ShowEmergencyCall);
            assertThat(upstream.sentRequest.get()).as("the model was never called").isNull();
        }
    }

    @Test
    @DisplayName("validation logs contain stable code counts, never model text or forged lines")
    void validationLogsContainCodesAndCountsOnly() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(ChatProxyController.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            String attack = modelAnswer("[]", "[]")
                    .replace(
                            "Here is what I found.",
                            "LEAK_SENTINEL\\r\\nFORGED https://evil.example")
                    .replace(
                            "\"guidance\":[]",
                            "\"guidance\":[{\"id\":\"g1\",\"title\":\"t\",\"body\":\"b\","
                                    + "\"evidence\":\"general_safety\",\"sourceRefIds\":"
                                    + "[\"LEAK_SOURCE_SENTINEL\\r\\nFORGED_SOURCE\"]}]");

            MermAidAnswer answer = answerOf(
                    controller(attack, emptyContext())
                            .completions(request("REQUEST_SENTINEL\r\nFORGED_REQUEST headache")));

            assertThat(answer.summary()).contains("could not verify");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage())
                    .doesNotContain(
                            "REQUEST_SENTINEL",
                            "LEAK_SENTINEL",
                            "LEAK_SOURCE_SENTINEL",
                            "FORGED",
                            "evil.example",
                            "\r",
                            "\n");
            assertThat(java.util.Arrays.deepToString(event.getArgumentArray()))
                    .doesNotContain(
                            "REQUEST_SENTINEL",
                            "LEAK_SENTINEL",
                            "LEAK_SOURCE_SENTINEL",
                            "FORGED",
                            "evil.example",
                            "\r",
                            "\n");
        });
        List<ILoggingEvent> warnings = appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .toList();
        assertThat(warnings).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage())
                    .contains("answer_validation_failed total=2")
                    .contains("INV5_GUIDANCE_SOURCE_UNKNOWN=1")
                    .contains("INV7_FORBIDDEN_MARKUP=1");
        });
    }

    @Nested
    @DisplayName("stream=true carries the same validated answer")
    class Streaming {

        @Test
        @DisplayName("a streamed request gets an SseEmitter, not a raw relay")
        void streamsOneValidatedChunk() {
            var req = (com.fasterxml.jackson.databind.node.ObjectNode) request("I have a headache");
            req.put("stream", true);

            Object response =
                    controller(modelAnswer(drugCard(TYLENOL, "src:mfds:202005623"), "[]"), contextWith(TYLENOL))
                            .completions(req);

            assertThat(response).isInstanceOf(SseEmitter.class);
        }
    }
}
