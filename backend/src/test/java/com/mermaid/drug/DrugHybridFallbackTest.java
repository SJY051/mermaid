package com.mermaid.drug;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mermaid.common.ApiException;
import com.mermaid.common.ErrorCode;
import com.mermaid.common.FixtureLoader;
import com.mermaid.common.PublicApiException;
import com.mermaid.common.SourceRef;
import com.mermaid.config.DataModeProperties;
import com.mermaid.config.PublicApiProperties;
import com.mermaid.drug.domain.DurWarning;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class DrugHybridFallbackTest {

    private static final Instant FETCHED_AT = Instant.parse("2026-07-16T04:30:00Z");
    private static final Clock CLOCK = Clock.fixed(FETCHED_AT, ZoneOffset.UTC);
    private static final String TYLENOL = "202005623";

    @Nested
    @DisplayName("query and result binding")
    class QueryBinding {

        @Test
        @DisplayName("hybrid fallback never answers an acetaminophen query with the ibuprofen fixture")
        void impossibleHybridIngredientValueFailsClosed() {
            DrugPermissionApiClient client = permission(failingWebClient(new AtomicInteger()), HYBRID, configured());

            assertSourceUnavailable(() -> client.findByIngredientBatch("Acetaminophen"));
        }

        @Test
        @DisplayName("a hybrid fallback is usable only when every fixture row binds to the query")
        void exactHybridIngredientFallbackCarriesActualFixtureProvenance() {
            AtomicInteger calls = new AtomicInteger();
            DrugPermissionApiClient.PermissionBatch batch =
                    permission(failingWebClient(calls), HYBRID, configured())
                            .findByIngredientBatch("Ibuprofen");

            assertThat(calls).hasValue(1);
            assertThat(batch.rows()).hasSize(3).allSatisfy(row ->
                    assertThat(row.ingredientsEn()).contains("Ibuprofen"));
            assertThat(batch.origin()).isEqualTo(SourceRef.DataMode.FIXTURE);
            assertThat(batch.retrievedAt()).isEqualTo(FETCHED_AT);
        }

        @Test
        @DisplayName("a live response containing rows for another query is rejected as an invalid payload")
        void livePayloadMismatchFailsClosed() {
            AtomicInteger calls = new AtomicInteger();
            DrugPermissionApiClient client =
                    permission(fixtureWebClient("permission_ibuprofen.json", calls), LIVE, configured());

            assertThatExceptionOfType(ApiException.class)
                    .isThrownBy(() -> client.findByIngredientBatch("Acetaminophen"))
                    .satisfies(error -> {
                        assertThat(error.code()).isEqualTo(ErrorCode.SOURCE_PAYLOAD_INVALID);
                        assertThat(error).hasNoCause();
                    });
            assertThat(calls).hasValue(1);
        }

        @Test
        @DisplayName("an exact live result stays live and never consults the fixture")
        void exactLiveResultCarriesLiveProvenance() {
            AtomicInteger calls = new AtomicInteger();
            DrugPermissionApiClient.PermissionBatch batch =
                    permission(fixtureWebClient("permission_ibuprofen.json", calls), LIVE, configured())
                            .findByIngredientBatch("Ibuprofen");

            assertThat(calls).hasValue(1);
            assertThat(batch.rows()).hasSize(3).allSatisfy(row ->
                    assertThat(row.ingredientsEn()).contains("Ibuprofen"));
            assertThat(batch.origin()).isEqualTo(SourceRef.DataMode.LIVE);
            assertThat(batch.retrievedAt()).isEqualTo(FETCHED_AT);
        }

        @Test
        @DisplayName("fixture-only mode stays query-agnostic, fixture-labelled, and network-free")
        void fixtureModeRetainsItsDocumentedOfflineContract() {
            AtomicInteger calls = new AtomicInteger();
            DrugPermissionApiClient.PermissionBatch batch =
                    permission(countingWebClient(calls), FIXTURE, configured())
                            .findByIngredientBatch("Acetaminophen");

            assertThat(calls).hasValue(0);
            assertThat(batch.rows()).hasSize(3).allSatisfy(row ->
                    assertThat(row.ingredientsEn()).contains("Ibuprofen"));
            assertThat(batch.origin()).isEqualTo(SourceRef.DataMode.FIXTURE);
            assertThat(batch.retrievedAt()).isEqualTo(FETCHED_AT);
        }
    }

    @Nested
    @DisplayName("exact detail bindings")
    class DetailBinding {

        @Test
        @DisplayName("permission and e약은요 detail fixtures bind only to their exact ITEM_SEQ")
        void exactDetailFallbacksCarryFixtureOriginAndTime() {
            DrugPermissionApiClient permission = permission(failingWebClient(new AtomicInteger()), HYBRID, configured());
            EasyDrugApiClient easy = easy(failingWebClient(new AtomicInteger()), HYBRID, configured());

            DrugPermissionApiClient.PermissionDetailBatch permissionBatch = permission.detailBatch(TYLENOL);
            EasyDrugApiClient.NarratedDetailBatch easyBatch = easy.findBySeqBatch(TYLENOL);

            assertThat(permissionBatch.row().itemSeq()).isEqualTo(TYLENOL);
            assertThat(permissionBatch.origin()).isEqualTo(SourceRef.DataMode.FIXTURE);
            assertThat(permissionBatch.retrievedAt()).isEqualTo(FETCHED_AT);
            assertThat(easyBatch.row().itemSeq()).isEqualTo(TYLENOL);
            assertThat(easyBatch.origin()).isEqualTo(SourceRef.DataMode.FIXTURE);
            assertThat(easyBatch.retrievedAt()).isEqualTo(FETCHED_AT);

            assertSourceUnavailable(() -> permission.detailBatch("999999999"));
            assertSourceUnavailable(() -> easy.findBySeqBatch("999999999"));
        }

        @Test
        @DisplayName("a DUR fallback is accepted only for the exact product in that kind fixture")
        void durKindFallbackBindsTheRequestedItemSeq() {
            DurApiClient dur = dur(failingWebClient(new AtomicInteger()), HYBRID, configured());

            DurApiClient.DurKindBatch exact = dur.byKindBatch("197100097", DurWarning.Kind.AGE);

            assertThat(exact.warnings()).singleElement().satisfies(warning ->
                    assertThat(warning.itemSeq()).isEqualTo("197100097"));
            assertThat(exact.origin()).isEqualTo(SourceRef.DataMode.FIXTURE);
            assertThat(exact.retrievedAt()).isEqualTo(FETCHED_AT);
            assertSourceUnavailable(() -> dur.byKindBatch(TYLENOL, DurWarning.Kind.AGE));
        }

        @Test
        @DisplayName("all-kind DUR assembly fails when any fixture cannot prove the requested product")
        void incompleteDurFallbackCannotBePresentedAsVerified() {
            DurApiClient dur = dur(failingWebClient(new AtomicInteger()), HYBRID, configured());

            assertSourceUnavailable(() -> dur.warningsForBatch(TYLENOL));
        }

        @Test
        @DisplayName("DrugService propagates an unprovable hybrid source instead of silently skipping it")
        void detailPropagatesSourceUnavailable() {
            WebClient failing = failingWebClient(new AtomicInteger());
            PublicApiProperties properties = configured();
            FixtureLoader fixtures = fixtures();
            DataModeProperties mode = mode(HYBRID);
            IngredientNormalizer normalizer = new IngredientNormalizer();
            DrugService service =
                    new DrugService(
                            new DrugPermissionApiClient(failing, properties, mode, fixtures, CLOCK),
                            new EasyDrugApiClient(failing, properties, mode, fixtures, CLOCK),
                            new DurApiClient(failing, properties, mode, fixtures, CLOCK),
                            new AllergyChecker(normalizer),
                            normalizer,
                            mode,
                            CLOCK);

            assertSourceUnavailable(() -> service.detail(TYLENOL, Set.of()));
        }

        @Test
        @DisplayName("DrugService uses the permission record ID, mixed origin, and oldest component time")
        void serviceOwnsCompositeProvenance() {
            Instant permissionTime = FETCHED_AT.plusSeconds(30);
            Instant easyTime = FETCHED_AT.plusSeconds(20);
            Instant durTime = FETCHED_AT.minusSeconds(10);
            DataModeProperties fixtureMode = mode(FIXTURE);
            PublicApiProperties properties = configured();
            FixtureLoader loader = fixtures();
            DrugPermissionApiClient permission =
                    new DrugPermissionApiClient(null, properties, fixtureMode, loader, CLOCK) {
                        @Override
                        public PermissionDetailBatch detailBatch(String requestedItemSeq) {
                            return new PermissionDetailBatch(
                                    new PermittedDetail(
                                            TYLENOL,
                                            "어린이타이레놀산",
                                            "켄뷰코리아",
                                            List.of("Acetaminophen"),
                                            "아세트아미노펜",
                                            com.mermaid.drug.domain.PrescriptionStatus.OTC),
                                    SourceRef.DataMode.LIVE,
                                    permissionTime);
                        }
                    };
            EasyDrugApiClient easy =
                    new EasyDrugApiClient(null, properties, fixtureMode, loader, CLOCK) {
                        @Override
                        public NarratedDetailBatch findBySeqBatch(String requestedItemSeq) {
                            return new NarratedDetailBatch(
                                    null, SourceRef.DataMode.FIXTURE, easyTime);
                        }
                    };
            DurApiClient dur =
                    new DurApiClient(null, properties, fixtureMode, loader, CLOCK) {
                        @Override
                        public DurBatch warningsForBatch(String requestedItemSeq) {
                            return new DurBatch(List.of(), SourceRef.DataMode.LIVE, durTime);
                        }
                    };
            IngredientNormalizer normalizer = new IngredientNormalizer();
            DrugService service =
                    new DrugService(
                            permission,
                            easy,
                            dur,
                            new AllergyChecker(normalizer),
                            normalizer,
                            fixtureMode,
                            CLOCK);

            var drug = service.detail("caller-supplied-alias", Set.of());

            assertThat(drug.itemSeq()).isEqualTo(TYLENOL);
            assertThat(drug.id()).isEqualTo("drug:mfds:" + TYLENOL);
            assertThat(drug.source().recordId()).isEqualTo(TYLENOL);
            assertThat(drug.source().id()).isEqualTo("src:mfds:" + TYLENOL);
            assertThat(drug.source().dataMode()).isEqualTo(SourceRef.DataMode.FIXTURE);
            assertThat(drug.source().retrievedAt()).isEqualTo(durTime);
        }
    }

    @Nested
    @DisplayName("failure boundaries")
    class FailureBoundaries {

        @Test
        @DisplayName("a missing service key fails before any drug client attempts the network")
        void keylessHybridIsNetworkFree() {
            AtomicInteger calls = new AtomicInteger();
            WebClient webClient = countingWebClient(calls);
            PublicApiProperties keyless = keyless();

            assertSourceUnavailable(() -> permission(webClient, HYBRID, keyless).findByIngredientBatch("Ibuprofen"));
            assertSourceUnavailable(() -> easy(webClient, HYBRID, keyless).findBySeqBatch(TYLENOL));
            assertSourceUnavailable(() -> dur(webClient, HYBRID, keyless).byKindBatch(TYLENOL, DurWarning.Kind.AGE));
            assertThat(calls).hasValue(0);
        }

        @Test
        @DisplayName("drug fallback errors log stable codes only, without values or throwable payloads")
        void failuresKeepSecretsQueriesAndCausesOutOfLogs() {
            String secret = "SERVER_SECRET_SENTINEL";
            String query = "PRIVATE_QUERY_SENTINEL";
            PublicApiProperties properties = configured(secret);
            WebClient failing = failingWebClient(new AtomicInteger());
            List<LoggerCapture> captures =
                    List.of(
                            capture(DrugPermissionApiClient.class),
                            capture(EasyDrugApiClient.class),
                            capture(DurApiClient.class));
            try {
                assertSourceUnavailable(() ->
                        permission(failing, HYBRID, properties).findByIngredientBatch(query));
                assertSourceUnavailable(() -> easy(failing, HYBRID, properties).findBySeqBatch(query));
                assertSourceUnavailable(() ->
                        dur(failing, HYBRID, properties).byKindBatch(query, DurWarning.Kind.AGE));
            } finally {
                captures.forEach(LoggerCapture::close);
            }

            List<ILoggingEvent> events = captures.stream()
                    .flatMap(capture -> capture.appender().list.stream())
                    .toList();
            assertThat(events).isNotEmpty().allSatisfy(event -> {
                assertThat(event.getFormattedMessage())
                        .doesNotContain(secret, query, "sentinel-upstream-failure", "https://example.invalid");
                assertThat(event.getThrowableProxy()).isNull();
            });
        }
    }

    private static void assertSourceUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatExceptionOfType(PublicApiException.class)
                .isThrownBy(call)
                .withMessage("SOURCE_UNAVAILABLE")
                .withNoCause();
    }

    private static DrugPermissionApiClient permission(
            WebClient webClient, DataModeProperties.DataMode dataMode, PublicApiProperties properties) {
        return new DrugPermissionApiClient(webClient, properties, mode(dataMode), fixtures(), CLOCK);
    }

    private static EasyDrugApiClient easy(
            WebClient webClient, DataModeProperties.DataMode dataMode, PublicApiProperties properties) {
        return new EasyDrugApiClient(webClient, properties, mode(dataMode), fixtures(), CLOCK);
    }

    private static DurApiClient dur(
            WebClient webClient, DataModeProperties.DataMode dataMode, PublicApiProperties properties) {
        return new DurApiClient(webClient, properties, mode(dataMode), fixtures(), CLOCK);
    }

    private static DataModeProperties mode(DataModeProperties.DataMode mode) {
        return new DataModeProperties(mode);
    }

    private static FixtureLoader fixtures() {
        return new FixtureLoader(new ObjectMapper());
    }

    private static WebClient failingWebClient(AtomicInteger calls) {
        return WebClient.builder()
                .exchangeFunction(request -> {
                    calls.incrementAndGet();
                    return Mono.error(new IllegalStateException("sentinel-upstream-failure"));
                })
                .build();
    }

    private static WebClient countingWebClient(AtomicInteger calls) {
        return WebClient.builder()
                .exchangeFunction(request -> {
                    calls.incrementAndGet();
                    return Mono.error(new AssertionError("network must not be called"));
                })
                .build();
    }

    private static WebClient fixtureWebClient(String fixture, AtomicInteger calls) {
        String body = fixtures().load(fixture).toString();
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

    private static PublicApiProperties configured() {
        return configured("sentinel-service-key");
    }

    private static PublicApiProperties configured(String serviceKey) {
        return new PublicApiProperties(
                serviceKey,
                "https://example.invalid/pharmacy",
                "https://example.invalid/hospital",
                "https://example.invalid/hospital-detail",
                "https://example.invalid/easy",
                "https://example.invalid/permission",
                "https://example.invalid/dur");
    }

    private static PublicApiProperties keyless() {
        return configured("");
    }

    private static LoggerCapture capture(Class<?> type) {
        Logger logger = (Logger) LoggerFactory.getLogger(type);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return new LoggerCapture(logger, appender);
    }

    private record LoggerCapture(Logger logger, ListAppender<ILoggingEvent> appender) {
        private void close() {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static final DataModeProperties.DataMode FIXTURE = DataModeProperties.DataMode.FIXTURE;
    private static final DataModeProperties.DataMode HYBRID = DataModeProperties.DataMode.HYBRID;
    private static final DataModeProperties.DataMode LIVE = DataModeProperties.DataMode.LIVE;
}
