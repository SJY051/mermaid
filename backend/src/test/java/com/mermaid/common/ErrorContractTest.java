package com.mermaid.common;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
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

    private MockMvc mvc;

    /**
     * {@code webAppContextSetup(ctx).build()} alone skips servlet filters, so {@code X-Request-Id}
     * would be absent here while present in production. Register the filter explicitly.
     */
    @Autowired
    ErrorContractTest init(WebApplicationContext ctx, RequestIdFilter requestIdFilter) {
        this.mvc = MockMvcBuilders.webAppContextSetup(ctx).addFilters(requestIdFilter).build();
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
    @DisplayName("every response carries X-Request-Id, and the body echoes it")
    void requestIdOnHeaderAndBody() throws Exception {
        mvc.perform(get("/api/v1/facilities").param("lat", "999").param("lng", "126.97"))
                .andExpect(header().exists(RequestIdFilter.HEADER))
                .andExpect(jsonPath("$.error.request_id").isNotEmpty());
    }

    @Test
    @DisplayName("a supplied X-Request-Id is honoured so a trace survives the hop")
    void honoursSuppliedRequestId() throws Exception {
        mvc.perform(
                        get("/api/v1/facilities")
                                .param("lat", "999")
                                .param("lng", "126.97")
                                .header(RequestIdFilter.HEADER, "trace-me-123"))
                .andExpect(header().string(RequestIdFilter.HEADER, "trace-me-123"))
                .andExpect(jsonPath("$.error.request_id").value("trace-me-123"));
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
}
