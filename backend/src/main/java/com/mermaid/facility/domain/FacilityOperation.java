package com.mermaid.facility.domain;

import com.fasterxml.jackson.annotation.JsonValue;
import java.time.Instant;

/**
 * Whether a facility is open, and how much we trust that answer (spec §2-13).
 *
 * <p>The v1 model made {@code openNow} a primitive {@code boolean}, which quietly told users
 * that a pharmacy with no published schedule was <b>CLOSED</b>. It was not closed; we simply
 * did not know. At 2 a.m. that difference decides whether someone walks to a pharmacy.
 *
 * <p>So {@code isOpenNow} is nullable, {@code status} names the three real outcomes, and
 * {@code statusConfidence} says where the answer came from. We never claim "실시간 운영 보장" —
 * we compute from a published timetable, which is {@link StatusConfidence#OFFICIAL_SCHEDULE}.
 *
 * @param isOpenNow {@code null} means "could not determine"
 * @param verifiedAt when we fetched the underlying schedule
 * @param notice user-facing caveat, e.g. "Call before visiting"
 */
public record FacilityOperation(
        Boolean isOpenNow,
        OperationStatus status,
        StatusConfidence statusConfidence,
        Instant verifiedAt,
        String notice) {

    private static final String CALL_AHEAD =
            "Opening hours come from public data and may be out of date. Call before visiting.";

    public static FacilityOperation open(Instant verifiedAt) {
        return new FacilityOperation(
                true, OperationStatus.OPEN, StatusConfidence.OFFICIAL_SCHEDULE, verifiedAt, CALL_AHEAD);
    }

    public static FacilityOperation closed(Instant verifiedAt) {
        return new FacilityOperation(
                false, OperationStatus.CLOSED, StatusConfidence.OFFICIAL_SCHEDULE, verifiedAt, CALL_AHEAD);
    }

    /** No usable timetable. Not the same as closed. */
    public static FacilityOperation unknown(Instant verifiedAt) {
        return new FacilityOperation(
                null,
                OperationStatus.UNKNOWN,
                StatusConfidence.UNKNOWN,
                verifiedAt,
                "Opening hours are not published for this place. Call to check.");
    }

    public enum OperationStatus {
        OPEN("open"),
        CLOSED("closed"),
        UNKNOWN("unknown");

        private final String wire;

        OperationStatus(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    /** Where the answer came from. Nothing we can compute is {@code OFFICIAL_REALTIME}. */
    public enum StatusConfidence {
        OFFICIAL_REALTIME("official_realtime"),
        OFFICIAL_SCHEDULE("official_schedule"),
        INFERRED("inferred"),
        UNKNOWN("unknown");

        private final String wire;

        StatusConfidence(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }
}
