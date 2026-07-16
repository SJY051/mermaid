package com.mermaid.facility.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Server-owned visibility contract for facility-hour filtering. */
public enum FacilityOperationPreference {
    ANY("any"),
    CONFIRMED_OPEN_ONLY("confirmed_open_only"),
    OPEN_OR_UNKNOWN("open_or_unknown");

    private final String wire;

    FacilityOperationPreference(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static FacilityOperationPreference from(String value) {
        for (FacilityOperationPreference preference : values()) {
            if (preference.wire.equalsIgnoreCase(value)
                    || preference.name().equalsIgnoreCase(value)) {
                return preference;
            }
        }
        throw new IllegalArgumentException("Unknown facility operation preference: " + value);
    }

    public static FacilityOperationPreference fromLegacyOpenNow(boolean openNow) {
        return openNow ? CONFIRMED_OPEN_ONLY : ANY;
    }

    /** Whether a resolved tri-state operation value belongs in this result. */
    public boolean includes(Boolean isOpenNow) {
        return switch (this) {
            case ANY -> true;
            case CONFIRMED_OPEN_ONLY -> Boolean.TRUE.equals(isOpenNow);
            case OPEN_OR_UNKNOWN -> !Boolean.FALSE.equals(isOpenNow);
        };
    }

    /** Non-ANY searches inspect a wider bounded candidate set before applying the public limit. */
    public boolean usesExpandedCandidateSet() {
        return this != ANY;
    }

    /** OPEN_OR_UNKNOWN presents confirmed-open rows before rows whose hours are unknown. */
    public int operationRank(Boolean isOpenNow) {
        if (Boolean.TRUE.equals(isOpenNow)) {
            return 0;
        }
        if (isOpenNow == null) {
            return 1;
        }
        return 2;
    }
}
