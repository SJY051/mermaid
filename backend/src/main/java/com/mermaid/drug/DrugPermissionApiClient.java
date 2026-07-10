package com.mermaid.drug;

import com.fasterxml.jackson.databind.JsonNode;
import com.mermaid.common.FixtureLoader;
import com.mermaid.common.PublicApiException;
import com.mermaid.common.PublicApiResponse;
import com.mermaid.common.PublicApiUriBuilder;
import com.mermaid.config.DataModeProperties;
import com.mermaid.config.PublicApiProperties;
import com.mermaid.drug.domain.PrescriptionStatus;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@RequiredArgsConstructor
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

    /** Products whose name contains {@code itemName}. */
    @Cacheable(value = "permissionByName", key = "#itemName")
    public List<Permitted> findByName(String itemName) {
        return list(Map.of("item_name", itemName), FIXTURE_LIST);
    }

    /**
     * Products containing an ingredient.
     *
     * @param ingredientEn <b>English, capitalised</b>: {@code "Ibuprofen"}, not {@code "ibuprofen"}
     */
    @Cacheable(value = "permissionByIngredient", key = "#ingredientEn")
    public List<Permitted> findByIngredient(String ingredientEn) {
        return list(Map.of("item_ingr_name", ingredientEn), FIXTURE_BY_INGREDIENT);
    }

    /**
     * The only place {@code MAIN_INGR_ENG} exists. One product, by its {@code ITEM_SEQ}.
     *
     * <p>{@code unless} guards the same trap as {@code EasyDrugApiClient#findBySeq}: an empty Optional
     * is unwrapped to null before caching, and null caching is disabled.
     */
    @Cacheable(value = "permissionDetail", key = "#itemSeq", unless = "#result == null")
    public Optional<PermittedDetail> detail(String itemSeq) {
        JsonNode raw = fetch(OP_DETAIL, Map.of("item_seq", itemSeq), FIXTURE_DETAIL);
        return PublicApiResponse.of(raw).requireOk().items().stream()
                .findFirst()
                .map(DrugPermissionApiClient::toDetail);
    }

    private List<Permitted> list(Map<String, Object> params, String fixture) {
        JsonNode raw = fetch(OP_LIST, params, fixture);
        return PublicApiResponse.of(raw).requireOk().items().stream()
                .map(DrugPermissionApiClient::toPermitted)
                .filter(p -> p.itemSeq() != null)
                .toList();
    }

    private JsonNode fetch(String operation, Map<String, Object> params, String fixture) {
        if (dataMode.isFixtureOnly() || !properties.isConfigured()) {
            return fixtures.load(fixture);
        }
        try {
            return publicApiWebClient
                    .get()
                    .uri(uriFor(operation, params))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
        } catch (Exception e) {
            if (dataMode.allowsFallback()) {
                log.warn("허가정보 {} failed, falling back to fixture: {}", operation, e.getMessage());
                return fixtures.load(fixture);
            }
            throw new PublicApiException("허가정보 " + operation + " failed", e);
        }
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
}
