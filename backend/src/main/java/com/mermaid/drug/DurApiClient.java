package com.mermaid.drug;

import com.fasterxml.jackson.databind.JsonNode;
import com.mermaid.common.ApiException;
import com.mermaid.common.ErrorCode;
import com.mermaid.common.FixtureLoader;
import com.mermaid.common.Parallel;
import com.mermaid.common.PublicApiException;
import com.mermaid.common.PublicApiResponse;
import com.mermaid.common.PublicApiUriBuilder;
import com.mermaid.common.SourceRef;
import com.mermaid.config.DataModeProperties;
import com.mermaid.config.PublicApiProperties;
import com.mermaid.drug.domain.DurWarning;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 식약처 DUR 품목정보 (data.go.kr 15059486, {@code DURPrdlstInfoService03}).
 *
 * <p>DUR = Drug Utilization Review: the contraindications the ministry publishes. Passing one along
 * ("a contraindication is on file for children under 3") is <i>information</i>, not medical advice —
 * which is why adding this API lowers the policy risk of spec §7 rather than raising it.
 *
 * <p>Verified against the live service on 2026-07-10:
 *
 * <ul>
 *   <li>{@code type=json}, not {@code _type=json}.
 *   <li>{@code itemSeq} filters correctly on every operation.
 *   <li><b>No pairwise endpoint.</b> You cannot ask "are A and B contraindicated?". Query by one
 *       drug and scan {@code MIXTURE_ITEM_SEQ} for the other. See {@link #contraindicatedWith}.
 *   <li><b>Age contraindications have no age field.</b> "3세 미만" lives inside the Korean
 *       {@code PROHBT_CONTENT}. Machine-readable thresholds exist only in the sibling 성분정보 service
 *       (dataset 15056780), field {@code AGE_BASE}.
 *   <li><b>{@code PROHBT_CONTENT} is often null</b> — the 노인주의 rows carry none. The type alone is
 *       the warning.
 *   <li>{@code INGR_CODE} is a {@code D######} code that does <b>not</b> join to 허가정보's
 *       {@code [M######]}. Join on {@code ITEM_SEQ}, or on DUR's own {@code MAIN_INGR}.
 * </ul>
 */
@Slf4j
@Component
public class DurApiClient {

    /** Operation names, one per warning kind. Only 병용금기 names a second drug. */
    private static final Map<DurWarning.Kind, String> OPERATIONS =
            Map.of(
                    DurWarning.Kind.COMBINATION, "getUsjntTabooInfoList03",
                    DurWarning.Kind.AGE, "getSpcifyAgrdeTabooInfoList03",
                    DurWarning.Kind.PREGNANCY, "getPwnmTabooInfoList03",
                    DurWarning.Kind.ELDERLY, "getOdsnAtentInfoList03");

    /** A product with no warnings of a kind returns {@code totalCount: 0} and no items. */
    private static final String FIXTURE_EMPTY = "dur_empty.json";
    private static final Map<DurWarning.Kind, String> FIXTURES =
            Map.of(
                    DurWarning.Kind.COMBINATION, "dur_usjnt.json",
                    DurWarning.Kind.AGE, "dur_age.json",
                    DurWarning.Kind.PREGNANCY, FIXTURE_EMPTY,
                    DurWarning.Kind.ELDERLY, "dur_elderly.json");

    private static final int MAX_ROWS = 20;

    /** All four kinds at once. There are only ever four. */
    private static final int KIND_CONCURRENCY = 4;

    private final WebClient publicApiWebClient;
    private final PublicApiProperties properties;
    private final DataModeProperties dataMode;
    private final FixtureLoader fixtures;
    private final Clock clock;

    @Autowired
    public DurApiClient(
            WebClient publicApiWebClient,
            PublicApiProperties properties,
            DataModeProperties dataMode,
            FixtureLoader fixtures,
            Clock clock) {
        this.publicApiWebClient = publicApiWebClient;
        this.properties = properties;
        this.dataMode = dataMode;
        this.fixtures = fixtures;
        this.clock = clock;
    }

    DurApiClient(
            WebClient publicApiWebClient,
            PublicApiProperties properties,
            DataModeProperties dataMode,
            FixtureLoader fixtures) {
        this(publicApiWebClient, properties, dataMode, fixtures, Clock.systemUTC());
    }

    /**
     * Every published warning for one product, across all four kinds.
     *
     * <p>Four upstream calls, and no one of them depends on another's answer — so they go together.
     * At ~1.5s each that is four and a half seconds saved on every uncached drug, and a chat turn
     * assembles three of them. Cached hard afterwards: a contraindication notice changes when the
     * ministry issues one, which is to say rarely.
     *
     * <p>Results stay in {@code Kind} declaration order, which the tests rely on.
     */
    public List<DurWarning> warningsFor(String itemSeq) {
        return warningsForBatch(itemSeq).warnings();
    }

    @Cacheable(value = "durWarningsV2", key = "#root.target.cacheRoute() + '|seq=' + #itemSeq")
    public DurBatch warningsForBatch(String itemSeq) {
        List<DurKindBatch> perKind =
                Parallel.map(
                        List.of(DurWarning.Kind.values()),
                        KIND_CONCURRENCY,
                        kind -> byKindBatch(itemSeq, kind));

        List<DurWarning> all = new ArrayList<>();
        perKind.forEach(batch -> all.addAll(batch.warnings()));
        SourceRef.DataMode origin =
                perKind.stream().anyMatch(batch -> batch.origin() == SourceRef.DataMode.FIXTURE)
                        ? SourceRef.DataMode.FIXTURE
                        : SourceRef.DataMode.LIVE;
        Instant retrievedAt =
                perKind.stream().map(DurKindBatch::retrievedAt).min(Instant::compareTo).orElseGet(this::now);
        return new DurBatch(all, origin, retrievedAt);
    }

    public List<DurWarning> byKind(String itemSeq, DurWarning.Kind kind) {
        return byKindBatch(itemSeq, kind).warnings();
    }

    public DurKindBatch byKindBatch(String itemSeq, DurWarning.Kind kind) {
        String operationCode = "dur_" + kind.wire();
        if (dataMode.isFixtureOnly()) {
            return new DurKindBatch(
                    parse(fixtures.load(FIXTURES.get(kind)), kind), SourceRef.DataMode.FIXTURE, now());
        }
        requireConfigured(operationCode);

        List<DurWarning> rows;
        try {
            rows = parse(fetchLive(OPERATIONS.get(kind), Map.of("itemSeq", itemSeq)), kind);
        } catch (RuntimeException failure) {
            if (dataMode.allowsFallback()) {
                return fixtureKind(itemSeq, kind, operationCode);
            }
            throw unavailable(operationCode, "UPSTREAM_FAILURE");
        }
        if (rows.stream().anyMatch(row -> !itemSeq.equals(row.itemSeq()))) {
            throw payloadInvalid(operationCode);
        }
        return new DurKindBatch(rows, SourceRef.DataMode.LIVE, now());
    }

    /**
     * Which of {@code otherItemSeqs} must not be taken alongside {@code itemSeq}.
     *
     * <p>There is no endpoint that accepts two drugs, so we fetch this drug's contraindicated
     * partners and intersect. For a basket of N medicines that is N calls and a set intersection.
     */
    public Set<String> contraindicatedWith(String itemSeq, Set<String> otherItemSeqs) {
        Set<String> hits = new LinkedHashSet<>();
        for (DurWarning w : byKindBatch(itemSeq, DurWarning.Kind.COMBINATION).warnings()) {
            if (w.pairedItemSeq() != null && otherItemSeqs.contains(w.pairedItemSeq())) {
                hits.add(w.pairedItemSeq());
            }
        }
        return hits;
    }

    private DurKindBatch fixtureKind(String itemSeq, DurWarning.Kind kind, String operationCode) {
        List<DurWarning> rows;
        try {
            rows = parse(fixtures.load(FIXTURES.get(kind)), kind);
        } catch (RuntimeException fixtureFailure) {
            throw unavailable(operationCode, "FIXTURE_UNAVAILABLE");
        }
        if (rows.isEmpty() || rows.stream().anyMatch(row -> !itemSeq.equals(row.itemSeq()))) {
            throw unavailable(operationCode, "FIXTURE_UNBOUND");
        }
        log.warn("drug_api_fallback operation={} reason=UPSTREAM_FAILURE", operationCode);
        return new DurKindBatch(rows, SourceRef.DataMode.FIXTURE, now());
    }

    private JsonNode fetchLive(String operation, Map<String, Object> params) {
        return publicApiWebClient
                .get()
                .uri(uriFor(operation, params))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
    }

    public String cacheRoute() {
        return "mode="
                + dataMode.dataMode().wire()
                + "|route="
                + (properties.isConfigured() ? "configured" : "keyless");
    }

    private void requireConfigured(String operationCode) {
        if (!properties.isConfigured()) {
            throw unavailable(operationCode, "KEY_MISSING");
        }
    }

    private PublicApiException unavailable(String operationCode, String reason) {
        log.warn("drug_api_rejected operation={} reason={}", operationCode, reason);
        return new PublicApiException("SOURCE_UNAVAILABLE");
    }

    private ApiException payloadInvalid(String operationCode) {
        log.warn("drug_api_rejected operation={} reason=SOURCE_PAYLOAD_INVALID", operationCode);
        return new ApiException(ErrorCode.SOURCE_PAYLOAD_INVALID, "SOURCE_PAYLOAD_INVALID");
    }

    private Instant now() {
        return Instant.now(clock);
    }

    URI uriFor(String operation, Map<String, Object> params) {
        PublicApiUriBuilder b =
                PublicApiUriBuilder.of(properties.durBaseUrl(), operation)
                        .serviceKey(properties.serviceKey())
                        .param("numOfRows", MAX_ROWS)
                        .param("pageNo", 1)
                        .param("type", "json"); // NOT `_type`. See the class doc.
        params.forEach(b::param);
        return b.build();
    }

    /**
     * Maps rows onto {@link DurWarning}.
     *
     * <p>{@code PROHBT_CONTENT} and {@code MIXTURE_*} are legitimately absent on some rows. Treat a
     * null as "no explanation published", never as a parse failure — the warning still stands.
     */
    private List<DurWarning> parse(JsonNode raw, DurWarning.Kind kind) {
        PublicApiResponse response = PublicApiResponse.of(raw).requireOk();

        List<DurWarning> out = new ArrayList<>();
        for (JsonNode row : response.items()) {
            String itemSeq = PublicApiResponse.text(row, "ITEM_SEQ");
            out.add(
                    new DurWarning(
                            kind,
                            itemSeq,
                            PublicApiResponse.text(row, "ITEM_NAME"),
                            PublicApiResponse.text(row, "INGR_ENG_NAME"),
                            PublicApiResponse.text(row, "PROHBT_CONTENT"),
                            PublicApiResponse.text(row, "NOTIFICATION_DATE"),
                            PublicApiResponse.text(row, "MIXTURE_ITEM_SEQ"),
                            PublicApiResponse.text(row, "MIXTURE_ITEM_NAME")));
        }
        return out;
    }

    public record DurKindBatch(
            List<DurWarning> warnings, SourceRef.DataMode origin, Instant retrievedAt) {
        public DurKindBatch {
            warnings = List.copyOf(warnings);
            Objects.requireNonNull(origin);
            Objects.requireNonNull(retrievedAt);
        }
    }

    public record DurBatch(List<DurWarning> warnings, SourceRef.DataMode origin, Instant retrievedAt) {
        public DurBatch {
            warnings = List.copyOf(warnings);
            Objects.requireNonNull(origin);
            Objects.requireNonNull(retrievedAt);
        }
    }
}
