package com.mermaid.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LlmPropertiesTest {

    private LlmProperties with(Duration timeout, Duration extraction) {
        return new LlmProperties("https://x", "k", "glm-5.2", timeout, extraction, Set.of("glm-5.2"));
    }

    @Test
    @DisplayName("the extraction budget is its own, and much smaller than the answer's")
    void usesItsOwnTimeout() {
        assertThat(with(Duration.ofSeconds(120), Duration.ofSeconds(30)).extractionTimeoutOrDefault())
                .isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("an unset extraction timeout falls back rather than becoming null")
    void fallsBackWhenUnset() {
        // `.timeout(null)` on a Mono throws. A missing line of YAML must not take the endpoint down.
        assertThat(with(Duration.ofSeconds(120), null).extractionTimeoutOrDefault())
                .isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    @DisplayName("a blank api key is not a configured provider")
    void blankKeyIsNotConfigured() {
        assertThat(props("  ", "m", Set.of()).isConfigured()).isFalse();
        assertThat(props(null, "m", Set.of()).isConfigured()).isFalse();
        assertThat(props("k", "m", Set.of()).isConfigured()).isTrue();
    }

    private LlmProperties props(String apiKey, String model, Set<String> structured) {
        return new LlmProperties("https://x", apiKey, model, Duration.ofSeconds(1), null, structured);
    }

    @Test
    @DisplayName("structured output is an allowlist — support cannot be guessed from the model name")
    void structuredOutputIsAnAllowlist() {
        assertThat(props("k", "glm-5.2", Set.of("glm-5.2", "kimi-k2.6")).supportsStructuredOutput()).isTrue();
        // deepseek-v4-flash answers 400 to the identical request. Measured, not assumed.
        assertThat(props("k", "deepseek-v4-flash", Set.of("glm-5.2")).supportsStructuredOutput()).isFalse();
    }

    @Test
    @DisplayName("an unset allowlist means no schema, not a crash")
    void missingAllowlistIsNotSupported() {
        assertThat(props("k", "glm-5.2", null).supportsStructuredOutput()).isFalse();
        assertThat(props("k", null, Set.of("glm-5.2")).supportsStructuredOutput()).isFalse();
    }
}
