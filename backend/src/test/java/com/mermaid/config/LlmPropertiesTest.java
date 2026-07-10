package com.mermaid.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LlmPropertiesTest {

    private LlmProperties with(Duration timeout, Duration extraction) {
        return new LlmProperties("https://x", "k", "glm-5.2", timeout, extraction);
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
        assertThat(new LlmProperties("https://x", "  ", "m", Duration.ofSeconds(1), null).isConfigured())
                .isFalse();
        assertThat(new LlmProperties("https://x", null, "m", Duration.ofSeconds(1), null).isConfigured())
                .isFalse();
        assertThat(new LlmProperties("https://x", "k", "m", Duration.ofSeconds(1), null).isConfigured())
                .isTrue();
    }
}
