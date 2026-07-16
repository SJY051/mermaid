package com.mermaid.chat.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.mermaid.facility.domain.FacilityOperationPreference;
import java.util.List;
import java.util.Objects;

/**
 * What the assistant asks the UI to do (spec §2-11).
 *
 * <p>These are <i>fields inside the response</i>, not provider tool calls. A tool-call message
 * carries {@code tool_calls} and leaves {@code content} empty, so it can never also carry the
 * schema-constrained JSON we need. Modelling the intent as data sidesteps that entirely, and it
 * keeps the browser from ever seeing a provider-specific tool format.
 *
 * <p>A closed allowlist on purpose. The model cannot ask the UI to open an arbitrary URL, run
 * code, or navigate anywhere we did not name here.
 */
// `As.PROPERTY`, not `EXISTING_PROPERTY`. The latter assumes the object already carries the
// discriminator and therefore writes nothing on serialisation — and a record's extra `type()`
// method is not a record component, so Jackson never emits it either. The result parsed fine and
// serialised without `type`, which meant the frontend's `action.type === 'OPEN_FACILITY_MAP'`
// never matched and the map never opened. Caught by calling the live endpoint, not by a unit test.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = UiAction.OpenFacilityMap.class, name = "OPEN_FACILITY_MAP"),
    @JsonSubTypes.Type(value = UiAction.ApplyFacilityFilters.class, name = "APPLY_FACILITY_FILTERS"),
    @JsonSubTypes.Type(value = UiAction.OpenDrugDetail.class, name = "OPEN_DRUG_DETAIL"),
    @JsonSubTypes.Type(value = UiAction.ShowEmergencyCall.class, name = "SHOW_EMERGENCY_CALL"),
    @JsonSubTypes.Type(value = UiAction.AskClarifyingQuestion.class, name = "ASK_CLARIFYING_QUESTION"),
    @JsonSubTypes.Type(value = UiAction.OpenOfficialSource.class, name = "OPEN_OFFICIAL_SOURCE"),
})
public sealed interface UiAction {

    /** For Java call sites. Jackson writes the discriminator itself, so keep it out of the JSON. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    String type();

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OpenFacilityMap(MapPayload payload) implements UiAction {
        public OpenFacilityMap {
            Objects.requireNonNull(payload, "payload");
        }

        @Override
        public String type() {
            return "OPEN_FACILITY_MAP";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ApplyFacilityFilters(MapPayload payload) implements UiAction {
        public ApplyFacilityFilters {
            Objects.requireNonNull(payload, "payload");
        }

        @Override
        public String type() {
            return "APPLY_FACILITY_FILTERS";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OpenDrugDetail(DrugPayload payload) implements UiAction {
        @Override
        public String type() {
            return "OPEN_DRUG_DETAIL";
        }
    }

    /** Required whenever {@code urgency.level == EMERGENCY} — post-processing invariant #4. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ShowEmergencyCall(EmergencyPayload payload) implements UiAction {
        @Override
        public String type() {
            return "SHOW_EMERGENCY_CALL";
        }

        public static ShowEmergencyCall korea119() {
            return new ShowEmergencyCall(new EmergencyPayload("119", "Call 119 (emergency services)"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AskClarifyingQuestion(QuestionPayload payload) implements UiAction {
        @Override
        public String type() {
            return "ASK_CLARIFYING_QUESTION";
        }
    }

    /**
     * A server-owned link selected from the launch legal-policy allowlist.
     *
     * <p>This action is intentionally absent from the provider schema and prompt. Even if a
     * non-strict provider emits the type, {@link OfficialSourcePayload}'s constructor accepts only
     * the exact source tuples approved on 2026-07-16.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record OpenOfficialSource(OfficialSourcePayload payload) implements UiAction {
        public OpenOfficialSource {
            Objects.requireNonNull(payload, "payload");
        }

        @Override
        public String type() {
            return "OPEN_OFFICIAL_SOURCE";
        }

        public static OpenOfficialSource koreanNarcoticsControlAct() {
            return new OpenOfficialSource(OfficialSourcePayload.koreanNarcoticsControlAct());
        }

        public static OpenOfficialSource mfdsMedicalNarcoticAnalgesicStandards() {
            return new OpenOfficialSource(
                    OfficialSourcePayload.mfdsMedicalNarcoticAnalgesicStandards());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record MapPayload(
            List<String> types,
            FacilityOperationPreference operationPreference,
            Boolean openNow,
            int radiusM) {
        public MapPayload {
            Objects.requireNonNull(types, "types");
            if ((operationPreference == null) == (openNow == null)) {
                throw new IllegalArgumentException(
                        "exactly one of operationPreference or legacy openNow is required");
            }
        }

        /** New server-owned action contract. */
        public MapPayload(
                List<String> types,
                FacilityOperationPreference operationPreference,
                int radiusM) {
            this(types, Objects.requireNonNull(operationPreference), null, radiusM);
        }

