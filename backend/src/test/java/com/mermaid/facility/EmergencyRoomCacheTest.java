package com.mermaid.facility;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;

/** Verifies the SpEL key through Spring's {@code @Cacheable} proxy, not a direct unit call. */
@SpringBootTest
@ActiveProfiles("test")
class EmergencyRoomCacheTest {

    @Autowired EmergencyRoomApiClient emergencyRooms;
    @Autowired CacheManager cacheManager;

    @BeforeEach
    void clearCache() {
        cacheManager.getCache("emergencyRoomsNear.v1").clear();
    }

    @Test
    void sameGridCellUsesTheCacheThroughSpringProxy() {
        var first = emergencyRooms.findNear(37.565501, 126.9779);
        var second = emergencyRooms.findNear(37.566499, 126.9779);

        assertThat(second).isSameAs(first);
        assertThat(second.emergencyRooms()).isNotEmpty();
    }
}
