package com.mermaid.facility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisNmcCallQuotaTest {

    @Test
    @DisplayName("directory and schedule share one Redis budget while detail keeps its reserve")
    void sharesTheNmcCredentialBudgetAcrossEndpointKinds() {
        StringRedisTemplate redis = org.mockito.Mockito.mock(StringRedisTemplate.class);
        when(redis.execute(any(), anyList(), any(), any(), any())).thenReturn(1L);
        var quota = new RedisNmcCallQuota(redis);

        quota.tryAcquire(NmcCallKind.DIRECTORY);
        quota.tryAcquire(NmcCallKind.SCHEDULE);
        quota.tryAcquire(NmcCallKind.DETAIL);

        verify(redis, times(2))
                .execute(
                        any(),
                        anyList(),
                        eq("directory-and-schedule"),
                        eq(Integer.toString(RedisNmcCallQuota.DIRECTORY_AND_SCHEDULE_LIMIT)),
                        anyString());
        verify(redis)
                .execute(
                        any(),
                        anyList(),
                        eq("detail"),
                        eq(Integer.toString(RedisNmcCallQuota.DETAIL_LIMIT)),
                        anyString());

        // Raising either reserve until their sum reaches 1,000 removes the outage headroom this
        // boundary exists to protect, and turns this red.
        assertThat(
                        RedisNmcCallQuota.DIRECTORY_AND_SCHEDULE_LIMIT
                                + RedisNmcCallQuota.DETAIL_LIMIT)
                .isEqualTo(900)
                .isLessThan(1_000);
    }
}
