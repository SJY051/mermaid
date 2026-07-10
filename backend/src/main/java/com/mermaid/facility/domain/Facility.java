package com.mermaid.facility.domain;

import com.mermaid.common.SourceRef;

/**
 * A pharmacy or hospital as the frontend sees it. Not a JPA entity — assembled from public API
 * responses per request (and cached in Redis), never stored in our schema.
 *
 * @param id provider-namespaced, e.g. {@code facility:nmc:12345} (spec §4-3). Never a name.
 * @param distanceMeters computed by us; the pharmacy API has no radius parameter (spec §2-9)
 * @param operation computed by us; no public API exposes "open now" (spec §2-13)
 * @param source provenance. Every fact carries one, and fixtures say so (spec §2-14)
 */
public record Facility(
        String id,
        FacilityType type,
        String nameKo,
        String nameEn,
        String addressKo,
        String addressEn,
        String phone,
        Double latitude,
        Double longitude,
        Double distanceMeters,
        FacilityOperation operation,
        SourceRef source) {

    /** Builds the namespaced id. `hpid` for a pharmacy, `ykiho` for a hospital. */
    public static String idOf(String providerKey, String recordId) {
        return "facility:" + providerKey + ":" + recordId;
    }
}
