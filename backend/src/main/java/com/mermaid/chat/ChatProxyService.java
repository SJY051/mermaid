package com.mermaid.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mermaid.config.LlmProperties;
import java.util.List;
import java.util.StringJoiner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * Proxies OpenAI-compatible chat requests to the upstream provider.
 *
 * <p>Two jobs, both security-shaped:
 *
 * <ol>
 *   <li>The API key lives here and only here. The browser sends a dummy one (NFR-03).
 *   <li>Only client {@code user} and {@code assistant} messages are retained. Privileged, tool, and
 *       malformed roles are dropped before our own system rules are added (NFR-03, SA-01).
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatProxyService {

    private static final String COMPLETIONS_PATH = "/chat/completions";

    private static final String RESPONSE_FORMAT = "response_format";

    /** Application-owned output ceiling; this is a spend/surface bound, not a provider maximum. */
    private static final int MAX_OUTPUT_TOKENS = 8192;

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
    private final AnswerSchemaProvider answerSchema;
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
        boolean schemaEnforced = upstream.has(RESPONSE_FORMAT);

        return post(upstream)
                .onErrorResume(
                        e -> schemaEnforced && isBadRequest(e),
                        e -> {
                            // `model` is an environment variable and support for strict schemas is not
                            // advertised. A model missing from the allowlist should degrade, not take
                            // the chat endpoint down: the prompt and the validator still stand.
                            log.warn("Model '{}' rejected response_format; retrying without it. "
                                            + "Remove it from mermaid.llm.structured-output-models.",
                                    llmProperties.model());
                            return post(withoutSchema(upstream));
                        });
    }

    private Mono<JsonNode> post(ObjectNode body) {
        return llmWebClient
                .post()
                .uri(COMPLETIONS_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + llmProperties.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(llmProperties.timeout());
    }

    private static ObjectNode withoutSchema(ObjectNode body) {
        ObjectNode copy = body.deepCopy();
        copy.remove(RESPONSE_FORMAT);
        return copy;
    }

    static boolean isBadRequest(Throwable e) {
        return e instanceof WebClientResponseException w && w.getStatusCode() == HttpStatus.BAD_REQUEST;
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
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", llmProperties.model());
        body.put("stream", false);
        body.set(RESPONSE_FORMAT, responseFormat(schemaName, schema));
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

    /** The newest user turn — what pass 1 searches. Empty if there is none. */
    public static String lastUserMessage(JsonNode clientRequest) {
        String last = "";
        for (JsonNode m : clientRequest.path("messages")) {
            if ("user".equals(m.path("role").asText())) {
                last = messageText(m.path("content"));
            }
        }
        return last;
    }

    /** Every user turn that remains model-visible, joined for pre-model safety screening. */
    public static String userMessagesForSafety(JsonNode clientRequest) {
        StringJoiner text = new StringJoiner(" ");
        for (JsonNode message : clientRequest.path("messages")) {
            if (!"user".equals(message.path("role").asText())) {
                continue;
            }
            String content = messageText(message.path("content"));
            if (!content.isBlank()) {
                text.add(content);
            }
        }
        return text.toString();
    }

    /** OpenAI chat content is either a string or an array of text-part objects. */
    private static String messageText(JsonNode content) {
        if (content.isTextual()) {
            return content.asText();
        }
        if (!content.isArray()) {
            return "";
        }
        StringJoiner text = new StringJoiner(" ");
        for (JsonNode part : content) {
            if (part.isTextual()) {
                text.add(part.asText());
            } else if (part.path("text").isTextual()) {
                text.add(part.path("text").asText());
            }
        }
        return text.toString();
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
    ObjectNode prepare(JsonNode clientRequest, boolean stream, List<String> extraSystemMessages) {
        ObjectNode req = objectMapper.createObjectNode();
        req.put("model", llmProperties.model());
        req.put("stream", stream);
        req.put("n", 1);
        req.put("max_tokens", MAX_OUTPUT_TOKENS);
        req.put("store", false);
        // Tool fields are intentionally absent because this service does not expose provider tools.

        ArrayNode sanitized = objectMapper.createArrayNode();
        sanitized.add(systemMessage(systemPromptProvider.get()));
        for (String extra : extraSystemMessages) {
            sanitized.add(systemMessage(extra));
        }

        int dropped = 0;
        for (JsonNode message : clientRequest.path("messages")) {
            String role = message.path("role").asText();
            if (!"user".equals(role) && !"assistant".equals(role)) {
                // Only conversation roles cross this boundary. Developer, tool, malformed, and
                // disguised system roles are all client-controlled instruction channels.
                dropped++;
                continue;
            }
            sanitized.add(objectMapper
                    .createObjectNode()
                    .put("role", role)
                    .put("content", messageText(message.path("content"))));
        }
        if (dropped > 0) {
            log.warn("Dropped {} client message(s) with a disallowed role before proxying", dropped);
        }
        req.set("messages", sanitized);

        // Force the answer's shape when the configured model is known to honour it (DEV-102). This
        // constrains generation, not truth: AnswerValidator still runs on the result, because a model
        // obeying a schema perfectly can still name a medicine that does not exist.
        if (llmProperties.supportsStructuredOutput()) {
            req.set(RESPONSE_FORMAT, responseFormat(AnswerSchemaProvider.SCHEMA_NAME, answerSchema.get()));
        } else {
            log.debug("Model '{}' is not on the structured-output allowlist; relying on the prompt",
                    llmProperties.model());
        }

        return req;
    }

    /** OpenAI's {@code response_format} envelope for a strict JSON Schema. */
    private ObjectNode responseFormat(String schemaName, JsonNode schema) {
        ObjectNode format = objectMapper.createObjectNode();
        format.put("type", "json_schema");
        ObjectNode jsonSchema = format.putObject("json_schema");
        jsonSchema.put("name", schemaName);
        jsonSchema.put("strict", true);
        jsonSchema.set("schema", schema);
        return format;
    }

    private ObjectNode systemMessage(String content) {
        return objectMapper.createObjectNode().put("role", "system").put("content", content);
    }
}
