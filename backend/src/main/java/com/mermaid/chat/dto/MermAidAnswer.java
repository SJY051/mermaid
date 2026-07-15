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
     * <p>Three of these fields are the model's, and three are the server's, and the split is not
     * arbitrary: <b>the model translates, the server states facts.</b> {@code indicationSummary},
     * {@code directionsSummary} and {@code labelCautions} are English renderings of the ministry's
     * three narrative fields — that translation is the job we gave the model, and post-processing
     * invariants 7 and 8 bound it to the ministry's own numbers. {@code warnings}, {@code
     * prescriptionStatus} and {@code allergyCheck} are records the server holds, and it overwrites
     * whatever the model wrote in them.
     *
     * @param productNameKo Korean script, so the user can show it at a pharmacy counter. Must match
     *     a record we actually fetched — invariant #6.
     * @param labelCautions the model's English summary of 식약처's 주의사항·경고·상호작용·부작용. Until
     *     2026-07-14 this had no field of its own and the prompt asked for it inside {@code
     *     warnings}, where it sat indistinguishable from a government contraindication — and could
     *     displace one. It is a translation, like the two summaries above it, and lives with them.
     * @param warnings 식약처's DUR contraindications (spec §2-10), rendered by the server. The model
     *     is told to leave it empty; {@code ChatProxyController} fills it (invariant 8).
     * @param prescriptionStatus the server's own value from the MFDS licence record, overwritten in
     *     post-processing. A traveller told "no prescription needed" who then cannot buy it has
     *     wasted a trip; the opposite has them skip a doctor they needed.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DrugCard(
            String id,
            String productNameKo,
            String productNameEn,
            List<Ingredient> ingredients,
            String indicationSummary,
            String directionsSummary,
            String labelCautions,
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
