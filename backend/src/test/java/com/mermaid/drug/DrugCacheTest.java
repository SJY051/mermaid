package com.mermaid.drug;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mermaid.common.FixtureLoader;
import com.mermaid.config.DataModeProperties;
import com.mermaid.config.PublicApiProperties;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class DrugCacheTest {

    private static final Instant FETCHED_AT = Instant.parse("2026-07-16T04:30:00Z");

    @Test
    @DisplayName("every typed drug cache uses a versioned name and binds mode, route, and query")
    void typedCacheKeysIncludeEveryResultBoundary() throws Exception {
        List<CacheContract> contracts =
                List.of(
                        contract(
                                DrugPermissionApiClient.class,
                                "findByNameBatch",
                                new Class<?>[] {String.class},
                                "permissionByNameV2",
                                "#itemName"),
                        contract(
                                DrugPermissionApiClient.class,
                                "findByIngredientBatch",
                                new Class<?>[] {String.class},
                                "permissionByIngredientV2",
                                "#ingredientEn"),
                        contract(
                                DrugPermissionApiClient.class,
                                "detailBatch",
                                new Class<?>[] {String.class},
                                "permissionDetailV2",
                                "#itemSeq"),
                        contract(
                                EasyDrugApiClient.class,
                                "findByNameBatch",
                                new Class<?>[] {String.class},
                                "easyDrugByNameV2",
                                "#itemName"),
                        contract(
                                EasyDrugApiClient.class,
                                "findBySeqBatch",
                                new Class<?>[] {String.class},
                                "easyDrugBySeqV2",
                                "#itemSeq"),
                        contract(
                                DurApiClient.class,
                                "warningsForBatch",
                                new Class<?>[] {String.class},
                                "durWarningsV2",
                                "#itemSeq"));

        assertThat(contracts).allSatisfy(contract -> {
            assertThat(contract.annotation().value()).containsExactly(contract.cacheName());
            assertThat(contract.cacheName()).endsWith("V2");
            assertThat(contract.annotation().key())
                    .contains("#root.target.cacheRoute()")
                    .contains(contract.queryExpression());
        });
    }

    @Test
    @DisplayName("cache routes distinguish all data modes and configured from keyless clients")
    void cacheRoutesSeparateModeAndCredentialPath() {
        FixtureLoader fixtures = new FixtureLoader(new ObjectMapper());

        List<String> routes =
                java.util.Arrays.stream(DataModeProperties.DataMode.values())
                        .flatMap(mode -> List.of(configured(), keyless()).stream().flatMap(properties -> {
                            var dataMode = new DataModeProperties(mode);
                            return List.of(
                                            new DrugPermissionApiClient(null, properties, dataMode, fixtures)
                                                    .cacheRoute(),
                                            new EasyDrugApiClient(null, properties, dataMode, fixtures).cacheRoute(),
                                            new DurApiClient(null, properties, dataMode, fixtures).cacheRoute())
                                    .stream();
                        }))
                        .toList();

        assertThat(routes).hasSize(18);
        assertThat(routes).allSatisfy(route ->
                assertThat(route).matches("mode=(fixture|live|hybrid)\\|route=(configured|keyless)"));
        assertThat(routes.stream().distinct()).hasSize(6);
    }

    @Test
    @DisplayName("a real Spring cache hit preserves the first batch while mode routes stay isolated")
    void runtimeCachePreservesProvenanceAndSeparatesModes() {
        AtomicInteger liveCalls = new AtomicInteger();
        AtomicInteger hybridCalls = new AtomicInteger();
        AtomicInteger clockReads = new AtomicInteger();
        FixtureLoader fixtures = new FixtureLoader(new ObjectMapper());
        PublicApiProperties properties = configured();
        DrugPermissionApiClient liveTarget =
                new DrugPermissionApiClient(
                        fixtureWebClient("permission_ibuprofen.json", liveCalls),
                        properties,
                        new DataModeProperties(DataModeProperties.DataMode.LIVE),
                        fixtures,
                        sequenceClock(clockReads));
        DrugPermissionApiClient hybridTarget =
                new DrugPermissionApiClient(
                        failingWebClient(hybridCalls),
                        properties,
                        new DataModeProperties(DataModeProperties.DataMode.HYBRID),
                        fixtures,
                        Clock.fixed(FETCHED_AT.minusSeconds(30), ZoneOffset.UTC));
        CacheManager cacheManager = new ConcurrentMapCacheManager("permissionByIngredientV2");

        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(CacheInfrastructure.class);
            context.registerBean("cacheManager", CacheManager.class, () -> cacheManager);
            context.registerBean("livePermission", DrugPermissionApiClient.class, () -> liveTarget);
            context.registerBean("hybridPermission", DrugPermissionApiClient.class, () -> hybridTarget);
            context.refresh();
            DrugPermissionApiClient live =
                    context.getBean("livePermission", DrugPermissionApiClient.class);
            DrugPermissionApiClient hybrid =
                    context.getBean("hybridPermission", DrugPermissionApiClient.class);

            DrugPermissionApiClient.PermissionBatch first =
                    live.findByIngredientBatch("Ibuprofen");
            DrugPermissionApiClient.PermissionBatch cached =
                    live.findByIngredientBatch("Ibuprofen");
            DrugPermissionApiClient.PermissionBatch isolated =
                    hybrid.findByIngredientBatch("Ibuprofen");

            assertThat(liveCalls).hasValue(1);
            assertThat(clockReads).hasValue(1);
            assertThat(cached).isEqualTo(first);
            assertThat(cached.origin()).isEqualTo(com.mermaid.common.SourceRef.DataMode.LIVE);
            assertThat(cached.retrievedAt()).isEqualTo(first.retrievedAt());
            assertThat(hybridCalls).hasValue(1);
            assertThat(isolated.origin())
                    .isEqualTo(com.mermaid.common.SourceRef.DataMode.FIXTURE);
            assertThat(isolated.retrievedAt()).isEqualTo(FETCHED_AT.minusSeconds(30));
        }
    }

    private static CacheContract contract(
            Class<?> type,
            String methodName,
            Class<?>[] parameterTypes,
            String cacheName,
            String queryExpression)
            throws Exception {
        Method method = type.getMethod(methodName, parameterTypes);
        return new CacheContract(method.getAnnotation(Cacheable.class), cacheName, queryExpression);
    }

    private static PublicApiProperties configured() {
        return properties("service-key");
    }

    private static PublicApiProperties keyless() {
        return properties("");
    }

    private static PublicApiProperties properties(String serviceKey) {
        return new PublicApiProperties(
                serviceKey,
                "https://example.invalid/pharmacy",
                "https://example.invalid/hospital",
                "https://example.invalid/hospital-detail",
                "https://example.invalid/easy",
                "https://example.invalid/permission",
                "https://example.invalid/dur");
    }

    private static WebClient fixtureWebClient(String fixture, AtomicInteger calls) {
        String body = new FixtureLoader(new ObjectMapper()).load(fixture).toString();
        return WebClient.builder()
                .exchangeFunction(request -> {
                    calls.incrementAndGet();
                    return Mono.just(
                            ClientResponse.create(HttpStatus.OK)
                                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                                    .body(body)
                                    .build());
                })
                .build();
    }

    private static WebClient failingWebClient(AtomicInteger calls) {
        return WebClient.builder()
                .exchangeFunction(request -> {
                    calls.incrementAndGet();
                    return Mono.error(new IllegalStateException("upstream unavailable"));
                })
                .build();
    }

    private static Clock sequenceClock(AtomicInteger reads) {
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return FETCHED_AT.plusSeconds(reads.getAndIncrement());
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCaching(proxyTargetClass = true)
    static class CacheInfrastructure {}

    private record CacheContract(Cacheable annotation, String cacheName, String queryExpression) {}
}
