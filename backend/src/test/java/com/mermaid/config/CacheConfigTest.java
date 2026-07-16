package com.mermaid.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mermaid.common.SourceRef;
import com.mermaid.drug.DrugPermissionApiClient;
import com.mermaid.drug.DurApiClient;
import com.mermaid.drug.EasyDrugApiClient;
import com.mermaid.facility.EmergencyRoomApiClient;
import com.mermaid.facility.HospitalApiClient;
import com.mermaid.facility.HospitalDetailApiClient;
import com.mermaid.facility.HolidayApiClient;
import com.mermaid.facility.PharmacyApiClient;
import com.mermaid.facility.domain.DutyTable;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.RedisSerializationContext;

/** Verifies cache values against the real JSON serializer rather than the heap-only test cache. */
class CacheConfigTest {

    private static RedisSerializationContext.SerializationPair<Object> pair() {
        return new CacheConfig()
                .redisCacheConfiguration(new ObjectMapper().findAndRegisterModules())
                .getValueSerializationPair();
    }

    @Test
    @DisplayName("the weekly-hours cache value round-trips through the JSON serializer intact")
    void weeklyHoursValueRoundTrips() {
        DutyTable table =
                new DutyTable(
                        Map.of(1, List.of("0900", "1900"), 6, List.of("1000", "1400")),
                        SourceRef.DataMode.LIVE);

        RedisSerializationContext.SerializationPair<Object> pair = pair();
        ByteBuffer bytes = pair.write(table);
        Object back = pair.read(bytes);

        assertThat(back).isEqualTo(table);
        assertThat(((DutyTable) back).byDay().get(1)).containsExactly("0900", "1900");
        assertThat(((DutyTable) back).origin()).isEqualTo(SourceRef.DataMode.LIVE);
    }

    @Test
    @DisplayName("the pharmacy-batch cache value round-trips, provenance included")
    void pharmacyBatchRoundTrips() {
        Instant retrievedAt = Instant.parse("2026-07-16T00:00:00Z");
        var raw =
                new PharmacyApiClient.RawPharmacy(
                        "C1110693", "청실약국", "서울 중구", "02-000-0000", 37.5, 126.9, 0.14, "0900", "1900");
        var batch =
                new PharmacyApiClient.PharmacyBatch(
                        List.of(raw),
                        SourceRef.DataMode.LIVE,
                        PharmacyApiClient.PharmacyProvider.HIRA,
                        retrievedAt);

        RedisSerializationContext.SerializationPair<Object> pair = pair();
        Object back = pair.read(pair.write(batch));

        assertThat(back).isEqualTo(batch);
        assertThat(((PharmacyApiClient.PharmacyBatch) back).origin())
                .isEqualTo(SourceRef.DataMode.LIVE);
        assertThat(((PharmacyApiClient.PharmacyBatch) back).provider())
                .isEqualTo(PharmacyApiClient.PharmacyProvider.HIRA);
        assertThat(((PharmacyApiClient.PharmacyBatch) back).retrievedAt())
                .isEqualTo(retrievedAt);
    }

    @Test
    @DisplayName("the HIRA pharmacy identity cache value round-trips, including a missing match")
    void hiraPharmacyIdentityRoundTrips() {
        Instant retrievedAt = Instant.parse("2026-07-16T00:00:00Z");
        var raw =
                new PharmacyApiClient.RawPharmacy(
                        "YKIHO-1", "약국", "서울 중구", null, 37.5, 126.9, null, null, null);
        var found =
                new PharmacyApiClient.HiraIdentityBatch(
                        raw, SourceRef.DataMode.LIVE, retrievedAt);
        var missing =
                new PharmacyApiClient.HiraIdentityBatch(
                        null, SourceRef.DataMode.LIVE, retrievedAt);

        assertThat(pair().read(pair().write(found))).isEqualTo(found);
        assertThat(pair().read(pair().write(missing))).isEqualTo(missing);
    }

    @Test
    @DisplayName("the pharmacy-detail cache value round-trips, timetable, coordinates and retrievedAt included")
    void pharmacyDetailValueRoundTrips() {
        // A fixed retrievedAt must survive Redis so a cache hit is not restamped as fresh (issue #95).
        var retrievedAt = java.time.Instant.parse("2026-07-10T05:06:03.099082Z");
        var value =
                new PharmacyApiClient.PharmacyDetailBatch(
                        new PharmacyApiClient.PharmacyDetail(
                                "C1110693",
                                "청실약국",
                                "서울 중구",
                                "02-000-0000",
                                37.5672818668855,
                                126.978921749794,
                                new DutyTable(
                                        Map.of(1, List.of("0900", "1900")), SourceRef.DataMode.FIXTURE)),
                        SourceRef.DataMode.FIXTURE,
                        retrievedAt);

        Object back = pair().read(pair().write(value));

        assertThat(back).isEqualTo(value);
        assertThat(((PharmacyApiClient.PharmacyDetailBatch) back).retrievedAt()).isEqualTo(retrievedAt);
    }

