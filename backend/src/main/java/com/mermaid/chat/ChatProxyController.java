package com.mermaid.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mermaid.chat.DrugContextRetriever.DrugContext;
import com.mermaid.chat.dto.MermAidAnswer;
import com.mermaid.chat.dto.UiAction;
import com.mermaid.common.SourceRef;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * OpenAI-compatible chat endpoint (FR-01, TC-01).
 *
 * <p>The frontend points the official {@code openai} JS SDK at this path via {@code baseURL} and
 * passes a dummy key. No custom parsing layer on either side.
 *
 * <p>Deliberately <i>not</i> the Vercel AI SDK protocol: {@code useChat} validates each SSE line
 * against its own {@code UIMessageChunk} schema and rejects OpenAI's {@code choices[].delta} shape.
 * See spec §2-3.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatProxyController {

    private static final long STREAM_TIMEOUT_MS = 120_000L;

    private final ChatProxyService chatProxyService;
    private final DrugContextRetriever drugContextRetriever;
    private final StructuredOutputFallback fallback;
    private final AnswerValidator answerValidator;
    private final EmergencyTriage emergencyTriage;
    private final ObjectMapper objectMapper;

    /**
     * One path, two content types, chosen by the request body rather than by {@code Accept}.
     *
     * <p>Before either branch, rule-based screening runs (SA-03). If it fires we answer from code and
     * never reach the model — because a live model, asked about crushing chest pain, replied {@code
     * urgency: "unknown"}. Someone having a heart attack does not get a second try.
     *
     * <p>The blocking branch returns a {@link ResponseEntity} with an explicit JSON content type. It
     * has to: with {@code produces} listing {@code text/event-stream} first and a bare {@code Object}
     * return, a client that sends no {@code Accept} header makes Spring try to write the JSON
     * envelope as an SSE stream, and the request dies with {@code
     * HttpMessageNotWritableException: No converter for ObjectNode}.
     */
    @PostMapping(
            path = "/completions",
            produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE})
    public Object completions(@RequestBody JsonNode request) {
        boolean stream = ChatProxyService.wantsStream(request);

        var redFlag = emergencyTriage.screen(ChatProxyService.lastUserMessage(request));
        if (redFlag.isPresent()) {
            log.warn("Emergency triage fired: {} — answering without calling the model", redFlag.get());
            return respond(emergencyTriage.emergencyAnswer(redFlag.get()), stream);
        }

        return respond(answer(request), stream);
    }

    /**
     * Both content types carry the same fully-validated answer.
     *
     * <p>{@code stream=true} used to relay the upstream token by token, and the post-processing
     * invariants could not run on it: the JSON is only parseable once the last chunk lands, by which
     * time the earlier ones are already on the wire. A streamed drug recommendation therefore reached
     * the browser unverified. It now arrives as one SSE chunk instead — the {@code openai} SDK reads
     * it identically, and no answer leaves this server unchecked. Progressive rendering is a feature;
     * an unvalidated medicine is a defect.
     */
    private Object respond(MermAidAnswer answer, boolean stream) {
        return stream
                ? streamOne(answer)
                : ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(envelope(answer));
    }

    /** Emits a complete answer as a single OpenAI-shaped SSE chunk, then {@code [DONE]}. */
    private SseEmitter streamOne(MermAidAnswer answer) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        try {
            ObjectNode delta = objectMapper.createObjectNode();
            delta.put("role", "assistant");
            delta.put("content", objectMapper.writeValueAsString(answer));

            ObjectNode choice = objectMapper.createObjectNode();
            choice.put("index", 0);
            choice.set("delta", delta);
            choice.putNull("finish_reason");

            ObjectNode chunk = objectMapper.createObjectNode();
            chunk.put("object", "chat.completion.chunk");
            chunk.set("choices", objectMapper.createArrayNode().add(choice));

            emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(chunk)));
            emitter.send(SseEmitter.event().data(ChatProxyService.DONE_SENTINEL));
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /**
     * The two-pass RAG flow (spec §2-2).
     *
     * <pre>
     *   pass 1  DrugContextRetriever  → the only medicines that exist, as far as this turn is concerned
     *   pass 2  the model             → summarises them, and nothing else
     *           StructuredOutputFallback → makes the content parseable
     *           ground()                 → replaces the model's provenance with the server's
     *           AnswerValidator          → makes it true
     * </pre>
     *
     * <p>Pass 1 is what gives invariant 6 its teeth. Before it existed, {@code retrievedProductNames}
     * was empty and every answer naming a medicine was refused — correct for a system that had
     * retrieved nothing, and useless.
     */
    private MermAidAnswer answer(JsonNode request) {
        String userText = ChatProxyService.lastUserMessage(request);
        DrugContext context =
                drugContextRetriever.retrieve(userText, MermaidRequestExtension.excludedIngredients(request));

        JsonNode upstream;
        long startedAt = System.nanoTime();
        try {
            upstream = chatProxyService.complete(request, List.of(context.systemMessage())).block();
        } catch (RuntimeException e) {
            // A slow or unreachable provider is our problem, not the user's. Reactor wraps the
            // timeout, so this is where it lands: without the catch, a model that thinks for too long
            // reports INTERNAL_ERROR and tells a sick person the server is broken.
            log.error("Upstream chat call failed after {}ms", elapsedMs(startedAt), e);
            return unreachable();
        }
        // Pass 2 is the slowest thing this server does, and its cost scales with how much the model
        // has to write — three grounded drug cards is several thousand characters. Log it, or the
        // next person to hit the timeout will start by suspecting the public APIs.
        log.info("RAG pass 2: model answered in {}ms ({} chars of context)",
                elapsedMs(startedAt), context.systemMessage().length());
        if (upstream == null) {
            return unreachable();
        }

        JsonNode messageNode = upstream.path("choices").path(0).path("message");
        if (messageNode.isMissingNode()) {
            log.warn("Upstream returned no choices; shape={}", upstream.fieldNames());
            return unreachable();
        }

        MermAidAnswer coerced = ground(fallback.coerce(messageNode.path("content").asText(null)), context);

        List<String> violations = answerValidator.validate(coerced, context.allowedProductNames());
        if (!violations.isEmpty()) {
            log.warn("Answer failed {} post-processing invariant(s); refusing it. {}",
                    violations.size(), violations);
            return fallback.safeAnswer(
                    "I could not verify that answer against official data, so I will not show it. "
                            + "Please describe your symptoms again, or visit a pharmacy.");
        }
        return coerced;
    }

    /**
     * Replaces the model's {@code sourceRefs} and {@code dataStatus} with the server's own.
     *
     * <p>Provenance is not the model's to author. It has no way of knowing whether a record came from
     * the live ministry API or from a fixture we replayed because the network was down, and a
     * hand-copied {@code retrievedAt} is a timestamp nobody checked. The server retrieved the data, so
     * the server says where it came from.
     *
     * <p>This narrows invariants 1 and 5 to what they should have been all along — <i>does the model's
     * citation point at a source we actually hold?</i> — and turns invariant 3 into a regression guard
     * over our own labelling rather than a gate on the model.
     */
    private MermAidAnswer ground(MermAidAnswer answer, DrugContext context) {
        return new MermAidAnswer(
                answer.schemaVersion(),
                answer.answerId(),
                answer.language(),
                dataStatusOf(context.sources()),
                answer.urgency(),
                answer.summary(),
                answer.clarifyingQuestions(),
                answer.guidance(),
                answer.drugs(),
                distinct(answer.uiActions()),
                context.sources(),
                answer.warnings(),
                answer.disclaimer());
    }

    /**
     * The same action, asked for twice, is one action.
     *
     * <p>A live answer really did carry {@code OPEN_FACILITY_MAP} three times — once per drug it
     * described — and the UI would have drawn three identical buttons. {@link UiAction} and its
     * payloads are records, so equality is structural and this is exact: two map requests with
     * different radii both survive.
     */
    private static List<UiAction> distinct(List<UiAction> actions) {
        return actions == null ? List.of() : actions.stream().distinct().toList();
    }

    /** No sources means we grounded nothing — {@code unavailable} is the honest word for that. */
    private static MermAidAnswer.DataStatus dataStatusOf(List<SourceRef> sources) {
        if (sources.isEmpty()) {
            return MermAidAnswer.DataStatus.UNAVAILABLE;
        }
        boolean anyFixture = sources.stream().anyMatch(s -> s.dataMode() == SourceRef.DataMode.FIXTURE);
        boolean anyLive = sources.stream().anyMatch(s -> s.dataMode() == SourceRef.DataMode.LIVE);
        if (anyFixture && anyLive) {
            return MermAidAnswer.DataStatus.MIXED;
        }
        return anyFixture ? MermAidAnswer.DataStatus.FIXTURE : MermAidAnswer.DataStatus.LIVE;
    }

    private MermAidAnswer unreachable() {
        return fallback.safeAnswer("Sorry — I could not reach the assistant. Please try again.");
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    /**
     * Wraps an answer in the {@code chat.completion} envelope the {@code openai} SDK expects.
     *
     * <p>The catch used to write {@code "{}"} and say nothing. A {@code SourceRef} carries an {@code
     * Instant}, so an ObjectMapper without {@code JavaTimeModule} turns every grounded answer into a
     * blank card — and the only trace was a blank card. If this ever fires, it must be loud.
     */
    private JsonNode envelope(MermAidAnswer answer) {
        ObjectNode message = objectMapper.createObjectNode().put("role", "assistant");
        try {
            message.put("content", objectMapper.writeValueAsString(answer));
        } catch (Exception e) {
            log.error("Could not serialise the answer; the client will render nothing", e);
            message.put("content", "{}");
        }
        ObjectNode choice = objectMapper.createObjectNode();
        choice.put("index", 0);
        choice.set("message", message);
        choice.put("finish_reason", "stop");

        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("object", "chat.completion");
        envelope.set("choices", objectMapper.createArrayNode().add(choice));
        return envelope;
    }
}
