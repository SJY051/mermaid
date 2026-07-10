package com.mermaid.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mermaid.config.LlmProperties;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
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

    /**
     * OpenAI-compatible streams terminate with this literal. It is not JSON.
     *
     * <p>Observed against opencode zen: one more chunk arrives <i>after</i> {@code [DONE]} carrying
     * cost metadata ({@code {"choices":[],"cost":"0"}}). The {@code openai} JS SDK stops reading at
     * the sentinel, so anything after it is noise at best. We cut the stream there.
     */
    public static final String DONE_SENTINEL = "[DONE]";

    private final WebClient llmWebClient;
    private final LlmProperties llmProperties;
    private final SystemPromptProvider systemPromptProvider;
    private final ObjectMapper objectMapper;

    /**
     * Non-streaming call. The caller normalises {@code content} through StructuredOutputFallback.
     *
     * <p>There is deliberately no chunk-by-chunk relay. One existed, and the post-processing
     * invariants could not run on it — a streamed drug recommendation reached the browser unverified.
     * {@code ChatProxyController} now answers {@code stream=true} with a single validated SSE chunk.
     * Reinstating token streaming means buffering and validating first.
     */
    public Mono<JsonNode> complete(JsonNode clientRequest, List<String> extraSystemMessages) {
        ObjectNode upstream = prepare(clientRequest, false, extraSystemMessages);
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

    /**
     * A single schema-constrained turn, unrelated to the user's conversation. Pass 1 of the RAG flow
     * uses it to turn prose into search terms.
     *
     * <p>Nothing from the chat history is sent — only {@code userText}. The extraction prompt is not a
     * place where a long conversation can accumulate leverage over the model.
     *
     * @return the assistant's raw {@code content}, or empty if the provider failed or ignored us
     */
    public Mono<String> completeJson(String systemPrompt, String userText, String schemaName, JsonNode schema) {
        ObjectNode format = objectMapper.createObjectNode();
        format.put("type", "json_schema");
        ObjectNode jsonSchema = format.putObject("json_schema");
        jsonSchema.put("name", schemaName);
        jsonSchema.put("strict", true);
        jsonSchema.set("schema", schema);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", llmProperties.model());
        body.put("stream", false);
        body.set("response_format", format);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userText);

        return llmWebClient
                .post()
                .uri(COMPLETIONS_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + llmProperties.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(llmProperties.extractionTimeoutOrDefault())
                .mapNotNull(r -> r.path("choices").path(0).path("message").path("content").asText(null));
    }

    public static boolean wantsStream(JsonNode clientRequest) {
        return clientRequest.path("stream").asBoolean(false);
    }

    /** The newest user turn — what {@link EmergencyTriage} screens. Empty if there is none. */
    public static String lastUserMessage(JsonNode clientRequest) {
        String last = "";
        for (JsonNode m : clientRequest.path("messages")) {
            if ("user".equals(m.path("role").asText())) {
                last = m.path("content").asText("");
            }
        }
        return last;
    }

    /**
     * Rewrites the client's request into the one we actually send upstream.
     *
     * <p>The model is pinned server-side: letting a browser choose it would let anyone bill us for
     * the most expensive model on the endpoint.
     *
     * <p>{@code extraSystemMessages} carry the DRUG_CONTEXT that pass 1 retrieved. They go <i>after</i>
     * our system prompt and <i>before</i> the conversation, so the rules that constrain the context are
     * already in force when the model reads it.
     */
    private ObjectNode prepare(JsonNode clientRequest, boolean stream, List<String> extraSystemMessages) {
        ObjectNode req = clientRequest.deepCopy();

        req.put("model", llmProperties.model());
        req.put("stream", stream);
        // Our own extension field (see MermaidRequestExtension). Upstream providers reject unknown
        // top-level keys, and it is nobody's business but ours.
        req.remove(MermaidRequestExtension.FIELD);

        ArrayNode sanitized = objectMapper.createArrayNode();
        sanitized.add(systemMessage(systemPromptProvider.get()));
        for (String extra : extraSystemMessages) {
            sanitized.add(systemMessage(extra));
        }

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

        // TODO(BE-1, DEV-102): force the answer schema here with `req.set("response_format", …)`.
        //
        // glm-5.2 honours strict json_schema — measured, including `oneOf`, `$defs`, `const` and
        // `format` (see fixtures/README.md). Other models on the same endpoint reject it outright:
        // deepseek-v4-* and qwen3.7-max answer 400. So this cannot be switched on unconditionally
        // while the model is configurable; it needs a per-model capability flag.
        //
        // The validation schema is NOT the same artifact as the provider-coercion schema. The
        // server's runtime validator stays the source of truth either way. See spec §3.

        return req;
    }

    private ObjectNode systemMessage(String content) {
        return objectMapper.createObjectNode().put("role", "system").put("content", content);
    }
}
