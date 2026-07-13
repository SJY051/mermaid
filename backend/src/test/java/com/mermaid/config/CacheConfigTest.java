package com.mermaid.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mermaid.common.SourceRef;
import com.mermaid.facility.HospitalDetailApiClient;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CacheConfigTest {

    @Test
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
                                true),
                        SourceRef.DataMode.FIXTURE);
        var pair =
                new CacheConfig()
                        .redisCacheConfiguration(new ObjectMapper().findAndRegisterModules())
                        .getValueSerializationPair();

        Object restored = pair.read(pair.write(value));

        assertThat(restored).isEqualTo(value);
    }
}
