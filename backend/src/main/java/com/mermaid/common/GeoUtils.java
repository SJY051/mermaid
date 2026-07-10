package com.mermaid.common;

/**
 * Great-circle distance.
 *
 * <p>We need this because the pharmacy API ({@code getParmacyLcinfoInqire}) accepts only {@code
 * WGS84_LON} and {@code WGS84_LAT} — <b>there is no radius parameter</b>. It returns results sorted
 * by distance; clipping to the user's radius is our job (spec §2-9, FR-02).
 */
public final class GeoUtils {

    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private GeoUtils() {}

    /** Distance in meters between two WGS84 points. */
    public static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a =
                Math.sin(dLat / 2) * Math.sin(dLat / 2)
                        + Math.cos(Math.toRadians(lat1))
                                * Math.cos(Math.toRadians(lat2))
                                * Math.sin(dLon / 2)
                                * Math.sin(dLon / 2);
        return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public static boolean isWithin(
            double lat1, double lon1, double lat2, double lon2, double radiusMeters) {
        return haversineMeters(lat1, lon1, lat2, lon2) <= radiusMeters;
    }
}