    @Test
    @DisplayName("a not-found pharmacy-detail (null detail field) still round-trips, not just non-null")
    void pharmacyDetailNullDetailRoundTrips() {
        // disableCachingNullValues() only blocks a null *value*; the batch is non-null with a null
        // detail field. A record with a null component is exactly the shape that surprises the JSON
        // typing (§11), so prove this survives the serializer rather than only cache.type=simple.
        var value =
                new PharmacyApiClient.PharmacyDetailBatch(null, SourceRef.DataMode.FIXTURE);

        Object back = pair().read(pair().write(value));

        assertThat(back).isEqualTo(value);
        assertThat(((PharmacyApiClient.PharmacyDetailBatch) back).detail()).isNull();
    }

    @Test
    @DisplayName("the hospital-list cache value round-trips, provenance included")
    void hospitalBatchRoundTrips() {
        var raw =
                new HospitalApiClient.RawHospital(
                        "YKIHO-1", "Hospital", "Seoul", "03181", "02-000-0000", "11", 37.5, 126.9, 125.0);
        var batch = new HospitalApiClient.HospitalBatch(List.of(raw), SourceRef.DataMode.FIXTURE);

        Object back = pair().read(pair().write(batch));

        assertThat(back).isEqualTo(batch);
        assertThat(((HospitalApiClient.HospitalBatch) back).origin())
                .isEqualTo(SourceRef.DataMode.FIXTURE);
    }

    @Test
    @DisplayName("the hospital-detail cache value round-trips through the JSON serializer intact")
    void hospitalDetailCacheValueRoundTripsAsJson() {
        var value =
                new HospitalDetailApiClient.HospitalDetailBatch(
                        new HospitalDetailApiClient.HospitalDetail(
                                "YKIHO-1",
                                Map.of(1, List.of("0830", "1700")),
                                Optional.of(
                                        new HospitalDetailApiClient.LunchBreak(
                                                LocalTime.of(12, 30), LocalTime.of(13, 30))),
                                true,
                                true,
                                true,
                                false),
                        SourceRef.DataMode.FIXTURE);

        Object restored = pair().read(pair().write(value));

        assertThat(restored).isEqualTo(value);
    }

    @Test
    @DisplayName("the emergency-room batch cache value round-trips, provenance included")
    void emergencyRoomBatchRoundTrips() {
        var raw =
                new EmergencyRoomApiClient.RawEmergencyRoom(
                        "A1100006", "강북삼성병원", "서울 종로구", "02-000-0000", 37.5, 126.9);
        var batch =
                new EmergencyRoomApiClient.EmergencyRoomBatch(
                        List.of(raw), SourceRef.DataMode.FIXTURE);

        RedisSerializationContext.SerializationPair<Object> pair = pair();
        Object back = pair.read(pair.write(batch));

        assertThat(back).isEqualTo(batch);
        assertThat(((EmergencyRoomApiClient.EmergencyRoomBatch) back).origin())
                .isEqualTo(SourceRef.DataMode.FIXTURE);
    }

    @Test
    @DisplayName("the yearly public-holiday cache value round-trips through JSON")
    void holidayYearRoundTrips() {
        var year =
                new HolidayApiClient.HolidayYear(
                        java.util.Set.of(LocalDate.of(2026, 5, 5), LocalDate.of(2026, 10, 5)));

        Object restored = pair().read(pair().write(year));

        assertThat(restored).isEqualTo(year);
    }

    @Test
    @DisplayName("typed drug cache values round-trip with actual origin, time, and nullable detail rows")
    void typedDrugCacheValuesRoundTrip() {
        Instant fetchedAt = Instant.parse("2026-07-16T04:30:00Z");
        List<Object> values =
                List.of(
                        new DrugPermissionApiClient.PermissionBatch(
                                List.of(), SourceRef.DataMode.FIXTURE, fetchedAt),
                        new DrugPermissionApiClient.PermissionDetailBatch(
                                null, SourceRef.DataMode.LIVE, fetchedAt),
                        new EasyDrugApiClient.NarratedBatch(
                                List.of(), SourceRef.DataMode.LIVE, fetchedAt),
                        new EasyDrugApiClient.NarratedDetailBatch(
                                null, SourceRef.DataMode.FIXTURE, fetchedAt),
                        new DurApiClient.DurKindBatch(
                                List.of(), SourceRef.DataMode.FIXTURE, fetchedAt),
                        new DurApiClient.DurBatch(
                                List.of(), SourceRef.DataMode.LIVE, fetchedAt));

        RedisSerializationContext.SerializationPair<Object> pair = pair();
        assertThat(values).allSatisfy(value -> assertThat(pair.read(pair.write(value))).isEqualTo(value));
    }
}
