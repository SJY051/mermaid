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
import com.mermaid.drug.domain.PrescriptionStatus;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 식약처 의약품 제품 허가정보 (data.go.kr 15095677) — the only source of machine-readable ingredients.
 *
 * <p>Four facts, each verified against the live API on 2026-07-10 and each contradicting something
 * written about this service elsewhere:
 *
 * <ol>
 *   <li><b>Ingredient search is a case-sensitive SUBSTRING match</b>, which is worse than a
 *       case-sensitive exact one. {@code Ibuprofen} returns 282 products. {@code ibuprofen} returns
 *       142 — every one of them <i>Dex</i>ibuprofen, and no plain ibuprofen at all. {@code
 *       acetaminophen} returns zero, {@code Acetaminophen} returns 1,357. Always go through {@link
 *       IngredientNormalizer#toSearchTerm}.
 *   <li><b>The list operation has no {@code item_seq} parameter.</b> Passing one is silently ignored
 *       and you get all 43,064 products, starting with something unrelated.
 *   <li><b>The detail operation is {@code getDrugPrdtPrmsnDtlInq06}</b> — note the {@code 06} on a
 *       service named {@code …07}. {@code …DtlInq07} answers 404.
 *   <li>{@code MAIN_INGR_ENG} lives only on the detail response. The list gives {@code
 *       ITEM_INGR_NAME}, also English, slash-separated for compound products — and sometimes with the
 *       same ingredient twice.
 * </ol>
 */
@Slf4j
@Component
public class DrugPermissionApiClient {

    private static final String OP_LIST = "getDrugPrdtPrmsnInq07";
    private static final String OP_DETAIL = "getDrugPrdtPrmsnDtlInq06";

    private static final String FIXTURE_LIST = "permission.json";
    private static final String FIXTURE_DETAIL = "permission_detail.json";
    private static final String FIXTURE_BY_INGREDIENT = "permission_ibuprofen.json";

    private static final int MAX_ROWS = 20;

    private final WebClient publicApiWebClient;
    private final PublicApiProperties properties;
    private final DataModeProperties dataMode;
    private final FixtureLoader fixtures;
    private final Clock clock;

    @Autowired
    public DrugPermissionApiClient(
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

    DrugPermissionApiClient(
            WebClient publicApiWebClient,
            PublicApiProperties properties,
            DataModeProperties dataMode,
            FixtureLoader fixtures) {
        this(publicApiWebClient, properties, dataMode, fixtures, Clock.systemUTC());
    }

    /** Products whose name contains {@code itemName}. */
    public List<Permitted> findByName(String itemName) {
        return findByNameBatch(itemName).rows();
    }

    @Cacheable(value = "permissionByNameV2", key = "#root.target.cacheRoute() + '|name=' + #itemName")
    public PermissionBatch findByNameBatch(String itemName) {
        return list(
                Map.of("item_name", itemName),
                FIXTURE_LIST,
                permitted ->
                        permitted.itemSeq() != null
                                && permitted.nameKo() != null
                                && permitted.nameKo().contains(itemName),
                "permission_name");
    }

    /**
     * Products containing an ingredient.
     *
     * @param ingredientEn <b>English, capitalised</b>: {@code "Ibuprofen"}, not {@code "ibuprofen"}
     */
    public List<Permitted> findByIngredient(String ingredientEn) {
        return findByIngredientBatch(ingredientEn).rows();
    }

    @Cacheable(
            value = "permissionByIngredientV2",
            key = "#root.target.cacheRoute() + '|ingredient=' + #ingredientEn")
    public PermissionBatch findByIngredientBatch(String ingredientEn) {
        return list(
                Map.of("item_ingr_name", ingredientEn),
                FIXTURE_BY_INGREDIENT,
                permitted ->
                        permitted.itemSeq() != null
                                && permitted.ingredientsEn().stream()
                                        .anyMatch(value -> value.contains(ingredientEn)),
                "permission_ingredient");
    }

    /**
     * The only place {@code MAIN_INGR_ENG} exists. One product, by its {@code ITEM_SEQ}.
     *
     * <p>The cached batch itself is never null. A missing product is represented by a null {@code
     * row} inside the batch, so Redis can preserve the fetch origin and time for that result too.
     */
    public Optional<PermittedDetail> detail(String itemSeq) {
        return Optional.ofNullable(detailBatch(itemSeq).row());
    }

    @Cacheable(value = "permissionDetailV2", key = "#root.target.cacheRoute() + '|seq=' + #itemSeq")
    public PermissionDetailBatch detailBatch(String itemSeq) {
        if (dataMode.isFixtureOnly()) {
            return fixtureDetail(itemSeq, false);
        }
        requireConfigured("permission_detail");

        List<PermittedDetail> rows;
        try {
            rows = parseDetails(fetchLive(OP_DETAIL, Map.of("item_seq", itemSeq)));
        } catch (RuntimeException failure) {
            if (dataMode.allowsFallback()) {
                return fixtureDetail(itemSeq, true);
            }
            throw unavailable("permission_detail", "UPSTREAM_FAILURE");
        }
        if (rows.stream().anyMatch(row -> !itemSeq.equals(row.itemSeq()))) {
            throw payloadInvalid("permission_detail");
        }
        return new PermissionDetailBatch(rows.stream().findFirst().orElse(null), SourceRef.DataMode.LIVE, now());
    }

    private PermissionBatch list(
            Map<String, Object> params,
            String fixture,
            Predicate<Permitted> binding,
            String operationCode) {
        if (dataMode.isFixtureOnly()) {
            return new PermissionBatch(parseList(fixtures.load(fixture)), SourceRef.DataMode.FIXTURE, now());
        }
        requireConfigured(operationCode);

        List<Permitted> rows;
        try {
            rows = parseList(fetchLive(OP_LIST, params));
        } catch (RuntimeException failure) {
            if (dataMode.allowsFallback()) {
                return fixtureList(fixture, binding, operationCode);
            }
            throw unavailable(operationCode, "UPSTREAM_FAILURE");
        }
        if (rows.stream().anyMatch(binding.negate())) {
            throw payloadInvalid(operationCode);
        }
        return new PermissionBatch(rows, SourceRef.DataMode.LIVE, now());
    }

    private PermissionBatch fixtureList(
            String fixture, Predicate<Permitted> binding, String operationCode) {
        List<Permitted> rows;
        try {
            rows = parseList(fixtures.load(fixture));
        } catch (FixtureIntegrityException fixtureFailure) {
            throw fixtureFailure;
        } catch (RuntimeException fixtureFailure) {
            throw unavailable(operationCode, "FIXTURE_UNAVAILABLE");
        }
        if (rows.isEmpty() || rows.stream().anyMatch(binding.negate())) {
            throw unavailable(operationCode, "FIXTURE_UNBOUND");
        }
        log.warn("drug_api_fallback operation={} reason=UPSTREAM_FAILURE", operationCode);
        return new PermissionBatch(rows, SourceRef.DataMode.FIXTURE, now());
    }

    private PermissionDetailBatch fixtureDetail(String itemSeq, boolean requireBinding) {
        List<PermittedDetail> rows;
        try {
            rows = parseDetails(fixtures.load(FIXTURE_DETAIL));
        } catch (FixtureIntegrityException fixtureFailure) {
            throw fixtureFailure;
        } catch (RuntimeException fixtureFailure) {
            throw unavailable("permission_detail", "FIXTURE_UNAVAILABLE");
        }
        if (requireBinding
                && (rows.isEmpty() || rows.stream().anyMatch(row -> !itemSeq.equals(row.itemSeq())))) {
            throw unavailable("permission_detail", "FIXTURE_UNBOUND");
        }
        if (requireBinding) {
            log.warn("drug_api_fallback operation=permission_detail reason=UPSTREAM_FAILURE");
        }
        return new PermissionDetailBatch(
                rows.stream().findFirst().orElse(null), SourceRef.DataMode.FIXTURE, now());
    }

    private static List<Permitted> parseList(JsonNode raw) {
        return PublicApiResponse.of(raw).requireOk().items().stream()
                .map(DrugPermissionApiClient::toPermitted)
                .toList();
    }

    private static List<PermittedDetail> parseDetails(JsonNode raw) {
        return PublicApiResponse.of(raw).requireOk().items().stream()
                .map(DrugPermissionApiClient::toDetail)
                .toList();
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
                PublicApiUriBuilder.of(properties.drugPermissionBaseUrl(), operation)
                        .serviceKey(properties.serviceKey())
                        .param("numOfRows", MAX_ROWS)
                        .param("pageNo", 1)
                        .param("type", "json");
        params.forEach(b::param);
        return b.build();
    }

    private static Permitted toPermitted(JsonNode row) {
        return new Permitted(
                PublicApiResponse.text(row, "ITEM_SEQ"),
                PublicApiResponse.text(row, "ITEM_NAME"),
                PublicApiResponse.text(row, "ITEM_ENG_NAME"),
                PublicApiResponse.text(row, "ENTP_NAME"),
                splitIngredients(PublicApiResponse.text(row, "ITEM_INGR_NAME")),
                PrescriptionStatus.fromKorean(PublicApiResponse.text(row, "SPCLTY_PBLC")),
                // "정상" means the licence is current; anything else means it was cancelled or suspended.
                "정상".equals(PublicApiResponse.text(row, "CANCEL_NAME")));
    }

    private static PermittedDetail toDetail(JsonNode row) {
        return new PermittedDetail(
                PublicApiResponse.text(row, "ITEM_SEQ"),
                PublicApiResponse.text(row, "ITEM_NAME"),
                PublicApiResponse.text(row, "ENTP_NAME"),
                splitIngredients(PublicApiResponse.text(row, "MAIN_INGR_ENG")),
                stripIngredientCode(PublicApiResponse.text(row, "MAIN_ITEM_INGR")),
                PrescriptionStatus.fromKorean(PublicApiResponse.text(row, "ETC_OTC_CODE")));
    }

    /**
     * {@code "Glucose/Sodium Chloride"} → two ingredients. A single one has no slash.
     *
     * <p>Deduplicated, because the API repeats itself: 타이레놀8시간이알서방정 is a bilayer tablet and its
     * {@code ITEM_INGR_NAME} is literally {@code "Acetaminophen/Acetaminophen"} with {@code
     * ITEM_INGR_CNT = 2}. Two layers, one allergen. Showing it twice helps nobody.
     */
    static List<String> splitIngredients(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split("/"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    /**
     * {@code "[M279100]아세트아미노펜과립"} → {@code "아세트아미노펜과립"}.
     *
     * <p>The bracketed M-code is 식약처's 주성분코드. It is the same code space as DUR's {@code MAIN_INGR},
     * and a <i>different</i> one from DUR's {@code INGR_CODE} (which is a {@code D######}). Do not
     * join on the latter.
     */
    static String stripIngredientCode(String raw) {
        if (raw == null) {
            return null;
        }
        int close = raw.indexOf(']');
        return (raw.startsWith("[M") && close > 0) ? raw.substring(close + 1).trim() : raw.trim();
    }

    /** A row from the list operation. {@code ingredientsEn} comes from {@code ITEM_INGR_NAME}. */
    public record Permitted(
            String itemSeq,
            String nameKo,
            String nameEn,
            String manufacturerKo,
            List<String> ingredientsEn,
            PrescriptionStatus prescriptionStatus,
            boolean licenceCurrent) {}

    /** A row from the detail operation. {@code ingredientsEn} comes from {@code MAIN_INGR_ENG}. */
    public record PermittedDetail(
            String itemSeq,
            String nameKo,
            String manufacturerKo,
            List<String> ingredientsEn,
            String mainIngredientKo,
            PrescriptionStatus prescriptionStatus) {}

    public record PermissionBatch(List<Permitted> rows, SourceRef.DataMode origin, Instant retrievedAt) {
        public PermissionBatch {
            rows = List.copyOf(rows);
            Objects.requireNonNull(origin);
            Objects.requireNonNull(retrievedAt);
        }
    }

    public record PermissionDetailBatch(
            PermittedDetail row, SourceRef.DataMode origin, Instant retrievedAt) {
        public PermissionDetailBatch {
            Objects.requireNonNull(origin);
            Objects.requireNonNull(retrievedAt);
        }
    }
}
