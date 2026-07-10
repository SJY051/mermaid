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
public record LlmProperties(String baseUrl, String apiKey, String model, Duration timeout) {

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
