package com.mermaid.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Mono;

/** Direct safety, canonical records, fixed terminal states, and dormant legacy helpers. */
class ChatProxyControllerTest {

    private static final Instant WHEN = Instant.parse("2026-07-10T05:00:00Z");

    private static final SourceRef TYLENOL_SOURCE = new SourceRef(
            "src:mfds:202005623", "식품의약품안전처", "202005623",
            WHEN, SourceRef.DataMode.FIXTURE, "MFDS licence information");

    /** 식약처's 효능효과 for 타이레놀. The card's "For" box is a translation of THIS, and of nothing else. */
    private static final String OFFICIAL_EFFICACY = "감기로 인한 발열 및 동통, 두통, 신경통, 근육통에 사용합니다.";

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
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<JsonNode> sentRequest = new AtomicReference<>();

        FakeUpstream(String replyContent) {
            super(null, null, null, null, new ObjectMapper());
            this.replyContent = replyContent;
        }

        @Override
        public Mono<JsonNode> complete(JsonNode clientRequest, List<String> extraSystemMessages) {
            calls.incrementAndGet();
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

    private record ControllerHarness(ChatProxyController controller, FakeUpstream upstream) {}

    private ChatProxyController controller(String modelReply, DrugContext context) {
        return harness(modelReply, context).controller();
    }

    private ControllerHarness harness(String modelReply, DrugContext context) {
        var retriever = new DrugContextRetriever(null, null, null, mapper) {
            @Override
            public DrugContext retrieve(
                    String userText, String allUserText, MermaidRequestExtension.StructuredExclusions exclusions) {
                return context;
            }
        };
        FakeUpstream upstream = new FakeUpstream(modelReply);
        IngredientNormalizer normalizer = new IngredientNormalizer();
        AnswerValidator validator = new AnswerValidator(normalizer);
        ChatProxyController controller = new ChatProxyController(
                upstream,
                retriever,
                new StructuredOutputFallback(mapper),
                validator,
                new ServerAuthoredAnswerBuilder(normalizer, validator),
                new EmergencyTriage(),
                normalizer,
                mapper);
        return new ControllerHarness(controller, upstream);
    }

    private static DrugContext contextWith(String... productNames) {
        return contextWith(AllergyCheck.noMatch(), productNames);
    }

    /** The same, but carrying the server's own allergy verdict for each retrieved product. */
    private static DrugContext contextWith(AllergyCheck serverCheck, String... productNames) {
        return contextWithDosage(serverCheck, null, productNames);
    }

    /** The same, carrying the ministry's 용법용량 for the canonical card. */
    private static DrugContext contextWithDosage(
            AllergyCheck serverCheck, String officialDosageKo, String... productNames) {
        return contextWith(
                new GroundedDrug(
                        TYLENOL_SOURCE.id(),
                        Set.of(),
                        serverCheck,
                        "Tylenol",
                        List.of(),
                        officialDosageKo,
                        OFFICIAL_EFFICACY,
                        MermAidAnswer.DrugCard.PrescriptionStatus.OTC,
                        List.of(),
                        null),
                productNames);
    }

    /** The server's whole record for a product — the only input to a canonical card. */
    private static DrugContext contextWith(GroundedDrug record, String... productNames) {
        Map<String, GroundedDrug> grounded = new LinkedHashMap<>();
        for (String productName : productNames) {
            grounded.put(productName, record);
        }
        return new DrugContext("DRUG_CONTEXT: …", grounded, List.of(TYLENOL_SOURCE));
    }

    private static DrugContext emptyContext() {
        return new DrugContext("DRUG_CONTEXT: nothing", Map.of(), List.of());
    }

    private static DrugContext searchUnavailableContext() {
        return new DrugContext(
                "", Map.of(), List.of(), Optional.of(ServerAuthoredSearchUnavailableAnswer.answer()));
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

    private MermAidAnswer streamedAnswerOf(ChatProxyController controller, JsonNode request)
            throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(
                        new org.springframework.http.converter.StringHttpMessageConverter(
                                StandardCharsets.UTF_8),
                        new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(
                                mapper))
                .defaultResponseCharacterEncoding(StandardCharsets.UTF_8)
                .build();
        MvcResult started = mvc.perform(post("/api/v1/chat/completions")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .accept(org.springframework.http.MediaType.TEXT_EVENT_STREAM)
                        .content(mapper.writeValueAsBytes(request)))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.request()
                                .asyncStarted())
                .andReturn();
        String stream = mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String firstData = stream.lines()
                .filter(line -> line.startsWith("data:"))
                .findFirst()
                .orElseThrow();
        JsonNode chunk = mapper.readTree(firstData.substring("data:".length()).trim());
        String content = chunk.path("choices").path(0).path("delta").path("content").asText();
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
        ControllerHarness harness =
                harness(modelAnswer("[]", "[]"), DrugContext.allergyClarification());
        MermAidAnswer answer = answerOf(harness.controller()
                .completions(request("I am allergic but I do not know the ingredient name")));

        assertThat(answer.answerId()).isEqualTo("allergy-clarification");
        assertThat(answer.clarifyingQuestions()).containsExactly(AllergyClarification.QUESTION);
        assertThat(answer.drugs()).isEmpty();
        assertThat(harness.upstream().calls).as("allergy direct answer runs first").hasValue(0);
    }

    @Nested
    @DisplayName("server-authored empty and unavailable states")
    class ServerAuthoredTerminalStates {

        private static final String MODEL_SENTINEL = "MODEL_WHOLE_ANSWER_MUST_NOT_RUN";