        /** Legacy whole-answer model contract retained only during planner migration. */
        public MapPayload(List<String> types, boolean openNow, int radiusM) {
            this(types, null, openNow, radiusM);
        }

        @com.fasterxml.jackson.annotation.JsonIgnore
        public FacilityOperationPreference resolvedOperationPreference() {
            return operationPreference != null
                    ? operationPreference
                    : FacilityOperationPreference.fromLegacyOpenNow(Boolean.TRUE.equals(openNow));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DrugPayload(String drugId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmergencyPayload(String phone, String label) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record QuestionPayload(String question) {}

    /** Exact source tuple; arbitrary URLs, labels, dates, and source identifiers fail closed. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record OfficialSourcePayload(
            String sourceId, String label, String url, String verifiedOn) {

        private static final String LAUNCH_VERIFIED_ON = "2026-07-16";
        private static final String LAW_SOURCE_ID = "korean-narcotics-control-act";
        private static final String LAW_LABEL =
                "National Law Information Center — Narcotics Control Act";
        private static final String LAW_URL =
                "https://www.law.go.kr/LSW/lsSc.do?eventGubun=060101&menuId=1&query=%EB%A7%88%EC%95%BD%EB%A5%98+%EA%B4%80%EB%A6%AC%EC%97%90+%EA%B4%80%ED%95%9C+%EB%B2%95%EB%A5%A0&section=&subMenuId=15&tabMenuId=81";
        private static final String MFDS_SOURCE_ID =
                "mfds-medical-narcotic-analgesic-standards";
        private static final String MFDS_LABEL =
                "MFDS — Medical narcotic analgesic prescribing standards";
        private static final String MFDS_URL =
                "https://www.mfds.go.kr/brd/m_218/view.do?Data_stts_gubun=C9999&company_cd=&company_nm=&itm_seq_1=0&itm_seq_2=0&multi_itm_seq=0&page=19&seq=33698&srchFr=&srchTo=&srchTp=0&srchWord=%EC%9D%98%EC%95%BD%ED%92%88";

        public OfficialSourcePayload {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(url, "url");
            Objects.requireNonNull(verifiedOn, "verifiedOn");

            String expectedLabel;
            String expectedUrl;
            if (LAW_SOURCE_ID.equals(sourceId)) {
                expectedLabel = LAW_LABEL;
                expectedUrl = LAW_URL;
            } else if (MFDS_SOURCE_ID.equals(sourceId)) {
                expectedLabel = MFDS_LABEL;
                expectedUrl = MFDS_URL;
            } else {
                throw new IllegalArgumentException("official source is not allowlisted");
            }
            if (!expectedLabel.equals(label)
                    || !expectedUrl.equals(url)
                    || !LAUNCH_VERIFIED_ON.equals(verifiedOn)) {
                throw new IllegalArgumentException("official source tuple does not match allowlist");
            }
        }

        static OfficialSourcePayload koreanNarcoticsControlAct() {
            return new OfficialSourcePayload(
                    LAW_SOURCE_ID, LAW_LABEL, LAW_URL, LAUNCH_VERIFIED_ON);
        }

        static OfficialSourcePayload mfdsMedicalNarcoticAnalgesicStandards() {
            return new OfficialSourcePayload(
                    MFDS_SOURCE_ID, MFDS_LABEL, MFDS_URL, LAUNCH_VERIFIED_ON);
        }
    }
}
