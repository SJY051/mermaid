package com.mermaid.drug.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Whether a prescription is needed.
 *
 * <p>The MFDS APIs answer in Korean, and in two different fields: {@code SPCLTY_PBLC} on the list
 * operation, {@code ETC_OTC_CODE} on the detail one. Both carry the same two words.
 */
public enum PrescriptionStatus {
    PRESCRIPTION("prescription"),
    OTC("otc"),
    UNKNOWN("unknown");

    private final String wire;

    PrescriptionStatus(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    /** @param korean 전문의약품 / 일반의약품, or anything else */
    public static PrescriptionStatus fromKorean(String korean) {
        if (korean == null) {
            return UNKNOWN;
        }
        return switch (korean.trim()) {
            case "전문의약품" -> PRESCRIPTION;
            case "일반의약품" -> OTC;
            // Never guess. A traveller told "no prescription needed" who then cannot buy it has
            // wasted a trip; the opposite has them skip a doctor they needed.
            default -> UNKNOWN;
        };
    }
}
