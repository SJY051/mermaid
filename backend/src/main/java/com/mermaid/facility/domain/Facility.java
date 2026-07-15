package com.mermaid.facility.domain;

import com.mermaid.common.SourceRef;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * A pharmacy or hospital as the frontend sees it. Not a JPA entity — assembled from public API
 * responses per request (and cached in Redis), never stored in our schema.
 *
 * @param id provider-namespaced, e.g. {@code facility:nmc:12345}; HIRA hospital segments encode
 *     ykiho with base64url, while HIRA pharmacy segments also carry an encoded name for a verified
 *     detail lookup (spec §4-3).
 * @param distanceMeters computed by us from the caller's coordinate; cached directory batches use a
 *     nearby grid-cell centre
 * @param operation computed by us; no public API exposes "open now" (spec §2-13)
 * @param source provenance. Every fact carries one, and fixtures say so (spec §2-14)
 * @param emergencyDay HIRA daytime-ER flag (hospitals only); {@code null} for pharmacies or unknown
 * @param emergencyNight HIRA night-ER flag (hospitals only); {@code null} for pharmacies or unknown
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
        SourceRef source,
        Boolean emergencyDay,
        Boolean emergencyNight) {

    /** Builds the namespaced id around the provider's stable record identifier. */
    public static String idOf(String providerKey, String recordId) {
        return "facility:" + providerKey + ":" + recordId;
    }

    /**
     * base64url (no padding) so a record id survives a {@code GET /facilities/{id}} path segment.
     *
     * <p>A HIRA {@code ykiho} decodes to text like {@code $481881#51#…} and is transmitted as
     * base64, whose alphabet includes {@code /}, {@code +}, {@code =} — a raw {@code /} would break
     * path routing. NMC pharmacy {@code hpid} values are already URL-safe and use {@link #idOf}
     * directly; HIRA values are encoded before being composed into their provider-specific segment.
     */
    public static String urlSafeSegment(String raw) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** Inverse of {@link #urlSafeSegment}; the UI-03 detail endpoint resolves HIRA id components. */
    public static String decodeSegment(String segment) {
        return new String(Base64.getUrlDecoder().decode(segment), StandardCharsets.UTF_8);
    }
}
