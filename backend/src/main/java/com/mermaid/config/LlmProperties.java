package com.mermaid.config;

import java.time.Duration;
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
        Duration extractionTimeout) {

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
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
