package com.mermaid.facility;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;

/** Proves the {@code @Cacheable} SpEL key works through Spring's proxy, not just in a direct call. */
@SpringBootTest
@ActiveProfiles("test")
class SeoulPharmacyOperatingCacheTest {

    @Autowired SeoulPharmacyOperatingApiClient client;
    @Autowired CacheManager cacheManager;

    @BeforeEach
    void clearCache() {
        cacheManager.getCache("seoulPharmacyOperating.v1").clear();
    }

    @Test
    void fixtureTableUsesTheConfiguredCacheKeyThroughTheSpringProxy() {
        var first = client.operatingTable();
        var second = client.operatingTable();

        assertThat(second).isSameAs(first);
        assertThat(first.origin()).isEqualTo(com.mermaid.common.SourceRef.DataMode.FIXTURE);
    }
}
