package com.mermaid.config;

import io.netty.resolver.DefaultAddressResolverGroup;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * We run on Spring MVC (servlet), but use WebFlux's {@link WebClient} purely as a non-blocking HTTP
 * client — the standard way to reach an upstream API from an MVC app.
 */
@Configuration
public class WebClientConfig {

    /** Chat completions can carry long JSON payloads; the 256KB default is too small. */
    private static final int MAX_IN_MEMORY_BYTES = 4 * 1024 * 1024;

    /**
     * Some OpenAI-compatible endpoints sit behind Cloudflare, which rejects unfamiliar clients with
     * <b>error 1010 ("access denied based on your browser's signature")</b> before the request ever
     * reaches the API. Verified against opencode zen: {@code Python-urllib/3.14} gets a 403 while
     * curl gets a 200. Reactor Netty's default UA is equally anonymous, so we name ourselves.
     */
    private static final String USER_AGENT = "mermAid/0.1 (+https://github.com/SJY051/mermaid)";

    /**
     * Resolve host names through the JDK rather than Netty's own DNS client.
     *
     * <p>Netty's native macOS resolver ships per-architecture, and the one Gradle pulls in is the
     * {@code osx-x86_64} build — on an Apple Silicon machine it fails to load, logs {@code
     * UnsatisfiedLinkError} at startup, and Netty falls back to a resolver that hands out the AAAA
     * record first. On a host with no IPv6 route that is fatal, and unlike curl, Netty does not retry
     * over IPv4: the request dies with {@code No route to host: opencode.ai/[2606:…]:443}. Observed
     * twice against the live provider while curl to the same host succeeded.
     *
     * <p>The JDK resolver honours the operating system's address ordering, which puts IPv4 first
     * unless {@code java.net.preferIPv6Addresses} says otherwise. It also silences the startup error.
     * Pinning a per-architecture native jar instead would fix one developer's laptop and break CI.
     */
    private static HttpClient httpClient() {
        return HttpClient.create().resolver(DefaultAddressResolverGroup.INSTANCE);
    }

    @Bean
    WebClient llmWebClient(WebClient.Builder builder, LlmProperties props) {
        return builder.baseUrl(props.baseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient()))
                .defaultHeader(org.springframework.http.HttpHeaders.USER_AGENT, USER_AGENT)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BYTES))
                .build();
    }

    @Bean
    WebClient publicApiWebClient(WebClient.Builder builder) {
        // No baseUrl: each public API lives on a different host and we pass absolute URIs
        // built by PublicApiUriBuilder (which must not be re-encoded).
        return builder.clientConnector(new ReactorClientHttpConnector(httpClient()))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BYTES))
                .build();
    }
}
