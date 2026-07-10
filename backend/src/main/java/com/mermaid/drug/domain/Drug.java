package com.mermaid.drug.domain;

import com.mermaid.chat.dto.AllergyCheck;
import com.mermaid.common.SourceRef;
import java.util.List;

/**
 * One medicine, assembled from the three MFDS services by {@code ITEM_SEQ} (spec §2-10).
 *
 * <pre>
 *   허가정보  → 성분 (영문), 전문/일반 구분
 *   e약은요   → 효능·복용법·주의사항 (한국어 안내문)
 *   DUR      → 병용·연령·임부 금기, 노인주의
 * </pre>
 *
 * <p>Not a JPA entity. Reference data we do not own, fetched per request and cached in Redis.
 *
 * @param id provider-namespaced, {@code drug:mfds:202005623} (spec §4-3)
 * @param nameKo shown in Korean script so the user can point at it on a shelf
 * @param ingredientsEn what the allergy check compares against — from {@code ITEM_INGR_NAME} on the
 *     list op, or {@code MAIN_INGR_ENG} on the detail op
 * @param narrative the Korean guidance text; null on a list result, populated on a detail lookup
 * @param durWarnings empty when the ministry has published nothing for this product
 */
public record Drug(
        String id,
        String itemSeq,
        String nameKo,
        String nameEn,
        String manufacturerKo,
        List<String> ingredientsEn,
        String mainIngredientKo,
        PrescriptionStatus prescriptionStatus,
        Narrative narrative,
        List<DurWarning> durWarnings,
        AllergyCheck allergyCheck,
        SourceRef source) {

    public static String idOf(String itemSeq) {
        return "drug:mfds:" + itemSeq;
    }

    /**
     * e약은요's consumer-facing Q&A text. Every field is prose, in Korean, and may be null.
     *
     * <p>We do not translate it here. The model summarises it in pass 2 of the RAG flow, constrained
     * to what these fields actually say.
     */
    public record Narrative(
            String efficacy,
            String useMethod,
            String caution,
            String warning,
            String interaction,
            String sideEffect,
            String storage) {

        public static final Narrative EMPTY = new Narrative(null, null, null, null, null, null, null);
    }
}
