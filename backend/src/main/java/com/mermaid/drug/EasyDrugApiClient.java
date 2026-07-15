package com.mermaid.drug;

import com.fasterxml.jackson.databind.JsonNode;
import com.mermaid.common.ApiException;
import com.mermaid.common.ErrorCode;
import com.mermaid.common.FixtureIntegrityException;
import com.mermaid.common.FixtureLoader;
import com.mermaid.common.PublicApiException;
import com.mermaid.common.PublicApiResponse;
import com.mermaid.common.PublicApiUriBuilder;
import com.mermaid.common.SourceRef;
import com.mermaid.config.DataModeProperties;
import com.mermaid.config.PublicApiProperties;
import com.mermaid.drug.domain.Drug;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 식약처 의약품개요정보 e약은요 (data.go.kr 15075057).
 *
 * <p>The consumer-facing guidance: what a medicine is for, how to take it, what to watch out for.
 * Every field is Korean prose ending in {@code Qesitm}.
 *
 * <p><b>It carries no ingredient data at all.</b> Not a missing field — the concept is absent. That
 * is why {@link DrugPermissionApiClient} exists, and why the original 요구사항 명세서's plan to filter
 * allergies with this API alone could never have worked (spec §2-8).
 *
 * <p>The JSON parameter here is {@code type=json}, not {@code _type=json}. One underscore and you get
 * XML back with no error.
 */
@Slf4j
@Component
public class EasyDrugApiClient {

    private static final String OP = "getDrbEasyDrugList";
    private static final String FIXTURE = "easydrug.json";
    private static final int MAX_ROWS = 10;

    private final WebClient publicApiWebClient;
    private final PublicApiProperties properties;
    private final DataModeProperties dataMode;
    private final FixtureLoader fixtures;
    private final Clock clock;