        @Test
        @DisplayName("usable empty official context skips Pass 2 and returns the fixed empty answer")
        void usableEmptyContextSkipsPassTwo() throws Exception {
            ControllerHarness harness = harness(
                    modelAnswer(drugCard(TYLENOL, TYLENOL_SOURCE.id()), "[]")
                            .replace("Here is what I found.", MODEL_SENTINEL),
                    emptyContext());

            MermAidAnswer answer =
                    answerOf(harness.controller().completions(request("I have a headache")));

            assertThat(harness.upstream().calls).hasValue(0);
            assertThat(harness.upstream().sentRequest).hasValue(null);
            assertTerminalAnswer(answer, ServerAuthoredEmptyAnswer.answer());
            assertThat(answer.summary()).doesNotContain(MODEL_SENTINEL);
        }

        @Test
        @DisplayName("Pass 1 unavailable is distinct from a usable empty official result")
        void unavailableIsDistinctFromUsableEmpty() throws Exception {
            ControllerHarness emptyHarness = harness(modelAnswer("[]", "[]"), emptyContext());
            ControllerHarness unavailableHarness =
                    harness(modelAnswer("[]", "[]"), searchUnavailableContext());

            MermAidAnswer empty =
                    answerOf(emptyHarness.controller().completions(request("I have a headache")));
            MermAidAnswer unavailable = answerOf(
                    unavailableHarness.controller().completions(request("I have a headache")));

            assertThat(emptyHarness.upstream().calls).hasValue(0);
            assertThat(unavailableHarness.upstream().calls).hasValue(0);
            assertTerminalAnswer(empty, ServerAuthoredEmptyAnswer.answer());
            assertTerminalAnswer(unavailable, ServerAuthoredSearchUnavailableAnswer.answer());
            assertThat(unavailable.answerId()).isNotEqualTo(empty.answerId());
            assertThat(unavailable.summary()).isNotEqualTo(empty.summary());
        }

        @Test
        @DisplayName("JSON and SSE carry the same fixed answer for each terminal state")
        void jsonAndSseMatchForBothTerminalStates() throws Exception {
            for (DrugContext context : List.of(emptyContext(), searchUnavailableContext())) {
                ControllerHarness jsonHarness = harness(modelAnswer("[]", "[]"), context);
                ControllerHarness sseHarness = harness(modelAnswer("[]", "[]"), context);
                ObjectNode streamingRequest = (ObjectNode) request("I have a headache");
                streamingRequest.put("stream", true);

                MermAidAnswer json = answerOf(
                        jsonHarness.controller().completions(request("I have a headache")));
                MermAidAnswer sse =
                        streamedAnswerOf(sseHarness.controller(), streamingRequest);

                assertThat(sse).isEqualTo(json);
                assertThat(jsonHarness.upstream().calls).hasValue(0);
                assertThat(sseHarness.upstream().calls).hasValue(0);
            }
        }

        private void assertTerminalAnswer(MermAidAnswer actual, MermAidAnswer expected) {
            assertThat(actual).isEqualTo(expected);
            assertThat(actual.dataStatus()).isEqualTo(MermAidAnswer.DataStatus.UNAVAILABLE);
            assertThat(actual.urgency().level()).isEqualTo(MermAidAnswer.Urgency.Level.UNKNOWN);
            assertThat(actual.urgency().actions()).isEmpty();
            assertThat(actual.drugs()).isEmpty();
            assertThat(actual.guidance()).isEmpty();
            assertThat(actual.clarifyingQuestions()).isEmpty();
            assertThat(actual.uiActions()).isEmpty();
            assertThat(actual.sourceRefs()).isEmpty();
            assertThat(actual.warnings()).isEmpty();
            assertThat(actual.disclaimer()).isEqualTo(StructuredOutputFallback.DISCLAIMER);
        }
    }

    @Test
    @DisplayName("SA-08 suppression remains a server-authored direct answer before empty handling")
    void allergySuppressionPrecedesTheEmptyState() throws Exception {
        ControllerHarness jsonHarness = harness(
                modelAnswer("[]", "[]")
                        .replace("Here is what I found.", "MODEL_ALTERNATIVE_MUST_NOT_RUN"),
                DrugContext.allergySuppressed());
        ControllerHarness sseHarness = harness(
                modelAnswer("[]", "[]")
                        .replace("Here is what I found.", "MODEL_ALTERNATIVE_MUST_NOT_RUN"),
                DrugContext.allergySuppressed());
        ObjectNode streamingRequest = (ObjectNode) request("I have a headache");
        streamingRequest.put("stream", true);

        MermAidAnswer json =
                answerOf(jsonHarness.controller().completions(request("I have a headache")));
        MermAidAnswer sse = streamedAnswerOf(sseHarness.controller(), streamingRequest);

        assertThat(sse).isEqualTo(json).isEqualTo(AllergySuppressedAnswer.answer());
        assertThat(jsonHarness.upstream().calls).hasValue(0);
        assertThat(sseHarness.upstream().calls).hasValue(0);
        assertThat(json.answerId()).isNotEqualTo(ServerAuthoredEmptyAnswer.ANSWER_ID);
    }


