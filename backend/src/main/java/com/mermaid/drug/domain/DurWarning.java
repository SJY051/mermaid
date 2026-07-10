package com.mermaid.drug.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * One contraindication or caution the ministry has published for a product (FR-07).
 *
 * <p>Reporting this is information, not medical advice: "the government lists a contraindication for
 * children under 3" is a fact with a date and a source. "Do not give this to your child" is a
 * clinical judgement we are not qualified to make. Keep the wording on the former side.
 *
 * @param prohibitContent {@code PROHBT_CONTENT} — Korean free text, and <b>often null</b>. The
 *     노인주의 rows in particular carry no explanation; the type alone is the warning.
 * @param notificationDate {@code NOTIFICATION_DATE}, {@code yyyyMMdd}
 * @param ingredientEn the ingredient the warning attaches to, e.g. {@code "Chlorpheniramine"}
 * @param pairedItemName for {@link Kind#COMBINATION}, the drug that must not be co-administered
 */
public record DurWarning(
        Kind kind,
        String itemSeq,
        String itemName,
        String ingredientEn,
        String prohibitContent,
        String notificationDate,
        String pairedItemSeq,
        String pairedItemName) {

    public enum Kind {
        /** 병용금기 — must not be taken together with {@code pairedItemName}. */
        COMBINATION("combination", "병용금기"),
        /** 특정연령대금기 — the age threshold is inside {@code prohibitContent}, in Korean. */
        AGE("age", "특정연령대금기"),
        /** 임부금기 */
        PREGNANCY("pregnancy", "임부금기"),
        /** 노인주의 */
        ELDERLY("elderly", "노인주의");

        private final String wire;
        private final String korean;

        Kind(String wire, String korean) {
            this.wire = wire;
            this.korean = korean;
        }

        @JsonValue
        public String wire() {
            return wire;
        }

        public String korean() {
            return korean;
        }
    }

    /**
     * An English sentence a traveller can read, stating the fact without prescribing an action.
     *
     * <p>The Korean {@code prohibitContent} is included verbatim when present. We do not translate
     * it: a paraphrase of a government contraindication is a new medical claim, and ours to answer
     * for. Showing it lets a pharmacist read it.
     */
    public String describe() {
        String base =
                switch (kind) {
                    case COMBINATION ->
                            "Contraindicated with " + (pairedItemName == null ? "another medicine" : pairedItemName);
                    case AGE -> "Age restriction published";
                    case PREGNANCY -> "Contraindicated during pregnancy";
                    case ELDERLY -> "Caution advised for older adults";
                };
        String ingredient = ingredientEn == null ? "" : " (" + ingredientEn + ")";
        String detail = (prohibitContent == null || prohibitContent.isBlank()) ? "" : " — " + prohibitContent;
        return base + ingredient + detail + ". Source: MFDS DUR, notified " + formattedDate() + ".";
    }

    private String formattedDate() {
        if (notificationDate == null || notificationDate.length() != 8) {
            return "date unknown";
        }
        return notificationDate.substring(0, 4)
                + "-"
                + notificationDate.substring(4, 6)
                + "-"
                + notificationDate.substring(6, 8);
    }
}
