package com.mermaid.facility.domain;

/**
 * A pharmacy or hospital as the frontend sees it. Not a JPA entity — this is assembled from public
 * API responses on every request (and cached in Redis), never stored in our schema.
 *
 * @param id {@code hpid} for a pharmacy, {@code ykiho} for a hospital
 * @param distanceMeters computed by us; the pharmacy API has no radius parameter (spec §2-9)
 * @param openNow computed by us; no public API exposes this (spec §2-9)
 */
public record Facility(
        String id,
        FacilityType type,
        String name,
        String address,
        String phone,
        double latitude,
        double longitude,
        double distanceMeters,
        boolean openNow) {}
