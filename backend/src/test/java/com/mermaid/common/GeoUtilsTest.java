package com.mermaid.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GeoUtilsTest {

    // 서울시청
    private static final double CITY_HALL_LAT = 37.5663;
    private static final double CITY_HALL_LNG = 126.9779;

    @Test
    @DisplayName("distance to itself is zero")
    void zeroDistance() {
        assertThat(GeoUtils.haversineMeters(CITY_HALL_LAT, CITY_HALL_LNG, CITY_HALL_LAT, CITY_HALL_LNG))
                .isZero();
    }

    @Test
    @DisplayName("서울시청 → 광화문 is a little over 1km")
    void knownShortDistance() {
        double gwanghwamunLat = 37.5759;
        double gwanghwamunLng = 126.9769;
        assertThat(GeoUtils.haversineMeters(CITY_HALL_LAT, CITY_HALL_LNG, gwanghwamunLat, gwanghwamunLng))
                .isCloseTo(1070, within(80.0));
    }

    @Test
    @DisplayName("one degree of latitude is about 111km anywhere")
    void oneDegreeLatitude() {
        assertThat(GeoUtils.haversineMeters(37.0, 127.0, 38.0, 127.0))
                .isCloseTo(111_195, within(500.0));
    }

    @Test
    @DisplayName("isWithin uses the radius inclusively")
    void radiusInclusive() {
        assertThat(GeoUtils.isWithin(37.0, 127.0, 37.0, 127.0, 0)).isTrue();
        assertThat(GeoUtils.isWithin(CITY_HALL_LAT, CITY_HALL_LNG, 37.5759, 126.9769, 500)).isFalse();
        assertThat(GeoUtils.isWithin(CITY_HALL_LAT, CITY_HALL_LNG, 37.5759, 126.9769, 2000)).isTrue();
    }
}
