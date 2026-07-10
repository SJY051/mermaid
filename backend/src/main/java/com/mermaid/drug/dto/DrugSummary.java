package com.mermaid.drug.dto;

import java.util.List;

/**
 * One medicine, assembled from both MFDS APIs.
 *
 * @param itemSeq the MFDS product id, the join key between the two APIs
 * @param itemName Korean product name — shown in Korean script so the user can point at a shelf
 * @param ingredientsEn from {@code MAIN_INGR_ENG}; what the allergy filter matches (FR-04)
 * @param allergyWarning non-null when this product hits one of the profile's avoided ingredients
 */
public record DrugSummary(
        String itemSeq,
        String itemName,
        String entpName,
        List<String> ingredientsEn,
        String efficacy,
        String useMethod,
        String caution,
        boolean prescriptionRequired,
        String allergyWarning) {}
