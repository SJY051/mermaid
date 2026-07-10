package com.mermaid.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

/**
 * Redis caches values as JSON, not with JDK serialization.
 *
 * <p>The default serializer requires {@link java.io.Serializable}, and every value we cache is a Java
 * {@code record} — which is not. The whole drug lookup died with {@code NotSerializableException} the
 * first time it ran against a real Redis. Tests never saw it: {@code cache.type=simple} keeps objects
 * on the heap.
 *
 * <p>{@code DefaultTyping.EVERYTHING} rather than {@code NON_FINAL}, because records <i>are</i> final
 * and would otherwise be written without the type information needed to read them back. That embeds
 * class names in the cache, so a validator restricts what may be instantiated to our own packages —
 * a cache entry must never be able to name an arbitrary class.
 */
@Configuration
public class CacheConfig {

    /** Public API data changes slowly, and the pharmacy quota is 1,000 calls a day. */
    private static final Duration TTL = Duration.ofHours(6);

    @Bean
    RedisCacheConfiguration redisCacheConfiguration(ObjectMapper base) {
        PolymorphicTypeValidator validator =
                BasicPolymorphicTypeValidator.builder()
                        .allowIfSubType("com.mermaid.")
                        .allowIfSubType("java.util.")
                        .allowIfSubType("java.time.")
                        .allowIfSubType("java.lang.")
                        .build();

        ObjectMapper mapper = base.copy();
        mapper.activateDefaultTyping(validator, ObjectMapper.DefaultTyping.EVERYTHING, JsonTypeInfo.As.PROPERTY);

        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(TTL)
                // A cached "no results" would hide a provider outage behind an empty list.
                .disableCachingNullValues()
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new GenericJackson2JsonRedisSerializer(mapper)));
    }
}
