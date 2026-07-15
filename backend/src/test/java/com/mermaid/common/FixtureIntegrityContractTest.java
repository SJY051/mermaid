package com.mermaid.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mermaid.config.DataModeProperties;
import com.mermaid.config.PublicApiProperties;
import com.mermaid.drug.DrugPermissionApiClient;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class FixtureIntegrityContractTest {

    private static final String MISSING_FIXTURE = "PRIVATE_FIXTURE_PATH_SENTINEL.json";
    private static final String PARSE_DETAIL = "PRIVATE_PARSE_DETAIL_SENTINEL";

    @Test
    @DisplayName("a missing local fixture is an integrity failure, not a government API outage")
    void missingFixtureHasLocalIntegrityType() {
        FixtureLoader loader = new FixtureLoader(new ObjectMapper());

        assertThatThrownBy(() -> loader.load(MISSING_FIXTURE))
                .isInstanceOf(FixtureIntegrityException.class)
                .isNotInstanceOf(PublicApiException.class)
                .satisfies(
                        error ->
                                assertThat(((FixtureIntegrityException) error).reason())
                                        .isEqualTo(FixtureIntegrityException.Reason.MISSING))
                .hasMessageNotContaining(MISSING_FIXTURE);
    }

    @Test
    @DisplayName("malformed fixture JSON is classified as corrupt without retaining parser values")
    void malformedFixtureHasLocalIntegrityType() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        when(mapper.readTree(any(InputStream.class))).thenThrow(new IOException(PARSE_DETAIL));
        FixtureLoader loader = new FixtureLoader(mapper);

        assertThatThrownBy(() -> loader.load("pharmacy.json"))
                .isInstanceOf(FixtureIntegrityException.class)
                .isNotInstanceOf(PublicApiException.class)
                .satisfies(
                        error -> {
                            FixtureIntegrityException fixtureError =
                                    (FixtureIntegrityException) error;
                            assertThat(fixtureError.reason())
                                    .isEqualTo(FixtureIntegrityException.Reason.CORRUPT);
                            assertThat(fixtureError.getMessage()).doesNotContain(PARSE_DETAIL);
                        });
    }

    @Test
    @DisplayName("a corrupt fixture logs only the bounded integrity reason")
    void corruptFixtureLogIsValueFree() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        when(mapper.readTree(any(InputStream.class))).thenThrow(new IOException(PARSE_DETAIL));
        FixtureLoader loader = new FixtureLoader(mapper);
        Logger loaderLogger = (Logger) LoggerFactory.getLogger(FixtureLoader.class);
        Logger handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        Level originalLoaderLevel = loaderLogger.getLevel();
        loaderLogger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> loaderLogs = appender(loaderLogger);
        ListAppender<ILoggingEvent> handlerLogs = appender(handlerLogger);
        try {
            MockMvc mvc =
                    MockMvcBuilders.standaloneSetup(new CorruptFixtureController(loader))
                            .setControllerAdvice(new GlobalExceptionHandler())
                            .addFilters(new RequestIdFilter())
                            .build();

            mvc.perform(get("/corrupt-fixture"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.error.retryable").value(false));

            List<ILoggingEvent> allLogs = new ArrayList<>(loaderLogs.list);
            allLogs.addAll(handlerLogs.list);
            assertThat(allLogs)
                    .anySatisfy(
                            event ->
                                    assertThat(event.getFormattedMessage())
                                            .isEqualTo(
                                                    "fixture_integrity_failure reason=CORRUPT"));
            assertThat(allLogs)
                    .allSatisfy(
                            event -> {
                                assertThat(event.getFormattedMessage())
                                        .doesNotContain(
                                                "pharmacy.json", "/fixtures/", PARSE_DETAIL);
                                assertThat(event.getThrowableProxy()).isNull();
                            });
        } finally {
            detach(loaderLogger, loaderLogs);
            detach(handlerLogger, handlerLogs);
            loaderLogger.setLevel(originalLoaderLevel);
        }
    }

    @Test
    @DisplayName("an empty fixture document is corrupt rather than an eventual null failure")
    void emptyFixtureDocumentIsCorrupt() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        when(mapper.readTree(any(InputStream.class))).thenReturn(null);
        FixtureLoader loader = new FixtureLoader(mapper);

        assertThatThrownBy(() -> loader.load("pharmacy.json"))
                .isInstanceOf(FixtureIntegrityException.class)
                .satisfies(
                        error ->
                                assertThat(((FixtureIntegrityException) error).reason())
                                        .isEqualTo(FixtureIntegrityException.Reason.CORRUPT));
    }

    @Test
    @DisplayName("a syntactically valid fixture with an invalid API envelope is corrupt")
    void invalidFixtureEnvelopeIsCorrupt() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        when(mapper.readTree(any(InputStream.class)))
                .thenReturn(
                        new ObjectMapper()
                                .readTree(
                                        "{\"header\":{\"resultCode\":\"99\",\"resultMsg\":\"bad fixture\"}}"));
        FixtureLoader loader = new FixtureLoader(mapper);

        assertThatThrownBy(() -> loader.load("pharmacy.json"))
                .isInstanceOf(FixtureIntegrityException.class)
                .isNotInstanceOf(PublicApiException.class)
                .satisfies(
                        error ->
                                assertThat(((FixtureIntegrityException) error).reason())
                                        .isEqualTo(FixtureIntegrityException.Reason.CORRUPT));
    }

    @Test
    @DisplayName("a successful fixture envelope without a body is corrupt, not an empty result")
    void successfulFixtureWithoutBodyIsCorrupt() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        when(mapper.readTree(any(InputStream.class)))
                .thenReturn(
                        new ObjectMapper()
                                .readTree(
                                        "{\"header\":{\"resultCode\":\"00\",\"resultMsg\":\"NORMAL SERVICE\"}}"));
        FixtureLoader loader = new FixtureLoader(mapper);

        assertThatThrownBy(() -> loader.load("pharmacy.json"))
                .isInstanceOf(FixtureIntegrityException.class)
                .isNotInstanceOf(PublicApiException.class)
                .satisfies(
                        error ->
                                assertThat(((FixtureIntegrityException) error).reason())
                                        .isEqualTo(FixtureIntegrityException.Reason.CORRUPT));
    }

    @Test
    @DisplayName("a valid captured fixture remains available")
    void validFixtureStillLoads() {
        FixtureLoader loader = new FixtureLoader(new ObjectMapper());

        assertThat(PublicApiResponse.of(loader.load("pharmacy.json")).requireOk().isOk()).isTrue();
    }

    @Test
    @DisplayName("the drug permission adapter preserves a fixture integrity failure")
    void drugPermissionAdapterPreservesFixtureIntegrity() {
        FixtureLoader loader =
                new FixtureLoader(new ObjectMapper()) {
                    @Override
                    public com.fasterxml.jackson.databind.JsonNode load(String ignored) {
                        return super.load(MISSING_FIXTURE);
                    }
                };
        PublicApiProperties properties =
                new PublicApiProperties(
                        "",
                        "https://example.invalid",
                        "https://example.invalid",
                        "https://example.invalid",
                        "https://example.invalid",
                        "https://example.invalid",
                        "https://example.invalid",
                        "https://example.invalid");
        DrugPermissionApiClient client =
                new DrugPermissionApiClient(
                        null,
                        properties,
                        new DataModeProperties(DataModeProperties.DataMode.FIXTURE),
                        loader,
                        Clock.systemUTC());

        assertThatThrownBy(() -> client.findByIngredient("Acetaminophen"))
                .isInstanceOf(FixtureIntegrityException.class)
                .isNotInstanceOf(PublicApiException.class);
    }

    @Test
    @DisplayName("fixture integrity returns safe non-retryable 500 and value-free operator logging")
    void fixtureIntegrityUsesInternalErrorContract() throws Exception {
        MockMvc mvc = mvc();
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> logs = appender(logger);
        try {
            MvcResult result =
                    mvc.perform(get("/fixture-integrity-failure"))
                            .andExpect(status().isInternalServerError())
                            .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                            .andExpect(jsonPath("$.error.retryable").value(false))
                            .andExpect(
                                    jsonPath("$.error.message")
                                            .value("Something went wrong on our side."))
                            .andReturn();

            assertThat(result.getResponse().getContentAsString())
                    .doesNotContain(MISSING_FIXTURE, "SOURCE_UNAVAILABLE", "government");
            assertThat(logs.list)
                    .anySatisfy(
                            event -> {
                                assertThat(event.getFormattedMessage())
                                        .isEqualTo("fixture_integrity_failure reason=MISSING");
                                assertThat(event.getThrowableProxy()).isNull();
                            });
            String renderedLogs =
                    logs.list.stream()
                            .map(ILoggingEvent::getFormattedMessage)
                            .collect(Collectors.joining("\n"));
            assertThat(renderedLogs).doesNotContain(MISSING_FIXTURE);
        } finally {
            detach(logger, logs);
        }
    }

    @Test
    @DisplayName("real public API failures keep the retryable SOURCE_UNAVAILABLE contract")
    void publicApiFailureContractIsUnchanged() throws Exception {
        mvc().perform(get("/public-api-failure"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SOURCE_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.retryable").value(true))
                .andExpect(
                        jsonPath("$.error.message")
                                .value(
                                        "A government data service is not responding. Please try again shortly."));
    }

    private static MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new FailureController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
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

    @RestController
    private static final class FailureController {

        private final FixtureLoader fixtures = new FixtureLoader(new ObjectMapper());

        @GetMapping("/fixture-integrity-failure")
        void fixtureIntegrityFailure() {
            fixtures.load(MISSING_FIXTURE);
        }

        @GetMapping("/public-api-failure")
        void publicApiFailure() {
            throw new PublicApiException("upstream failed");
        }
    }

    @RestController
    private static final class CorruptFixtureController {

        private final FixtureLoader fixtures;

        private CorruptFixtureController(FixtureLoader fixtures) {
            this.fixtures = fixtures;
        }

        @GetMapping("/corrupt-fixture")
        void corruptFixture() {
            fixtures.load("pharmacy.json");
        }
    }
}
