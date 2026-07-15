package com.mermaid.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

class WebClientConfigTest {

    @Test
    @Timeout(2)
    void publicApiClientTimesOutWhenAnUpstreamAcceptsButNeverResponds() {
        DisposableServer server =
                HttpServer.create().port(0).handle((request, response) -> Mono.never()).bindNow();

        try {
            WebClient client =
                    new WebClientConfig()
                            .publicApiWebClient(WebClient.builder(), Duration.ofMillis(75));

            assertThatThrownBy(
                            () ->
                                    client.get()
                                            .uri("http://localhost:" + server.port() + "/never")
                                            .retrieve()
                                            .bodyToMono(String.class)
                                            .block())
                    .isInstanceOf(RuntimeException.class);
        } finally {
            server.disposeNow();
        }
    }
}
