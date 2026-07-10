package com.mermaid.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mermaid.config.LlmProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Proxies OpenAI-compatible chat requests to the upstream provider.
 *
 * <p>Two jobs, both security-shaped:
 *
 * <ol>
 *   <li>The API key lives here and only here. The browser sends a dummy one (NFR-03).
 *   <li>Any {@code system} message the client sent is dropped and replaced with ours, so a user
 *       cannot redefine the assistant's medical safety rules (NFR-03, SA-01).
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatProxyService {

    private static final String COMPLETIONS_PATH = "/chat/completions";
    /** OpenAI-compatible streams terminate with this literal. It is not JSON. */
    public static final String DONE_SENTINEL = "[DONE]";

    private final WebClient llmWebClient;
    private final LlmProperties llmProperties;
    private final SystemPromptProvider systemPromptProvider;
    private final ObjectMapper objectMapper;

    /** Relays the upstream SSE stream chunk by chunk. Each element is one {@code data:} payload. */
    public Flux<String> stream(JsonNode clientRequest) {
        ObjectNode upstream = prepare(clientRequest, true);
        return llmWebClient
                .post()
                .uri(COMPLETIONS_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + llmProperties.apiKey())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(upstream)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(llmProperties.timeout());
    }

    /** Non-streaming call. The caller normalises {@code content} through StructuredOutputFallback. */
    public Mono<JsonNode> complete(JsonNode clientRequest) {
        ObjectNode upstream = prepare(clientRequest, false);
        return llmWebClient
                .post()
                .uri(COMPLETIONS_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + llmProperties.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(upstream)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(llmProperties.timeout());
    }

    public static boolean wantsStream(JsonNode clientRequest) {
        return clientRequest.path("stream").asBoolean(false);
    }

    /**
     * Rewrites the client's request into the one we actually send upstream.
     *
     * <p>The model is pinned server-side: letting a browser choose it would let anyone bill us for
     * the most expensive model on the endpoint.
     */
    private ObjectNode prepare(JsonNode clientRequest, boolean stream) {
        ObjectNode req = clientRequest.deepCopy();

        req.put("model", llmProperties.model());
        req.put("stream", stream);

        ArrayNode sanitized = objectMapper.createArrayNode();
        sanitized.add(
                objectMapper
                        .createObjectNode()
                        .put("role", "system")
                        .put("content", systemPromptProvider.get()));

        int dropped = 0;
        for (JsonNode message : req.path("messages")) {
            if ("system".equals(message.path("role").asText())) {
                dropped++; // A client-supplied system message is an injection attempt, not a feature.
                continue;
            }
            sanitized.add(message);
        }
        if (dropped > 0) {
            log.warn("Dropped {} client-supplied system message(s) before proxying", dropped);
        }
        req.set("messages", sanitized);

        // TODO(team): once you have confirmed the chosen model honours it, force the schema here:
        //   req.set("response_format", jsonSchemaOf(MedicalResponse.class));
        // Support varies across the models on an OpenAI-compatible endpoint, so we rely on the
        // system prompt plus StructuredOutputFallback until someone verifies it with a real call.
        // See docs/specs/001-foundation/spec.md §3.

        return req;
    }
}
