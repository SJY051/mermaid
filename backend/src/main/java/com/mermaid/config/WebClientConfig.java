package com.mermaid.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * We run on Spring MVC (servlet), but use WebFlux's {@link WebClient} purely as a non-blocking HTTP
 * client — the standard way to consume an upstream SSE stream from an MVC app.
 */
@Configuration
public class WebClientConfig {

    /** Chat completions can carry long JSON payloads; the 256KB default is too small. */
    private static final int MAX_IN_MEMORY_BYTES = 4 * 1024 * 1024;

    @Bean
    WebClient llmWebClient(WebClient.Builder builder, LlmProperties props) {
        return builder.baseUrl(props.baseUrl())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BYTES))
                .build();
    }

    @Bean
    WebClient publicApiWebClient(WebClient.Builder builder) {
        // No baseUrl: each public API lives on a different host and we pass absolute URIs
        // built by PublicApiUriBuilder (which must not be re-encoded).
        return builder.codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BYTES)).build();
    }
}
