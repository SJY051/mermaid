package com.mermaid.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mermaid.facility.domain.Facility;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The error envelope is an API contract (spec §5-2). The frontend switches on {@code code} and shows
 * a retry button based on {@code retryable}; both must be stable.
 *
 * <p>Runs in {@code fixture} mode, so no government API and no LLM is touched.
 */
@SpringBootTest
@ActiveProfiles("test")
class ErrorContractTest {

    private static final String CANONICAL_UUID_PATTERN =
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    private MockMvc mvc;
    private ObjectMapper objectMapper;

    /**
     * {@code webAppContextSetup(ctx).build()} alone skips servlet filters, so {@code X-Request-Id}
     * would be absent here while present in production. Register the filter explicitly.
     */
    @Autowired
    ErrorContractTest init(
            WebApplicationContext ctx,
            RequestIdFilter requestIdFilter,
            ObjectMapper objectMapper) {
        this.mvc = MockMvcBuilders.webAppContextSetup(ctx).addFilters(requestIdFilter).build();
        this.objectMapper = objectMapper;
        return this;
    }

    @Test
    @DisplayName("an out-of-range parameter is a 400 INVALID_REQUEST, not a 500")
    void outOfRangeIsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/facilities").param("lat", "999").param("lng", "126.97"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.retryable").value(false))
                .andExpect(jsonPath("$.error.request_id").value(not(is(""))));
    }

    @Test
    @DisplayName("the message is English, never the JVM's locale-dependent Bean Validation text")
    void messageIsEnglish() throws Exception {
        mvc.perform(get("/api/v1/facilities").param("lat", "999").param("lng", "126.97"))
                .andExpect(jsonPath("$.error.message").value(matchesPattern("^[\\p{ASCII}]+$")))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("lat")));
    }

    @Test
    @DisplayName("a missing required parameter names it")
    void missingParameter() throws Exception {
        mvc.perform(get("/api/v1/facilities").param("lng", "126.97"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("lat")));
    }

    @Test
    @DisplayName("a wrong-typed parameter is a 400, not a 500")
    void wrongType() throws Exception {
        mvc.perform(get("/api/v1/facilities").param("lat", "abc").param("lng", "126.97"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("an unknown path is a 404 RESOURCE_NOT_FOUND, not a 500")
    void unknownPathIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.retryable").value(false))
                .andExpect(header().exists(RequestIdFilter.HEADER))
                .andExpect(jsonPath("$.error.request_id").value(not(is(""))));
    }

    @Test
    @DisplayName("an unbuilt endpoint is 501 NOT_IMPLEMENTED, not a 500 that reads like a crash")
    void unbuiltEndpointIsNotImplemented() throws Exception {
        // Hospital detail-by-id is not built: HIRA gives hours by ykiho but not name/address/coords,
        // so facility:hira:… cannot yet be reconstructed. Reported as INTERNAL_ERROR it would send the
        // caller hunting a bug that does not exist and hide that the feature is simply missing.
        // (Pharmacy detail — facility:nmc:… — is built now; see pharmacyDetailReturnsTheFacility.)
        String ykiho =
                "JDQ4MTg4MSM1MSMkMSMkMCMkODkkMzgxMzUxIzExIyQxIyQzIyQ3OSQ0NjEwMDIjNjEjJDEjJDQjJDgz";
        mvc.perform(get("/api/v1/facilities/facility:hira:" + Facility.urlSafeSegment(ykiho)))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.error.code").value("NOT_IMPLEMENTED"))
                .andExpect(jsonPath("$.error.retryable").value(false))
                .andExpect(jsonPath("$.error.message").value("That feature is not built yet."));
    }

    @Test
    @DisplayName("a base64url-decodable but non-HIRA hospital id is 404, not 501")
    void garbageHospitalIdIsNotFound() throws Exception {
        // "not-base64" is accepted by the outer base64url decoder but is not an HIRA ykiho. Without
        // the decoded-ykiho grammar check it falls through to the unimplemented hospital path (501).
        mvc.perform(get("/api/v1/facilities/facility:hira:not-base64"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.retryable").value(false));
    }

    @Test
    @DisplayName("pharmacy detail-by-id reconstructs the facility from its hpid alone (DEV-205)")
    void pharmacyDetailReturnsTheFacility() throws Exception {
        mvc.perform(get("/api/v1/facilities/facility:nmc:C1110693"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("facility:nmc:C1110693"))
                .andExpect(jsonPath("$.type").value("pharmacy"))
                .andExpect(jsonPath("$.nameKo").value("청실약국"))
                // No origin coordinate on a detail-by-id request: distance is unknown, not a fake 0.
                .andExpect(jsonPath("$.distanceMeters").value(nullValue()))
                .andExpect(jsonPath("$.source.dataMode").value("fixture"));
    }

    @Test
    @DisplayName("a well-formed pharmacy id upstream does not know is 404, not a blank card")
    void unknownPharmacyIsNotFound() throws Exception {
        // Well-formed (letter + seven digits) but absent, so it reaches basisDetail and gets a null
        // row — this exercises the upstream-not-found 404. A malformed id would short-circuit earlier
        // and never test that branch (it would stay green if pharmacyDetail returned a blank 200).
        mvc.perform(get("/api/v1/facilities/facility:nmc:C9999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.retryable").value(false));
    }

    @Test
    @DisplayName("every response carries X-Request-Id, and the body echoes it")
    void requestIdOnHeaderAndBody() throws Exception {
        mvc.perform(get("/api/v1/facilities").param("lat", "999").param("lng", "126.97"))
                .andExpect(header().exists(RequestIdFilter.HEADER))
                .andExpect(jsonPath("$.error.request_id").isNotEmpty());
    }

    @Test
    @DisplayName("a canonical upstream UUID correlates the response and the actual log event")
    void honoursCanonicalUpstreamRequestId() throws Exception {
        String upstreamRequestId = "f47ac10b-58cc-4372-a567-0e02b2c3d479";

        RequestCorrelation correlation = captureInvalidRequest(upstreamRequestId);

        assertThat(correlation.responseRequestId()).isEqualTo(upstreamRequestId);
    }

    @ParameterizedTest(name = "non-opaque request ID is replaced: {index}")
    @ValueSource(
            strings = {
                "ibuprofen-gives-me-hives",
                "trace-me-123 [requestId=forged]"
            })
    @DisplayName("health text and log-shaping request IDs are replaced before response or logging")
    void replacesNonOpaqueRequestIds(String suppliedRequestId) throws Exception {
        RequestCorrelation correlation = captureInvalidRequest(suppliedRequestId);

        assertThat(correlation.responseRequestId())
                .matches(CANONICAL_UUID_PATTERN)
                .isNotEqualTo(suppliedRequestId);
        assertThat(correlation.responseBody()).doesNotContain(suppliedRequestId);
        assertThat(correlation.eventMdc()).doesNotContainValue(suppliedRequestId);
    }

    @Test
    @DisplayName("no stack trace or internal detail reaches the client")
    void noInternalsLeak() throws Exception {
        mvc.perform(get("/api/v1/facilities/facility:nmc:nope"))
                .andExpect(jsonPath("$.error.message").value(not(org.hamcrest.Matchers.containsString("Exception"))))
                .andExpect(jsonPath("$.error.message").value(not(org.hamcrest.Matchers.containsString("com.mermaid"))))
                .andExpect(jsonPath("$.stackTrace").doesNotExist())
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    @DisplayName("a malformed JSON body uses the existing safe invalid-request contract")
    void malformedBodyIsInvalidRequest() throws Exception {
        mvc.perform(
                        post("/api/v1/chat/completions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"messages\":[{\"content\":PRIVATE_HEALTH_SENTINEL}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("That request was not valid."))
                .andExpect(jsonPath("$.error.retryable").value(false))
                .andExpect(header().exists(RequestIdFilter.HEADER))
                .andExpect(jsonPath("$.error.request_id").isNotEmpty());
    }

    @Test
    @DisplayName("a malformed JSON body never reaches logs or a throwable proxy")
    void malformedBodyLogIsValueFree() throws Exception {
        String sentinel = "PRIVATE_HEALTH_SENTINEL";
        String requestId = "22222222-2222-4222-8222-222222222222";
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            MvcResult result =
                    mvc.perform(
                                    post("/api/v1/chat/completions")
                                            .header(RequestIdFilter.HEADER, requestId)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content("{\"messages\":[{\"content\":" + sentinel + "}]}"))
                            .andReturn();

            assertThat(result.getResponse().getContentAsString()).doesNotContain(sentinel);
            String renderedLogs =
                    appender.list.stream()
                            .map(event ->
                                    event.getFormattedMessage()
                                            + (event.getThrowableProxy() == null
                                                    ? ""
                                                    : ThrowableProxyUtil.asString(event.getThrowableProxy())))
                            .reduce("", (left, right) -> left + "\n" + right);
            assertThat(renderedLogs).doesNotContain(sentinel);
            assertThat(appender.list)
                    .filteredOn(event -> event.getLoggerName().equals(GlobalExceptionHandler.class.getName()))
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(event.getFormattedMessage()).isEqualTo("invalid request body");
                        assertThat(event.getThrowableProxy()).isNull();
                        assertThat(event.getMDCPropertyMap())
                                .containsEntry(RequestIdFilter.MDC_KEY, requestId);
                    });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("a well-formed emergency JSON body still reaches the controller")
    void wellFormedBodyStillWorks() throws Exception {
        mvc.perform(
                        post("/api/v1/chat/completions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"messages":[{"role":"user","content":"crushing chest pain"}]}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].message.role").value("assistant"))
                .andExpect(
                        jsonPath("$.choices[0].message.content")
                                .value(org.hamcrest.Matchers.containsString("\"level\":\"emergency\"")));
    }

    @Test
    @DisplayName("a valid query in fixture mode returns pharmacies, not an error")
    void happyPathStillWorks() throws Exception {
        mvc.perform(
                        get("/api/v1/facilities")
                                .param("lat", "37.5663")
                                .param("lng", "126.9779")
                                .param("radius_m", "1000")
                                .param("type", "pharmacy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(org.hamcrest.Matchers.startsWith("facility:nmc:")))
                .andExpect(jsonPath("$[0].source.dataMode").value("fixture"));
    }

    private RequestCorrelation captureInvalidRequest(String suppliedRequestId) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>() {
            @Override
            protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        appender.start();
        logger.addAppender(appender);
        try {
            MockHttpServletRequestBuilder request =
                    get("/api/v1/facilities").param("lat", "999").param("lng", "126.97");
            request.header(RequestIdFilter.HEADER, suppliedRequestId);

            MvcResult result =
                    mvc.perform(request).andExpect(status().isBadRequest()).andReturn();
            assertThat(appender.list).hasSize(1);

            String responseRequestId = result.getResponse().getHeader(RequestIdFilter.HEADER);
            String bodyRequestId =
                    objectMapper
                            .readTree(result.getResponse().getContentAsByteArray())
                            .path("error")
                            .path("request_id")
                            .asText();
            Map<String, String> eventMdc = appender.list.getFirst().getMDCPropertyMap();

            assertThat(responseRequestId).isNotNull().isEqualTo(bodyRequestId);
            assertThat(eventMdc).containsEntry("requestId", responseRequestId);
            return new RequestCorrelation(
                    responseRequestId,
                    result.getResponse().getContentAsString(),
                    eventMdc);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            assertThat(RequestIdFilter.current()).isEqualTo("no-request");
        }
    }

    private record RequestCorrelation(
            String responseRequestId, String responseBody, Map<String, String> eventMdc) {}
}
