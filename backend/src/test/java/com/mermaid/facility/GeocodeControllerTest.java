package com.mermaid.facility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mermaid.common.GlobalExceptionHandler;
import com.mermaid.common.RequestIdFilter;
import com.mermaid.config.NaverMapProperties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class GeocodeControllerTest {

    private static final String BASE_URL =
            "https://maps.apigw.ntruss.com/map-geocode/v2/geocode";

    @Test
    @DisplayName("a blank query is INVALID_REQUEST before Naver is called")
    void rejectsBlankBeforeCallingUpstream() throws Exception {
        AtomicInteger upstreamCalls = new AtomicInteger();
        MockMvc mvc = mvc(upstreamCalls, "secret", HttpStatus.OK, okBody());

        mvc.perform(get("/api/v1/geocode").param("query", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(upstreamCalls).hasValue(0);
    }

    @Test
    @DisplayName("an over-long query is INVALID_REQUEST before Naver is called")
    void rejectsOverLongBeforeCallingUpstream() throws Exception {
        AtomicInteger upstreamCalls = new AtomicInteger();
        MockMvc mvc = mvc(upstreamCalls, "secret", HttpStatus.OK, okBody());

        mvc.perform(get("/api/v1/geocode").param("query", "x".repeat(201)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(upstreamCalls).hasValue(0);
    }

    @Test
    @DisplayName("the Naver secret and typed address stay out of the error response and logs")
    void keepsSecretAndAddressOutOfErrorPath() throws Exception {
        String secret = "SERVER_SECRET_SENTINEL";
        String address = "PRIVATE_HOME_ADDRESS_SENTINEL";
        AtomicInteger upstreamCalls = new AtomicInteger();
        MockMvc mvc =
                mvc(
                        upstreamCalls,
                        secret,
                        HttpStatus.BAD_GATEWAY,
                        "upstream echoed " + secret + " for " + address);

        Logger clientLogger = (Logger) LoggerFactory.getLogger(GeocodeClient.class);
        Logger handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> clientLogs = appender(clientLogger);
        ListAppender<ILoggingEvent> handlerLogs = appender(handlerLogger);
        try {
            MvcResult result =
                    mvc.perform(get("/api/v1/geocode").param("query", address))
                            .andExpect(status().isServiceUnavailable())
                            .andExpect(jsonPath("$.error.code").value("SOURCE_UNAVAILABLE"))
                            .andReturn();

            assertThat(result.getResponse().getContentAsString())
                    .doesNotContain(secret, address)
                    .contains("SOURCE_UNAVAILABLE");
            String renderedLogs =
                    Stream.concat(clientLogs.list.stream(), handlerLogs.list.stream())
                            .map(ILoggingEvent::getFormattedMessage)
                            .reduce("", (left, right) -> left + "\n" + right);
            assertThat(renderedLogs).doesNotContain(secret, address);
            assertThat(upstreamCalls).hasValue(1);
        } finally {
            detach(clientLogger, clientLogs);
            detach(handlerLogger, handlerLogs);
        }
    }

    private static MockMvc mvc(
            AtomicInteger calls, String secret, HttpStatus status, String responseBody) {
        WebClient webClient =
                WebClient.builder()
                        .exchangeFunction(
                                request -> {
                                    calls.incrementAndGet();
                                    return Mono.just(
                                            ClientResponse.create(status)
                                                    .header(
                                                            HttpHeaders.CONTENT_TYPE,
                                                            MediaType.APPLICATION_JSON_VALUE)
                                                    .body(responseBody)
                                                    .build());
                                })
                        .build();
        GeocodeClient client =
                new GeocodeClient(
                        webClient,
                        new NaverMapProperties("client-id", secret, BASE_URL));
        return MockMvcBuilders.standaloneSetup(new GeocodeController(client))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    private static String okBody() {
        return "{\"status\":\"OK\",\"addresses\":[]}";
    }

    private static ListAppender<ILoggingEvent> appender(Logger logger) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detach(Logger logger, ListAppender<ILoggingEvent> appender) {
        logger.detachAppender(appender);
        appender.stop();
    }
}
