package com.mermaid.facility;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis-backed daily admission boundary for the NMC credential.
 *
 * <p>Directory and weekly-schedule calls share an 800-call budget; detail-by-id calls get the
 * remaining 100-call reserve. Together they stay below NMC's 1,000 calls/day allowance, and the
 * Redis counter survives a restart and is shared by every application instance. Refusing a request
 * when Redis is unavailable is intentional: calling NMC without an admission decision would turn a
 * cache outage into a quota outage.
 */
@Slf4j
@Component
final class RedisNmcCallQuota implements NmcCallQuota {

    static final int DIRECTORY_AND_SCHEDULE_LIMIT = 800;
    static final int DETAIL_LIMIT = 100;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String KEY_PREFIX = "nmcCallQuota.v1:";

    private static final DefaultRedisScript<Long> ACQUIRE =
            new DefaultRedisScript<>(
                    "local used = tonumber(redis.call('HGET', KEYS[1], ARGV[1]) or '0') "
                            + "if used >= tonumber(ARGV[2]) then return 0 end "
                            + "redis.call('HINCRBY', KEYS[1], ARGV[1], 1) "
                            + "redis.call('EXPIRE', KEYS[1], ARGV[3]) "
                            + "return 1",
                    Long.class);

    private final StringRedisTemplate redis;

    RedisNmcCallQuota(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryAcquire(NmcCallKind kind) {
        LocalDate today = LocalDate.now(KST);
        int limit = kind == NmcCallKind.DETAIL ? DETAIL_LIMIT : DIRECTORY_AND_SCHEDULE_LIMIT;
        String bucket = kind == NmcCallKind.DETAIL ? "detail" : "directory-and-schedule";
        try {
            Long admitted =
                    redis.execute(
                            ACQUIRE,
                            java.util.List.of(KEY_PREFIX + today),
                            bucket,
                            Integer.toString(limit),
                            Long.toString(secondsUntilTomorrow()));
            return Long.valueOf(1).equals(admitted);
        } catch (RuntimeException ignored) {
            // Do not attach the Redis exception: connection details must not become a log throwable.
            log.warn("nmc_quota_admission_failed action=DENY");
            return false;
        }
    }

    private static long secondsUntilTomorrow() {
        var tomorrow = LocalDate.now(KST).plusDays(1).atStartOfDay(KST).toInstant();
        return Math.max(1, Duration.between(java.time.Instant.now(), tomorrow).getSeconds());
    }
}
