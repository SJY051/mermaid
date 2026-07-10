package com.mermaid.facility.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The two kinds of place we put on the map.
 *
 * <p>Their identifiers come from different agencies and never collide: a pharmacy is keyed by {@code
 * hpid} (국립중앙의료원), a hospital by {@code ykiho} (건강보험심사평가원).
 */
public enum FacilityType {
    PHARMACY("pharmacy"),
    HOSPITAL("hospital");

    private final String wire;

    FacilityType(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static FacilityType from(String value) {
        for (FacilityType t : values()) {
            if (t.wire.equalsIgnoreCase(value) || t.name().equalsIgnoreCase(value)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown facility type: " + value);
    }
}
