package com.mermaid.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mermaid.chat.dto.MermAidAnswer;
import java.io.IOException;
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
    private final StructuredOutputFallback fallback;
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
            MermAidAnswer answer = emergencyTriage.emergencyAnswer(redFlag.get());
            return stream
                    ? streamOne(answer)
                    : ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(envelope(answer));
        }

        return stream
                ? streaming(request)
                : ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(blocking(request));
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
     * Bridges the WebClient {@code Flux} onto an {@link SseEmitter}.
     *
     * <p>We forward the upstream's terminal {@code data: [DONE]} verbatim — the {@code openai} SDK
     * watches for it to close the stream. It is not JSON, so nothing here may try to parse it.
     */
    private SseEmitter streaming(JsonNode request) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);

        chatProxyService
                .stream(request)
                .subscribe(
                        chunk -> {
                            try {
                                emitter.send(SseEmitter.event().data(chunk));
                            } catch (IOException e) {
                                // Client hung up mid-stream. Normal; stop pushing.
                                emitter.completeWithError(e);
                            }
                        },
                        error -> {
                            log.error("Upstream chat stream failed", error);
                            trySendErrorChunk(emitter);
                            emitter.complete();
                        },
                        emitter::complete);

        return emitter;
    }

    /**
     * Non-streaming path. The assistant's {@code content} is coerced into the response schema before
     * it reaches the browser, so a model that ignored the schema cannot crash the client (NFR-04).
     */
    private JsonNode blocking(JsonNode request) {
        JsonNode upstream = chatProxyService.complete(request).block();
        if (upstream == null) {
            return errorEnvelope();
        }

        JsonNode messageNode = upstream.path("choices").path(0).path("message");
        if (messageNode.isMissingNode()) {
            log.warn("Upstream returned no choices; shape={}", upstream.fieldNames());
            return errorEnvelope();
        }

        MermAidAnswer coerced = fallback.coerce(messageNode.path("content").asText(null));

        // Rewrite `content` in place so the envelope stays OpenAI-shaped for the SDK.
        ObjectNode rewritten = upstream.deepCopy();
        ObjectNode message = (ObjectNode) rewritten.path("choices").path(0).path("message");
        try {
            message.put("content", objectMapper.writeValueAsString(coerced));
        } catch (Exception e) {
            log.error("Could not serialise the coerced response", e);
            return errorEnvelope();
        }
        return rewritten;
    }

    private void trySendErrorChunk(SseEmitter emitter) {
        try {
            MermAidAnswer safe =
                    fallback.coerce("Sorry — I could not reach the assistant. Please try again.");
            emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(safe)));
            emitter.send(SseEmitter.event().data(ChatProxyService.DONE_SENTINEL));
        } catch (Exception ignored) {
            // The client is already gone. Nothing useful left to do.
        }
    }

    /** An OpenAI-shaped envelope carrying a safe fallback body. */
    private JsonNode errorEnvelope() {
        return envelope(fallback.coerce("Sorry — I could not reach the assistant. Please try again."));
    }

    /** Wraps an answer in the {@code chat.completion} envelope the {@code openai} SDK expects. */
    private JsonNode envelope(MermAidAnswer answer) {
        ObjectNode message = objectMapper.createObjectNode().put("role", "assistant");
        try {
            message.put("content", objectMapper.writeValueAsString(answer));
        } catch (Exception e) {
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
