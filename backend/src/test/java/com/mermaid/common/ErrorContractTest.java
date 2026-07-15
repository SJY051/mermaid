package com.mermaid.common;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mermaid.facility.domain.Facility;
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
