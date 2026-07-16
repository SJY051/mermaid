package com.mermaid.chat;

import com.mermaid.facility.domain.FacilityType;
import java.util.Objects;
import java.util.Set;

/** Server-authoritative plan for one chat turn. Model advice is adjudicated before this type exists. */
record ResponsePlan(
        ResponseMode mode,
        Set<Capability> capabilities,
        ConfidenceBucket confidence,
        Set<ReasonCode> reasonCodes,
        FacilityIntent facilityIntent) {

    ResponsePlan {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(reasonCodes, "reasonCodes");
        capabilities = Set.copyOf(capabilities);
        reasonCodes = Set.copyOf(reasonCodes);
        boolean hasFacilityCapability = capabilities.contains(Capability.FACILITY_LOOKUP);
        if (hasFacilityCapability != (facilityIntent != null)) {
            throw new IllegalArgumentException(
                    "FACILITY_LOOKUP and facilityIntent must be present together");
        }
        validateProtectedCapabilities(mode, capabilities);
    }

    enum ResponseMode {
        T1_ANSWER_GENERAL_OR_LOCATE_CARE,
        T2_ANSWER_WITH_CONSULTATION,
        T3_REFUSE_CLINICAL_AUTHORITY,
        T4_EMERGENCY,
        T5_REFUSE_ILLEGAL_ASSISTANCE
    }

    enum Capability {
        GENERAL_EXPLANATION,
        FACILITY_LOOKUP,
        OFFICIAL_MEDICINE_LOOKUP,
        OFFICIAL_SOURCE_NAVIGATION,
        PROFESSIONAL_CONSULTATION,
        CLINICAL_REFUSAL,
        EMERGENCY_RESPONSE,
        ILLEGAL_ASSISTANCE_REFUSAL
    }

    enum ConfidenceBucket {
        HIGH,
        MEDIUM,
        LOW
    }

    enum ReasonCode {
        FACILITY_REQUEST,
        OPEN_NOW_REQUEST,
        LOCATION_UNAVAILABLE,
        FACILITY_ADAPTER_UNAVAILABLE,
        GENERAL_INFORMATION,
        PROFESSIONAL_CONTEXT,
        CLINICAL_AUTHORITY_REQUEST,
        EMERGENCY_RED_FLAG,
        EXPLICIT_CONTROL_EVASION,
        NEUTRAL_LEGAL_INFORMATION,
        LOW_CONFIDENCE
    }

    private static void validateProtectedCapabilities(
            ResponseMode mode, Set<Capability> capabilities) {
        switch (mode) {
            case T1_ANSWER_GENERAL_OR_LOCATE_CARE, T2_ANSWER_WITH_CONSULTATION -> {
                if (capabilities.contains(Capability.CLINICAL_REFUSAL)
                        || capabilities.contains(Capability.EMERGENCY_RESPONSE)
                        || capabilities.contains(Capability.ILLEGAL_ASSISTANCE_REFUSAL)) {
                    throw new IllegalArgumentException(
                            "T1/T2 cannot carry a protected response capability");
                }
            }
            case T3_REFUSE_CLINICAL_AUTHORITY -> {
                if (!capabilities.contains(Capability.CLINICAL_REFUSAL)
                        || capabilities.contains(Capability.EMERGENCY_RESPONSE)
                        || capabilities.contains(Capability.ILLEGAL_ASSISTANCE_REFUSAL)) {
                    throw new IllegalArgumentException(
                            "T3 requires CLINICAL_REFUSAL and no T4/T5 capability");
                }
            }
            case T4_EMERGENCY -> requireExactCapability(
                    capabilities, Capability.EMERGENCY_RESPONSE, "T4");
            case T5_REFUSE_ILLEGAL_ASSISTANCE -> requireExactCapability(
                    capabilities, Capability.ILLEGAL_ASSISTANCE_REFUSAL, "T5");
        }
    }

    private static void requireExactCapability(
            Set<Capability> capabilities, Capability required, String mode) {
        if (!capabilities.equals(Set.of(required))) {
            throw new IllegalArgumentException(mode + " requires exactly " + required);
        }
    }

    /**
     * A navigation preference, not a frontend filter payload.
     *
     * <p>{@link OperationPreference#OPEN_OR_UNKNOWN} must retain facilities whose current hours are
     * unknown while excluding confirmed-closed rows. The existing boolean UI flag cannot express
     * that: {@code true} drops unknown rows and {@code false} includes confirmed-closed rows.
     */
    record FacilityIntent(
            Set<FacilityType> types,
            OperationPreference operationPreference,
            FacilityAvailability availability) {

        FacilityIntent {
            Objects.requireNonNull(types, "types");
            Objects.requireNonNull(operationPreference, "operationPreference");
            Objects.requireNonNull(availability, "availability");
            if (types.isEmpty()) {
                throw new IllegalArgumentException("facility types must not be empty");
            }
            types = Set.copyOf(types);
        }
    }

    enum OperationPreference {
        ANY,
        OPEN_OR_UNKNOWN
    }

    enum FacilityAvailability {
        READY,
        LOCATION_REQUIRED,
        ADAPTER_UNAVAILABLE
    }
}
