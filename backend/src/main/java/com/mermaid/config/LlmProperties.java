package com.mermaid.config;

import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Upstream LLM provider settings.
 *
 * <p>Any OpenAI-compatible endpoint works: api.openai.com, opencode zen, a local runner.
 * The {@code apiKey} never leaves the server — see {@code ChatProxyController} (NFR-03).
 */
@ConfigurationProperties(prefix = "mermaid.llm")
public record LlmProperties(
        String baseUrl,
        String apiKey,
        String model,
        /** Pass 2 of the RAG flow: the model writes a whole grounded answer, thousands of characters. */
        Duration timeout,
        /** Pass 1a: the model writes two short arrays. It has no business taking as long. */
        Duration extractionTimeout,
        /** Models measured to accept {@code response_format: json_schema, strict: true}. */
        Set<String> structuredOutputModels) {

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Whether to send {@code response_format} at all.
     *
     * <p>An allowlist rather than a guess, because {@code model} is an environment variable and
     * support is not advertised anywhere: {@code glm-5.2} honours a strict schema, {@code
     * deepseek-v4-flash} answers 400 to the identical request, and {@code minimax-m3} answers 200 with
     * {@code <think>} prose. A model absent from this list simply does not get the schema — the system
     * prompt, {@code StructuredOutputFallback} and {@code AnswerValidator} still apply.
     */
    public boolean supportsStructuredOutput() {
        return model != null
                && structuredOutputModels != null
                && structuredOutputModels.contains(model);
    }

    /**
     * Falls back to the answer timeout when unset, so a partial config cannot hand the extractor a
     * null budget. Failing extraction is not fatal — it yields an empty drug context, and the answer
     * then names no medicine — but it should never happen because someone forgot a line of YAML.
     */
    public Duration extractionTimeoutOrDefault() {
        return extractionTimeout == null ? timeout : extractionTimeout;
    }
}
