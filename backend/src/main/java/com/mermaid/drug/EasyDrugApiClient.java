package com.mermaid.drug;

import com.fasterxml.jackson.databind.JsonNode;
import com.mermaid.common.FixtureLoader;
import com.mermaid.common.PublicApiException;
import com.mermaid.common.PublicApiResponse;
import com.mermaid.common.PublicApiUriBuilder;
import com.mermaid.config.DataModeProperties;
import com.mermaid.config.PublicApiProperties;
import com.mermaid.drug.domain.Drug;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@RequiredArgsConstructor
public class EasyDrugApiClient {

    private static final String OP = "getDrbEasyDrugList";
    private static final String FIXTURE = "easydrug.json";
    private static final int MAX_ROWS = 10;

    private final WebClient publicApiWebClient;
    private final PublicApiProperties properties;
    private final DataModeProperties dataMode;
    private final FixtureLoader fixtures;

    /** Partial name match: {@code "타이레놀"} finds seven products. */
    @Cacheable(value = "easyDrugByName", key = "#itemName")
    public List<Narrated> findByName(String itemName) {
        return call(param("itemName", itemName));
    }

    /**
     * Exact product lookup, for the detail view and for the ITEM_SEQ join.
     *
     * <p>{@code unless} matters: Spring unwraps an {@code Optional} return value before caching, so an
     * {@code Optional.empty()} arrives at Redis as a null and {@code disableCachingNullValues()}
     * throws. Not every licensed product has consumer guidance — export-only drugs have none — and a
     * missing narrative is a fact, not a failure.
     */
    @Cacheable(value = "easyDrugBySeq", key = "#itemSeq", unless = "#result == null")
    public Optional<Narrated> findBySeq(String itemSeq) {
        return call(param("itemSeq", itemSeq)).stream().findFirst();
    }

    private java.util.Map<String, Object> param(String k, String v) {
        return java.util.Map.of(k, v);
    }

    private List<Narrated> call(java.util.Map<String, Object> params) {
        if (dataMode.isFixtureOnly() || !properties.isConfigured()) {
            return parse(fixtures.load(FIXTURE));
        }
        try {
            JsonNode raw =
                    publicApiWebClient.get().uri(uriFor(params)).retrieve().bodyToMono(JsonNode.class).block();
            return parse(raw);
        } catch (Exception e) {
            if (dataMode.allowsFallback()) {
                log.warn("e약은요 lookup failed, falling back to fixture: {}", e.getMessage());
                return parse(fixtures.load(FIXTURE));
            }
            throw new PublicApiException("e약은요 lookup failed", e);
        }
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
                .filter(n -> n.itemSeq() != null)
                .toList();
    }

    /** A product's Korean guidance text, keyed by the id that joins to the other two APIs. */
    public record Narrated(String itemSeq, String nameKo, String manufacturerKo, Drug.Narrative narrative) {}
}
