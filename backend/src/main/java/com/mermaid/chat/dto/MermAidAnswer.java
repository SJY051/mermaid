package com.mermaid.chat.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonValue;
import com.mermaid.common.SourceRef;
import java.util.List;

/**
 * The shape every assistant turn must take — {@code MermAidAnswerV1} (spec §5-4).
 *
 * <p>Passing this record's structure is necessary but <b>not sufficient</b>. A model can produce
 * perfectly-shaped JSON naming a medicine that does not exist. {@link com.mermaid.chat.AnswerValidator}
 * enforces the cross-field invariants that a schema cannot express (spec §2-15).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MermAidAnswer(
        String schemaVersion,
        String answerId,
        String language,
        DataStatus dataStatus,
        Urgency urgency,
        String summary,
        List<String> clarifyingQuestions,
        List<Guidance> guidance,
        List<DrugCard> drugs,
        List<UiAction> uiActions,
        List<SourceRef> sourceRefs,
        List<String> warnings,
        /** SA-02. Always present. The UI renders it. */
        String disclaimer) {

    public static final String SCHEMA_VERSION = "1.0";

    /** Whether the facts in this answer came from live government data or from fixtures. */
    public enum DataStatus {
        LIVE("live"),
        FIXTURE("fixture"),
        MIXED("mixed"),
        UNAVAILABLE("unavailable");

        private final String wire;

        DataStatus(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }

        @JsonCreator
        public static DataStatus from(String v) {
            for (DataStatus d : values()) {
                if (d.wire.equalsIgnoreCase(v)) {
                    return d;
                }
            }
            return UNAVAILABLE;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Urgency(
            Level level, String title, String message, List<String> reasonCodes, List<UiAction> actions) {

        public enum Level {
            EMERGENCY("emergency"),
            URGENT("urgent"),
            ROUTINE("routine"),
            UNKNOWN("unknown");

            private final String wire;

            Level(String wire) {
                this.wire = wire;
            }

            @JsonValue
            public String wire() {
                return wire;
            }

            @JsonCreator
            public static Level from(String v) {
                for (Level l : values()) {
                    if (l.wire.equalsIgnoreCase(v)) {
                        return l;
                    }
                }
                // Unrecognised urgency degrades UPWARD. Never quietly downgrade a symptom.
                return URGENT;
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Guidance(
            String id, String title, String body, Evidence evidence, List<String> sourceRefIds) {

        public enum Evidence {
            /** Backed by a government record. Must cite at least one sourceRefId (invariant #5). */
            OFFICIAL_DATA("official_data"),
            /** Generic first-aid advice that needs no citation. */
            GENERAL_SAFETY("general_safety"),
            /** The model's own summary of cited data. */
            MODEL_SUMMARY("model_summary");

            private final String wire;

            Evidence(String wire) {
                this.wire = wire;
            }

            @JsonValue
            public String wire() {
                return wire;
            }

            @JsonCreator
            public static Evidence from(String v) {
                for (Evidence e : values()) {
                    if (e.wire.equalsIgnoreCase(v)) {
                        return e;
                    }
                }
                return MODEL_SUMMARY;
            }
        }
    }

    /**
     * One medicine.
     *
     * @param productNameKo Korean script, so the user can show it at a pharmacy counter. Must match
     *     a record we actually fetched — invariant #6.
     * @param warnings includes DUR contraindications (spec §2-10), cited via {@code sourceRefId}
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DrugCard(
            String id,
            String productNameKo,
            String productNameEn,
            List<Ingredient> ingredients,
            String indicationSummary,
            String directionsSummary,
            List<String> warnings,
            PrescriptionStatus prescriptionStatus,
            AllergyCheck allergyCheck,
            String sourceRefId) {

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

            @JsonCreator
            public static PrescriptionStatus from(String v) {
                for (PrescriptionStatus p : values()) {
                    if (p.wire.equalsIgnoreCase(v)) {
                        return p;
                    }
                }
                return UNKNOWN;
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ingredient(
            String nameKo, String nameEn, String normalizedKey, String amount, String unit) {}
}