    @Autowired
    public EasyDrugApiClient(
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

    EasyDrugApiClient(
            WebClient publicApiWebClient,
            PublicApiProperties properties,
            DataModeProperties dataMode,
            FixtureLoader fixtures) {
        this(publicApiWebClient, properties, dataMode, fixtures, Clock.systemUTC());
    }

    /** Partial name match: {@code "타이레놀"} finds seven products. */
    public List<Narrated> findByName(String itemName) {
        return findByNameBatch(itemName).rows();
    }

    @Cacheable(value = "easyDrugByNameV2", key = "#root.target.cacheRoute() + '|name=' + #itemName")
    public NarratedBatch findByNameBatch(String itemName) {
        return list(
                param("itemName", itemName),
                row ->
                        row.itemSeq() != null
                                && row.nameKo() != null
                                && row.nameKo().contains(itemName),
                "easy_name");
    }

    /**
     * Exact product lookup, for the detail view and for the ITEM_SEQ join.
     *
     * <p>The cached batch itself is never null. A missing narrative is represented by a null {@code
     * row} inside the batch, so Redis can preserve the fetch origin and time for that fact too.
     */
    public Optional<Narrated> findBySeq(String itemSeq) {
        return Optional.ofNullable(findBySeqBatch(itemSeq).row());
    }

    @Cacheable(value = "easyDrugBySeqV2", key = "#root.target.cacheRoute() + '|seq=' + #itemSeq")
    public NarratedDetailBatch findBySeqBatch(String itemSeq) {
        if (dataMode.isFixtureOnly()) {
            return fixtureDetail(itemSeq, false);
        }
        requireConfigured("easy_seq");

        List<Narrated> rows;
        try {
            rows = parse(fetchLive(param("itemSeq", itemSeq)));
        } catch (RuntimeException failure) {
            if (dataMode.allowsFallback()) {
                return fixtureDetail(itemSeq, true);
            }
            throw unavailable("easy_seq", "UPSTREAM_FAILURE");
        }
        if (rows.stream().anyMatch(row -> !itemSeq.equals(row.itemSeq()))) {
            throw payloadInvalid("easy_seq");
        }
        return new NarratedDetailBatch(rows.stream().findFirst().orElse(null), SourceRef.DataMode.LIVE, now());
    }

    private java.util.Map<String, Object> param(String k, String v) {
        return java.util.Map.of(k, v);
    }

    private NarratedBatch list(
            java.util.Map<String, Object> params, Predicate<Narrated> binding, String operationCode) {
        if (dataMode.isFixtureOnly()) {
            return new NarratedBatch(parse(fixtures.load(FIXTURE)), SourceRef.DataMode.FIXTURE, now());
        }
        requireConfigured(operationCode);

        List<Narrated> rows;
        try {
            rows = parse(fetchLive(params));
        } catch (RuntimeException failure) {
            if (dataMode.allowsFallback()) {
                return fixtureList(binding, operationCode);
            }
            throw unavailable(operationCode, "UPSTREAM_FAILURE");
        }
        if (rows.stream().anyMatch(binding.negate())) {
            throw payloadInvalid(operationCode);
        }
        return new NarratedBatch(rows, SourceRef.DataMode.LIVE, now());
    }

    private NarratedBatch fixtureList(Predicate<Narrated> binding, String operationCode) {
        List<Narrated> rows = loadFixture(operationCode);
        if (rows.isEmpty() || rows.stream().anyMatch(binding.negate())) {
            throw unavailable(operationCode, "FIXTURE_UNBOUND");
        }
        log.warn("drug_api_fallback operation={} reason=UPSTREAM_FAILURE", operationCode);
        return new NarratedBatch(rows, SourceRef.DataMode.FIXTURE, now());
    }

    private NarratedDetailBatch fixtureDetail(String itemSeq, boolean requireBinding) {
        List<Narrated> rows = loadFixture("easy_seq");
        if (requireBinding
                && (rows.isEmpty() || rows.stream().anyMatch(row -> !itemSeq.equals(row.itemSeq())))) {
            throw unavailable("easy_seq", "FIXTURE_UNBOUND");
        }
        if (requireBinding) {
            log.warn("drug_api_fallback operation=easy_seq reason=UPSTREAM_FAILURE");
        }
        return new NarratedDetailBatch(
                rows.stream().findFirst().orElse(null), SourceRef.DataMode.FIXTURE, now());
    }

    private List<Narrated> loadFixture(String operationCode) {
        try {
            return parse(fixtures.load(FIXTURE));
        } catch (FixtureIntegrityException fixtureFailure) {
            throw fixtureFailure;
        } catch (RuntimeException fixtureFailure) {
            throw unavailable(operationCode, "FIXTURE_UNAVAILABLE");
        }
    }

    private JsonNode fetchLive(java.util.Map<String, Object> params) {
        return publicApiWebClient.get().uri(uriFor(params)).retrieve().bodyToMono(JsonNode.class).block();
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

    URI uriFor(java.util.Map<String, Object> params) {
        PublicApiUriBuilder b =
                PublicApiUriBuilder.of(properties.easyDrugBaseUrl(), OP)
                        .serviceKey(properties.serviceKey())
                        .param("numOfRows", MAX_ROWS)
                        .param("pageNo", 1)
                        .param("type", "json"); // NOT `_type`. See the class doc.
        params.forEach(b::param);
        return b.build();
    }

    private List<Narrated> parse(JsonNode raw) {
        return PublicApiResponse.of(raw).requireOk().items().stream()
                .map(
                        row ->
                                new Narrated(
                                        PublicApiResponse.text(row, "itemSeq"),
                                        PublicApiResponse.text(row, "itemName"),
                                        PublicApiResponse.text(row, "entpName"),
                                        new Drug.Narrative(
                                                PublicApiResponse.text(row, "efcyQesitm"),
                                                PublicApiResponse.text(row, "useMethodQesitm"),
                                                PublicApiResponse.text(row, "atpnQesitm"),
                                                PublicApiResponse.text(row, "atpnWarnQesitm"),
                                                PublicApiResponse.text(row, "intrcQesitm"),
                                                PublicApiResponse.text(row, "seQesitm"),
                                                PublicApiResponse.text(row, "depositMethodQesitm"))))
                .toList();
    }

    /** A product's Korean guidance text, keyed by the id that joins to the other two APIs. */
    public record Narrated(String itemSeq, String nameKo, String manufacturerKo, Drug.Narrative narrative) {}

    public record NarratedBatch(List<Narrated> rows, SourceRef.DataMode origin, Instant retrievedAt) {
        public NarratedBatch {
            rows = List.copyOf(rows);
            Objects.requireNonNull(origin);
            Objects.requireNonNull(retrievedAt);
        }
    }

    public record NarratedDetailBatch(Narrated row, SourceRef.DataMode origin, Instant retrievedAt) {
        public NarratedDetailBatch {
            Objects.requireNonNull(origin);
            Objects.requireNonNull(retrievedAt);
        }
    }
}
