package com.mermaid.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mermaid.common.SourceRef;
import com.mermaid.facility.HospitalApiClient;
import com.mermaid.facility.HospitalDetailApiClient;
import com.mermaid.facility.HolidayApiClient;
import com.mermaid.facility.PharmacyApiClient;
import com.mermaid.facility.domain.DutyTable;
import java.nio.ByteBuffer;
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
        var raw =
                new PharmacyApiClient.RawPharmacy(
                        "C1110693", "청실약국", "서울 중구", "02-000-0000", 37.5, 126.9, 0.14, "0900", "1900");
        var batch =
                new PharmacyApiClient.PharmacyBatch(List.of(raw), SourceRef.DataMode.FIXTURE);

        RedisSerializationContext.SerializationPair<Object> pair = pair();
        Object back = pair.read(pair.write(batch));

        assertThat(back).isEqualTo(batch);
        assertThat(((PharmacyApiClient.PharmacyBatch) back).origin())
                .isEqualTo(SourceRef.DataMode.FIXTURE);
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
    @DisplayName("the yearly public-holiday cache value round-trips through JSON")
    void holidayYearRoundTrips() {
        var year =
                new HolidayApiClient.HolidayYear(
                        java.util.Set.of(LocalDate.of(2026, 5, 5), LocalDate.of(2026, 10, 5)));

        Object restored = pair().read(pair().write(year));

        assertThat(restored).isEqualTo(year);
    }
}
