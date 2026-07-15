package com.mermaid.facility;

/** A Naver geocoding match translated into the coordinate names our API uses. */
public record GeocodeResult(
        String roadAddress,
        String jibunAddress,
        String englishAddress,
        double latitude,
        double longitude) {}
