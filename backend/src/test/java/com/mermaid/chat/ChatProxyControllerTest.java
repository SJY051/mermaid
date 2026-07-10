package com.mermaid.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mermaid.chat.DrugContextRetriever.DrugContext;
import com.mermaid.chat.dto.MermAidAnswer;
import com.mermaid.chat.dto.UiAction;
import com.mermaid.common.SourceRef;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
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
            public DrugContext retrieve(String userText, Set<String> excludedIngredients) {
                return context;
            }
        };
        return new ChatProxyController(
                new FakeUpstream(modelReply),
                retriever,
                new StructuredOutputFallback(mapper),
                new AnswerValidator(),
                new EmergencyTriage(),
                mapper);
    }

    private static DrugContext contextWith(String... productNames) {
        return new DrugContext("DRUG_CONTEXT: …", Set.of(productNames), List.of(TYLENOL_SOURCE));
    }

    private static DrugContext emptyContext() {
        return new DrugContext("DRUG_CONTEXT: nothing", Set.of(), List.of());
    }

    private JsonNode request(String userText) {
        var messages = mapper.createArrayNode();
        messages.addObject().put("role", "user").put("content", userText);
        var req = mapper.createObjectNode();
        req.set("messages", messages);
        return req;
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

    private static String drugCard(String productNameKo, String sourceRefId) {
        return """
            [{"productNameKo":"%s","productNameEn":null,"ingredients":[],
              "indicationSummary":"fever","directionsSummary":"1일 4회","warnings":[],
              "prescriptionStatus":"otc",
              "allergyCheck":{"status":"no_match_found","matchedIngredients":[],"message":"ok"},
              "sourceRefId":"%s"}]
            """.formatted(productNameKo, sourceRefId);
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
                        public DrugContext retrieve(String userText, Set<String> excluded) {
                            throw new AssertionError("retrieval must not run for an emergency");
                        }
                    },
                    new StructuredOutputFallback(mapper),
                    new AnswerValidator(),
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
