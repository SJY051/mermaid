package com.mermaid.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mermaid.common.SourceRef;
import com.mermaid.facility.PharmacyApiClient;
import com.mermaid.facility.domain.DutyTable;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.RedisSerializationContext;

/**
 * Guards the cache serializer against the §11 trap that {@code cache.type=simple} can never catch:
 * the heap-backed test cache stores objects by reference, so a value that JDK- or JSON-serializes
 * only on a real Redis fails silently in every other test. These round-trip the real
 * {@link CacheConfig} serializer, which is the only place that failure is reachable.
 */
class CacheConfigTest {

    private static RedisSerializationContext.SerializationPair<Object> pair() {
        return new CacheConfig()
                .redisCacheConfiguration(new ObjectMapper())
                .getValueSerializationPair();
    }

    @Test
    @DisplayName("the weekly-hours cache value round-trips through the JSON serializer intact")
    void weeklyHoursValueRoundTrips() {
        // The exact value PharmacyApiClient.weeklyHours caches. This goes red for a bare
        // Map<Integer, …> (Integer keys read back as String) and for a String[] value (its type id
        // "[Ljava.lang.String;" is denied by the validator) — the two ways DutyTable exists to avoid.
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
        // What PharmacyApiClient.findNear caches. The @JsonValue enum and the nested record must both
        // survive the round-trip, or a cached fallback would lose its FIXTURE label (§2-14).
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
}