    @Test
    @DisplayName("FR-017: the server appends the unverified-allergen caveat to every final answer")
    void unverifiedAllergensAlwaysAppendServerCaveat() throws Exception {
        String replyWithModelWarning = modelAnswer("[]", "[]")
                .replace("\"warnings\":[]", "\"warnings\":[\"Model warning\"]");
        String caveat = ChatProxyController.unverifiedAllergenCaveat(Set.of("Yellow dye"));

        ControllerHarness emptyHarness = harness(replyWithModelWarning, emptyContext());
        MermAidAnswer answer = answerOf(emptyHarness.controller()
                .completions(requestWithUnverifiedAllergen("can I take this?", "Yellow dye")));

        assertThat(emptyHarness.upstream().calls).hasValue(0);
        assertThat(answer.warnings()).containsExactly(caveat);
        assertThat(String.join(" ", answer.warnings())).doesNotContain("safe");

        MermAidAnswer emergency = answerOf(controller(replyWithModelWarning, emptyContext())
                .completions(requestWithUnverifiedAllergen(
                        "I have crushing chest pain and cannot breathe", "Yellow dye")));
        assertThat(emergency.warnings()).contains(caveat);

        ControllerHarness canonicalHarness = harness(replyWithModelWarning, contextWith(TYLENOL));
        MermAidAnswer canonical = answerOf(canonicalHarness
                .controller()
                .completions(requestWithUnverifiedAllergen("can I take this?", "Yellow dye")));
        assertThat(canonical.warnings()).containsExactly(caveat);
        assertThat(canonicalHarness.upstream().calls)
                .as("the server appends the caveat without whole-answer Pass 2")
                .hasValue(0);
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

    private static String drugCard(String productNameKo, String sourceRefId) {
        return drugCard(productNameKo, sourceRefId, "1일 4회");
    }

    private static String drugCard(String productNameKo, String sourceRefId, String directions) {
        return drugCard(productNameKo, sourceRefId, directions, null, "[]", "otc");
    }

    /** A model-authored card, in full — every field invariant 7 or 8 can overrule. */
    private static String drugCard(
            String productNameKo,
            String sourceRefId,
            String directions,
            String labelCautions,
            String warningsJson,
            String prescriptionStatus) {
        return """
            [{"productNameKo":"%s","productNameEn":null,"ingredients":[],
              "indicationSummary":"fever","directionsSummary":%s,"labelCautions":%s,
              "warnings":%s,
              "prescriptionStatus":"%s",
              "allergyCheck":{"status":"no_match_found","matchedIngredients":[],"message":"ok"},
              "sourceRefId":"%s"}]
            """.formatted(
                productNameKo,
                quoted(directions),
                quoted(labelCautions),
                warningsJson,
                prescriptionStatus,
                sourceRefId);
    }

    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    // ── option C server-record integration ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("canonical cards contain only fields owned by the server record")
    class ServerRecordGate {

        /**
         * The sentinel model card deliberately disagrees with the official record. Option C must not
         * call or copy it; the resulting card is assembled from the server record alone.
         */
        @Test
        @DisplayName("warnings and prescription status are copied from the official record")
        void canonicalCardCopiesWarningsAndPrescriptionStatus() throws Exception {
            GroundedDrug record = new GroundedDrug(
                    TYLENOL_SOURCE.id(),
                    Set.of(),
                    AllergyCheck.noMatch(),
                    "Tylenol",
                    List.of(),
                    null,
                    OFFICIAL_EFFICACY,
                    MermAidAnswer.DrugCard.PrescriptionStatus.PRESCRIPTION,
                    List.of("Do not take if you are pregnant."),
                    null);

            ControllerHarness harness = harness(
                    modelAnswer(
                            drugCard(TYLENOL, "src:mfds:202005623", null, null, "[]", "otc"),
                            "[]"),
                    contextWith(record, TYLENOL));
            MermAidAnswer answer =
                    answerOf(harness.controller().completions(request("can I take 타이레놀?")));

            assertThat(harness.upstream().calls).hasValue(0);
            MermAidAnswer.DrugCard card = answer.drugs().get(0);
            assertThat(card.warnings()).containsExactly("Do not take if you are pregnant.");
            assertThat(card.prescriptionStatus())
                    .isEqualTo(MermAidAnswer.DrugCard.PrescriptionStatus.PRESCRIPTION);
        }

        @Test
        @DisplayName("ingredient rows contain only the English names the official record holds")
        void canonicalIngredientsContainOnlyRetrievedFields() throws Exception {
            GroundedDrug record = new GroundedDrug(
                    TYLENOL_SOURCE.id(),
                    Set.of("acetaminophen"),
                    AllergyCheck.noMatch(),
                    "Tylenol",
                    List.of("Acetaminophen"),
                    null,
                    OFFICIAL_EFFICACY,
                    MermAidAnswer.DrugCard.PrescriptionStatus.OTC,
                    List.of(),
                    null);

            String card = """
                [{"productNameKo":"%s","productNameEn":null,
                  "ingredients":[{"nameKo":"이부프로펜","nameEn":"Acetaminophen",
                                  "normalizedKey":"acetaminophen","amount":"5000","unit":"mg"}],
                  "indicationSummary":"fever","directionsSummary":null,"labelCautions":null,
                  "warnings":[],"prescriptionStatus":"otc",
                  "allergyCheck":{"status":"no_match_found","matchedIngredients":[],"message":"ok"},
                  "sourceRefId":"src:mfds:202005623"}]
                """.formatted(TYLENOL);

            ControllerHarness harness = harness(modelAnswer(card, "[]"), contextWith(record, TYLENOL));
            MermAidAnswer answer =
                    answerOf(harness.controller().completions(request("can I take 타이레놀?")));

            assertThat(harness.upstream().calls).hasValue(0);
            MermAidAnswer.Ingredient shown = answer.drugs().get(0).ingredients().get(0);
            assertThat(shown.nameEn()).isEqualTo("Acetaminophen");
            assertThat(shown.nameKo()).isNull();
            assertThat(shown.amount()).isNull();
            assertThat(shown.unit()).isNull();
        }

        @Test
        @DisplayName("product and ingredient display names come from the official record")
        void displayNamesComeFromTheRecord() throws Exception {
            GroundedDrug record = new GroundedDrug(
                    TYLENOL_SOURCE.id(),
                    Set.of("acetaminophen"),
                    AllergyCheck.noMatch(),
                    "Tylenol 500mg",
                    List.of("Acetaminophen"),
                    null,
                    OFFICIAL_EFFICACY,
                    MermAidAnswer.DrugCard.PrescriptionStatus.OTC,
                    List.of(),
                    null);

            String card = """
                [{"productNameKo":"%s","productNameEn":"Advil",
                  "ingredients":[{"nameKo":null,"nameEn":"Acetaminophen Granules",
                                  "normalizedKey":"acetaminophen","amount":null,"unit":null}],
                  "indicationSummary":"fever","directionsSummary":null,"labelCautions":null,
                  "warnings":[],"prescriptionStatus":"otc",
                  "allergyCheck":{"status":"no_match_found","matchedIngredients":[],"message":"ok"},
                  "sourceRefId":"src:mfds:202005623"}]
                """.formatted(TYLENOL);

            ControllerHarness harness = harness(modelAnswer(card, "[]"), contextWith(record, TYLENOL));
            MermAidAnswer answer =
                    answerOf(harness.controller().completions(request("can I take 타이레놀?")));

            assertThat(harness.upstream().calls).hasValue(0);
            MermAidAnswer.DrugCard shown = answer.drugs().get(0);
            assertThat(shown.productNameEn()).isEqualTo("Tylenol 500mg");
            assertThat(shown.ingredients().get(0).nameEn()).isEqualTo("Acetaminophen");
        }

        @Test
        @DisplayName("a model card cannot influence the server-canonical product record")
        void modelCardCannotInfluenceCanonicalProduct() throws Exception {
            // Option C removes whole-answer Pass 2 whenever retrieval found a product. The malformed
            // card remains here as a sentinel: it must never be read, corrected, or shown. The server
            // builds the complete product row from the retrieved record instead.
            GroundedDrug record = new GroundedDrug(
                    TYLENOL_SOURCE.id(),
                    Set.of("acetaminophen"),
                    AllergyCheck.noMatch(),
                    "Tylenol 500mg",
                    List.of("Acetaminophen"),
                    null,
                    OFFICIAL_EFFICACY,
                    MermAidAnswer.DrugCard.PrescriptionStatus.OTC,
                    List.of(),
                    null);

            // Ibuprofen is not in this product. The card is otherwise perfect.
            String card = """
                [{"productNameKo":"%s","productNameEn":null,
                  "ingredients":[{"nameKo":null,"nameEn":"Ibuprofen",
                                  "normalizedKey":"ibuprofen","amount":null,"unit":null}],
                  "indicationSummary":"fever","directionsSummary":null,"labelCautions":null,
                  "warnings":[],"prescriptionStatus":"otc",
                  "allergyCheck":{"status":"no_match_found","matchedIngredients":[],"message":"ok"},
                  "sourceRefId":"src:mfds:202005623"}]
                """.formatted(TYLENOL);

            ControllerHarness harness = harness(modelAnswer(card, "[]"), contextWith(record, TYLENOL));
            MermAidAnswer answer =
                    answerOf(harness.controller().completions(request("can I take 타이레놀?")));

            assertThat(harness.upstream().calls).hasValue(0);
            assertThat(answer.drugs()).singleElement().satisfies(shown -> {
                assertThat(shown.productNameKo()).isEqualTo(TYLENOL);
                assertThat(shown.ingredients()).extracting(MermAidAnswer.Ingredient::nameEn)
                        .containsExactly("Acetaminophen");
            });
        }

        @Test
        @DisplayName("the canonical card leaves the English indication field unavailable")
        void canonicalIndicationIsNull() throws Exception {
            GroundedDrug record = new GroundedDrug(
                    TYLENOL_SOURCE.id(),
                    Set.of(),
                    AllergyCheck.noMatch(),
                    "Tylenol",
                    List.of(),
                    "만 12세 이상: 1회 1~2정",
                    OFFICIAL_EFFICACY,
                    MermAidAnswer.DrugCard.PrescriptionStatus.OTC,
                    List.of(),
                    null);

            String card = """
                [{"productNameKo":"%s","productNameEn":null,"ingredients":[],
                  "indicationSummary":"Take 8 tablets every 2 hours.",
                  "directionsSummary":null,"labelCautions":null,
                  "warnings":[],"prescriptionStatus":"otc",
                  "allergyCheck":{"status":"no_match_found","matchedIngredients":[],"message":"ok"},
                  "sourceRefId":"src:mfds:202005623"}]
                """.formatted(TYLENOL);

            ControllerHarness harness = harness(modelAnswer(card, "[]"), contextWith(record, TYLENOL));
            MermAidAnswer answer =
                    answerOf(harness.controller().completions(request("can I take 타이레놀?")));

            assertThat(harness.upstream().calls).hasValue(0);
            assertThat(answer.drugs().get(0).indicationSummary()).isNull();
        }

        @Test
        @DisplayName("even a plausible indication stays unavailable until record-scoped enrichment")
        void indicationEnrichmentStaysDisabledUntilOptionB() throws Exception {
            // This was the old positive Pass-2 case. Option C deliberately retires it: a plausible
            // model summary is still model-owned medical prose, so the field remains null until the
            // separately gated, record-scoped Option B contract exists.
            GroundedDrug record = new GroundedDrug(
                    TYLENOL_SOURCE.id(),
                    Set.of(),
                    AllergyCheck.noMatch(),
                    "Tylenol",
                    List.of(),
                    null,
                    OFFICIAL_EFFICACY,
                    MermAidAnswer.DrugCard.PrescriptionStatus.OTC,
                    List.of(),
                    null);

            String card = """
                [{"productNameKo":"%s","productNameEn":null,"ingredients":[],
                  "indicationSummary":"For fever, headache and muscle pain from a cold.",
                  "directionsSummary":null,"labelCautions":null,
                  "warnings":[],"prescriptionStatus":"otc",
                  "allergyCheck":{"status":"no_match_found","matchedIngredients":[],"message":"ok"},
                  "sourceRefId":"src:mfds:202005623"}]
                """.formatted(TYLENOL);

            ControllerHarness harness = harness(modelAnswer(card, "[]"), contextWith(record, TYLENOL));
            MermAidAnswer answer =
                    answerOf(harness.controller().completions(request("can I take 타이레놀?")));

            assertThat(harness.upstream().calls).hasValue(0);
            assertThat(answer.drugs().get(0).indicationSummary()).isNull();
        }

        @Test
        @DisplayName("a null ingredient from dormant Pass 2 cannot enter the fixed empty answer")
        void nullIngredientElementIsRefusedOnLegacyPath() throws Exception {
            // Keep the malformed fixture as a mutation guard: restoring whole-answer Pass 2 would
            // call the provider and expose this input to the dormant coercion/validator path.
            String card = """
                [{"productNameKo":"%s","productNameEn":null,
                  "ingredients":[null],
                  "indicationSummary":"fever","directionsSummary":null,"labelCautions":null,
                  "warnings":[],"prescriptionStatus":"otc",
                  "allergyCheck":{"status":"no_match_found","matchedIngredients":[],"message":"ok"},
                  "sourceRefId":"src:mfds:202005623"}]
                """.formatted(TYLENOL);

            ControllerHarness harness = harness(modelAnswer(card, "[]"), emptyContext());
            MermAidAnswer answer = answerOf(harness.controller()
                    .completions(request("can I take 타이레놀?")));

            assertThat(harness.upstream().calls).hasValue(0);
            assertThat(answer.answerId()).isEqualTo(ServerAuthoredEmptyAnswer.ANSWER_ID);
            assertThat(answer.drugs()).isEmpty();
            assertThat(answer.summary()).isEqualTo(ServerAuthoredEmptyAnswer.SUMMARY);
        }

        @Test
        @DisplayName("an official record with no DUR warning produces an empty warning list")
        void canonicalWarningsCanBeEmpty() throws Exception {
            GroundedDrug record = new GroundedDrug(
                    TYLENOL_SOURCE.id(),
                    Set.of(),
                    AllergyCheck.noMatch(),
                    "Tylenol",
                    List.of(),
                    null,
                    OFFICIAL_EFFICACY,
                    MermAidAnswer.DrugCard.PrescriptionStatus.OTC,
                    List.of(),
                    null);

            ControllerHarness harness = harness(
                    modelAnswer(
                            drugCard(
                                    TYLENOL,
                                    "src:mfds:202005623",
                                    null,
                                    null,
                                    "[\"Never take this with any other medicine.\"]",
                                    "otc"),
                            "[]"),
                    contextWith(record, TYLENOL));
            MermAidAnswer answer =
                    answerOf(harness.controller().completions(request("can I take 타이레놀?")));

            assertThat(harness.upstream().calls).hasValue(0);
            assertThat(answer.drugs().get(0).warnings()).isEmpty();
        }
    }

    // ── canonical dosing: the ministry's text verbatim, or no dose ─────────────────────────────

    @Nested
    @DisplayName("canonical cards preserve only the official Korean dosage")
    class DirectionsGate {

        /** 식약처's real 용법용량 for 타이레놀정500밀리그람. Note the 12 — it is an AGE. */
        private static final String OFFICIAL = "만 12세 이상 소아 및 성인: 1회 1~2정씩 1일 3~4회 필요시 복용합니다.";

        @Test
        @DisplayName("the ministry's dose is copied verbatim while English enrichment stays absent")
        void serverWritesTheDose() throws Exception {
            ControllerHarness harness = harness(
                    modelAnswer(
                            drugCard(
                                    TYLENOL,
                                    "src:mfds:202005623",
                                    "Take 8 tablets every 2 hours."),
                            "[]"),
                    contextWithDosage(AllergyCheck.noMatch(), OFFICIAL, TYLENOL));
            MermAidAnswer answer =
                    answerOf(harness.controller().completions(request("can I take 타이레놀?")));

            assertThat(harness.upstream().calls).hasValue(0);
            assertThat(answer.drugs()).hasSize(1);
            assertThat(answer.drugs().get(0).directionsSummary()).isEqualTo(OFFICIAL);
            assertThat(answer.drugs().get(0).indicationSummary()).isNull();
        }

        @Test
        @DisplayName("a model sentence reusing an official number cannot replace the Korean dosage")
        void modelNumberReuseCannotReplaceOfficialDose() throws Exception {
            ControllerHarness harness = harness(
                    modelAnswer(
                            drugCard(
                                    TYLENOL,
                                    "src:mfds:202005623",
                                    "Take 12 tablets once daily."),
                            "[]"),
                    contextWithDosage(AllergyCheck.noMatch(), OFFICIAL, TYLENOL));
            MermAidAnswer answer =
                    answerOf(harness.controller().completions(request("can I take 타이레놀?")));

            assertThat(harness.upstream().calls).hasValue(0);
            assertThat(answer.drugs().get(0).directionsSummary())
                    .isEqualTo(OFFICIAL)
                    .doesNotContain("12 tablets");
        }

        @Test
        @DisplayName("even a faithful model translation cannot replace the Korean dosage")
        void modelTranslationCannotReplaceOfficialDose() throws Exception {
            ControllerHarness harness = harness(
                    modelAnswer(
                            drugCard(
                                    TYLENOL,
                                    "src:mfds:202005623",
                                    "Adults and children over 12: 1-2 tablets, 3 to 4 times a day."),
                            "[]"),
                    contextWithDosage(AllergyCheck.noMatch(), OFFICIAL, TYLENOL));
            MermAidAnswer answer =
                    answerOf(harness.controller().completions(request("can I take 타이레놀?")));

            assertThat(harness.upstream().calls).hasValue(0);
            assertThat(answer.drugs().get(0).directionsSummary()).isEqualTo(OFFICIAL);
        }

        @Test
        @DisplayName("no ministry dosing text means no dose on the card at all")
        void withoutOfficialTextThereIsNoDose() throws Exception {
            ControllerHarness harness = harness(
                    modelAnswer(
                            drugCard(
                                    TYLENOL,
                                    "src:mfds:202005623",
                                    "Take 2 tablets 3 times a day."),
                            "[]"),
                    contextWithDosage(AllergyCheck.noMatch(), null, TYLENOL));
            MermAidAnswer answer =
                    answerOf(harness.controller().completions(request("can I take 타이레놀?")));

            assertThat(harness.upstream().calls).hasValue(0);
            assertThat(answer.drugs().get(0).directionsSummary()).isNull();
        }
    }

    // ── retrieved and empty-context boundaries ─────────────────────────────────────────────────

    @Nested
    @DisplayName("retrieved and empty contexts both stop at server-owned output")
    class HallucinationGate {

        @Test
        @DisplayName("a retrieved drug is shown without whole-answer Pass 2")
        void retrievedDrugUsesCanonicalPath() throws Exception {
            ControllerHarness harness = harness(
                    modelAnswer(drugCard(TYLENOL, "src:mfds:202005623"), "[]"),
                    contextWith(TYLENOL));

            MermAidAnswer answer =
                    answerOf(harness.controller().completions(request("I have a headache")));
            assertThat(harness.upstream().calls).hasValue(0);
            assertThat(answer.drugs()).hasSize(1);
            assertThat(answer.drugs().get(0).productNameKo()).isEqualTo(TYLENOL);
        }

        @Test
        @DisplayName("a model-invented drug is never read when a retrieved record exists")
        void inventedDrugCannotEnterCanonicalPath() throws Exception {
            ControllerHarness harness = harness(
                    modelAnswer(drugCard("타이레놀정500밀리그람", "src:mfds:202005623"), "[]"),
                    contextWith(TYLENOL));

            MermAidAnswer answer =
                    answerOf(harness.controller().completions(request("I have a headache")));
            assertThat(harness.upstream().calls).hasValue(0);
            assertThat(answer.drugs()).singleElement().extracting(MermAidAnswer.DrugCard::productNameKo)
                    .isEqualTo(TYLENOL);
        }

        @Test
        @DisplayName("with nothing retrieved, a model-named drug is never read")
        void emptyContextRefusesEveryDrug() throws Exception {
            ControllerHarness harness = harness(
                    modelAnswer(drugCard(TYLENOL, "src:mfds:202005623"), "[]"), emptyContext());
            var response = harness.controller().completions(request("hello"));

            MermAidAnswer answer = answerOf(response);
            assertThat(harness.upstream().calls).hasValue(0);
            assertThat(answer.answerId()).isEqualTo(ServerAuthoredEmptyAnswer.ANSWER_ID);
            assertThat(answer.drugs()).isEmpty();
        }

        @Test
        @DisplayName("with nothing retrieved, model prose is replaced by the fixed empty answer")
        void emptyContextStillAnswers() throws Exception {
            ControllerHarness harness = harness(modelAnswer("[]", "[]"), emptyContext());
            var response = harness.controller().completions(request("where is the nearest pharmacy?"));

            MermAidAnswer answer = answerOf(response);
            assertThat(harness.upstream().calls).hasValue(0);
            assertThat(answer.summary()).isEqualTo(ServerAuthoredEmptyAnswer.SUMMARY);
            assertThat(answer.drugs()).isEmpty();
        }
    }

    // ── option C: server canonical cards replace whole-answer Pass 2 ──────────────────────────

    @Nested
    @DisplayName("a retrieved drug context takes the server-canonical path")
    class ServerCanonicalPath {

        private static final String MODEL_SENTINEL = "MODEL_WHOLE_ANSWER_MUST_NOT_RUN";

        @Test
        @DisplayName("a non-empty context never invokes the whole-answer model")
        void nonEmptyContextSkipsWholeAnswerPassTwo() throws Exception {
            ControllerHarness harness = harness(
                    modelAnswer(drugCard(TYLENOL, TYLENOL_SOURCE.id()), "[]")
                            .replace("Here is what I found.", MODEL_SENTINEL),
                    contextWith(TYLENOL));

            harness.controller().completions(request("I have a headache"));

            assertThat(harness.upstream().calls)
                    .as("retrieved cards must not pay for or trust whole-answer Pass 2")
                    .hasValue(0);
            assertThat(harness.upstream().sentRequest).hasValue(null);
        }

        @Test
        @DisplayName("JSON receives the canonical server card and fixed server answer shell")
        void nonEmptyContextReturnsCanonicalServerAnswer() throws Exception {
            ControllerHarness harness = harness(
                    modelAnswer(drugCard(TYLENOL, TYLENOL_SOURCE.id()), "[]")
                            .replace("Here is what I found.", MODEL_SENTINEL),
                    contextWith(TYLENOL));

            MermAidAnswer answer =
                    answerOf(harness.controller().completions(request("I have a headache")));

            assertThat(answer.answerId()).isEqualTo(ServerAuthoredAnswerBuilder.ANSWER_ID);
            assertThat(answer.summary())
                    .isEqualTo(ServerAuthoredAnswerBuilder.SUMMARY)
                    .doesNotContain(MODEL_SENTINEL);
            assertThat(answer.urgency().level()).isEqualTo(MermAidAnswer.Urgency.Level.UNKNOWN);
            assertThat(answer.urgency().title())
                    .isEqualTo(ServerAuthoredAnswerBuilder.URGENCY_TITLE);
            assertThat(answer.urgency().message())
                    .isEqualTo(ServerAuthoredAnswerBuilder.URGENCY_MESSAGE);
            assertThat(answer.sourceRefs()).containsExactly(TYLENOL_SOURCE);
            assertThat(answer.disclaimer()).isEqualTo(StructuredOutputFallback.DISCLAIMER);
            assertThat(answer.drugs()).singleElement().satisfies(card -> {
                assertThat(card.id())
                        .isEqualTo(ServerAuthoredAnswerBuilder.CARD_ID_PREFIX + TYLENOL_SOURCE.id());
                assertThat(card.productNameKo()).isEqualTo(TYLENOL);
                assertThat(card.indicationSummary()).isNull();
                assertThat(card.labelCautions()).isNull();
                assertThat(card.sourceRefId()).isEqualTo(TYLENOL_SOURCE.id());
            });
            assertThat(harness.upstream().calls).hasValue(0);
        }

        @Test
        @DisplayName("a partial retrieved context fails closed without falling through to the model")
        void partialContextRefusesWithoutWholeAnswerPassTwo() throws Exception {
            GroundedDrug record = contextWith(TYLENOL).groundedDrugs().get(TYLENOL);
            List<DrugContext> partialContexts = List.of(
                    new DrugContext("DRUG_CONTEXT: partial", Map.of(TYLENOL, record), List.of()),
                    new DrugContext("DRUG_CONTEXT: partial", Map.of(), List.of(TYLENOL_SOURCE)));

            List<ControllerHarness> harnesses = partialContexts.stream()
                    .map(context -> harness(modelAnswer("[]", "[]"), context))
                    .toList();
            List<MermAidAnswer> answers = new java.util.ArrayList<>();
            for (ControllerHarness harness : harnesses) {
                answers.add(answerOf(harness.controller().completions(request("I have a headache"))));
            }

            assertThat(harnesses).allSatisfy(harness ->
                    assertThat(harness.upstream().calls)
                            .as("partial retrieved context must fail closed before Pass 2")
                            .hasValue(0));
            assertThat(answers).allSatisfy(answer -> {
                assertThat(answer.answerId()).isEqualTo("local-fallback");
                assertThat(answer.summary())
                        .isEqualTo(ServerAuthoredAnswerBuilder.INCONSISTENT_CONTEXT_SUMMARY);
                assertThat(answer.drugs()).isEmpty();
                assertThat(answer.sourceRefs()).isEmpty();
                assertThat(answer.uiActions()).isEmpty();
                assertThat(answer.urgency().actions()).isEmpty();
                assertThat(answer.disclaimer()).isEqualTo(StructuredOutputFallback.DISCLAIMER);
            });
        }

        @Test
        @DisplayName("an empty context stops before the retained legacy model path")
        void emptyContextStopsBeforeRetainedLegacyModelPath() throws Exception {
            String reply = modelAnswer("[]", "[]")
                    .replace("Here is what I found.", MODEL_SENTINEL);
            ControllerHarness harness = harness(reply, emptyContext());

            MermAidAnswer answer =
                    answerOf(harness.controller().completions(request("I have a headache")));

            assertThat(harness.upstream().calls).hasValue(0);
            assertThat(answer.summary())
                    .isEqualTo(ServerAuthoredEmptyAnswer.SUMMARY)
                    .doesNotContain(MODEL_SENTINEL);
            assertThat(answer.drugs()).isEmpty();
        }
    }

    // ── provenance ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("the server owns provenance, not the model")
    class Provenance {

        @Test
        @DisplayName("canonical answers carry the exact sources retrieved by the server")
        void canonicalSourceRefsComeFromRetrieval() throws Exception {
            String invented =
                    """
                    [{"id":"src:mfds:999","provider":"made up","recordId":"999",
                      "retrievedAt":"2020-01-01T00:00:00Z","dataMode":"live","title":"nonsense"}]
                    """;
            ControllerHarness harness = harness(
                    modelAnswer(drugCard(TYLENOL, "src:mfds:202005623"), invented),
                    contextWith(TYLENOL));
            MermAidAnswer answer =
                    answerOf(harness.controller().completions(request("I have a headache")));

            assertThat(harness.upstream().calls).hasValue(0);
            assertThat(answer.sourceRefs()).containsExactly(TYLENOL_SOURCE);
        }

        @Test
        @DisplayName("fixture sources always produce fixture status")
        void dataStatusComesFromTheSources() throws Exception {
            ControllerHarness harness = harness(
                    modelAnswer(drugCard(TYLENOL, "src:mfds:202005623"), "[]"),
                    contextWith(TYLENOL));
            MermAidAnswer answer =
                    answerOf(harness.controller().completions(request("I have a headache")));

            assertThat(harness.upstream().calls).hasValue(0);
            assertThat(answer.dataStatus()).isEqualTo(MermAidAnswer.DataStatus.FIXTURE);
        }

        @Test
        @DisplayName("nothing grounded means `unavailable`, never `live`")
        void ungroundedIsUnavailable() throws Exception {
            var response = controller(modelAnswer("[]", "[]"), emptyContext())
                    .completions(request("hello"));

            assertThat(answerOf(response).dataStatus()).isEqualTo(MermAidAnswer.DataStatus.UNAVAILABLE);
        }

        @Test
        @DisplayName("model-supplied duplicate map actions cannot enter an empty answer")
        void duplicateUiActionsCollapse() throws Exception {
            // A live answer really did carry OPEN_FACILITY_MAP once per drug it described.
            String threeMaps = """
                [{"type":"OPEN_FACILITY_MAP","payload":{"types":["pharmacy"],"openNow":true,"radiusM":1000}},
                 {"type":"OPEN_FACILITY_MAP","payload":{"types":["pharmacy"],"openNow":true,"radiusM":1000}},
                 {"type":"OPEN_FACILITY_MAP","payload":{"types":["pharmacy"],"openNow":true,"radiusM":1000}}]
                """;
            ControllerHarness harness = harness(
                    modelAnswer("[]", "[]").replace("\"uiActions\":[]", "\"uiActions\":" + threeMaps),
                    emptyContext());
            var response = harness.controller().completions(request("where is the nearest pharmacy?"));

            assertThat(harness.upstream().calls).hasValue(0);
            assertThat(answerOf(response).uiActions()).isEmpty();
        }

        @Test
        @DisplayName("model-supplied distinct map actions cannot enter an empty answer")
        void differentActionsSurvive() throws Exception {
            String twoMaps = """
                [{"type":"OPEN_FACILITY_MAP","payload":{"types":["pharmacy"],"openNow":true,"radiusM":1000}},
                 {"type":"OPEN_FACILITY_MAP","payload":{"types":["pharmacy"],"openNow":true,"radiusM":3000}}]
                """;
            ControllerHarness harness = harness(
                    modelAnswer("[]", "[]").replace("\"uiActions\":[]", "\"uiActions\":" + twoMaps),
                    emptyContext());
            var response = harness.controller().completions(request("pharmacies near me"));

            assertThat(harness.upstream().calls).hasValue(0);
            assertThat(answerOf(response).uiActions()).isEmpty();
        }

        @Test
        @DisplayName("a malformed model map action cannot enter the fixed empty answer")
        void nullMapTypesAreRefused() throws Exception {
            String invalidMap =
                    """
                    [{"type":"OPEN_FACILITY_MAP",
                      "payload":{"types":null,"openNow":true,"radiusM":1000}}]
                    """;
            ControllerHarness harness = harness(
                    modelAnswer("[]", "[]")
                            .replace("\"uiActions\":[]", "\"uiActions\":" + invalidMap),
                    emptyContext());
            var response = harness.controller().completions(request("where is the nearest pharmacy?"));

            MermAidAnswer answer = answerOf(response);
            assertThat(harness.upstream().calls).hasValue(0);
            assertThat(answer.answerId()).isEqualTo(ServerAuthoredEmptyAnswer.ANSWER_ID);
            assertThat(answer.summary()).isEqualTo(ServerAuthoredEmptyAnswer.SUMMARY);
            assertThat(answer.uiActions()).isEmpty();
        }

        @Test
        @DisplayName("a model-supplied source id cannot replace server provenance")
        void modelSourceIdCannotEnterCanonicalPath() throws Exception {
            ControllerHarness harness = harness(
                    modelAnswer(drugCard(TYLENOL, "src:mfds:not-a-real-id"), "[]"),
                    contextWith(TYLENOL));

            MermAidAnswer answer =
                    answerOf(harness.controller().completions(request("I have a headache")));
            assertThat(harness.upstream().calls).hasValue(0);
            assertThat(answer.drugs()).singleElement().extracting(MermAidAnswer.DrugCard::sourceRefId)
                    .isEqualTo(TYLENOL_SOURCE.id());
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
            IngredientNormalizer normalizer = new IngredientNormalizer();
            AnswerValidator validator = new AnswerValidator(normalizer);
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
                    validator,
                    new ServerAuthoredAnswerBuilder(normalizer, validator),
                    new EmergencyTriage(),
                    normalizer,
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
    @DisplayName("a skipped empty-context model answer writes neither its text nor validation logs")
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

            ControllerHarness harness = harness(attack, emptyContext());
            MermAidAnswer answer = answerOf(harness
                    .controller()
                    .completions(request("REQUEST_SENTINEL\r\nFORGED_REQUEST headache")));

            assertThat(harness.upstream().calls).hasValue(0);
            assertThat(answer.summary()).isEqualTo(ServerAuthoredEmptyAnswer.SUMMARY);
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
        assertThat(warnings).isEmpty();
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

        @Test
        @DisplayName("JSON and SSE carry the same canonical server answer")
        void jsonAndSseCarryTheSameCanonicalAnswer() throws Exception {
            String modelReply = modelAnswer(drugCard(TYLENOL, TYLENOL_SOURCE.id()), "[]")
                    .replace("Here is what I found.", "MODEL_STREAM_SENTINEL");
            ControllerHarness jsonHarness = harness(modelReply, contextWith(TYLENOL));
            ControllerHarness streamHarness = harness(modelReply, contextWith(TYLENOL));

            MermAidAnswer json = answerOf(
                    jsonHarness.controller().completions(request("I have a headache")));
            ObjectNode streamingRequest = (ObjectNode) request("I have a headache");
            streamingRequest.put("stream", true);
            MermAidAnswer streamed =
                    streamedAnswerOf(streamHarness.controller(), streamingRequest);

            assertThat(streamed).isEqualTo(json);
            assertThat(streamed.answerId()).isEqualTo(ServerAuthoredAnswerBuilder.ANSWER_ID);
            assertThat(streamed.summary()).isEqualTo(ServerAuthoredAnswerBuilder.SUMMARY);
            assertThat(jsonHarness.upstream().calls).hasValue(0);
            assertThat(streamHarness.upstream().calls).hasValue(0);
        }
    }
}
