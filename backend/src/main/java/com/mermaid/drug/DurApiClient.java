package com.mermaid.drug;

import com.fasterxml.jackson.databind.JsonNode;
import com.mermaid.common.FixtureLoader;
import com.mermaid.common.PublicApiException;
import com.mermaid.common.PublicApiResponse;
import com.mermaid.common.PublicApiUriBuilder;
import com.mermaid.config.DataModeProperties;
import com.mermaid.config.PublicApiProperties;
import com.mermaid.drug.domain.DurWarning;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@RequiredArgsConstructor
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

    private final WebClient publicApiWebClient;
    private final PublicApiProperties properties;
    private final DataModeProperties dataMode;
    private final FixtureLoader fixtures;

    /**
     * Every published warning for one product, across all four kinds.
     *
     * <p>Four upstream calls. Cached hard: a contraindication notice changes when the ministry issues
     * one, which is to say rarely.
     */
    @Cacheable(value = "durWarnings", key = "#itemSeq")
    public List<DurWarning> warningsFor(String itemSeq) {
        List<DurWarning> all = new ArrayList<>();
        for (DurWarning.Kind kind : DurWarning.Kind.values()) {
            all.addAll(byKind(itemSeq, kind));
        }
        return all;
    }

    public List<DurWarning> byKind(String itemSeq, DurWarning.Kind kind) {
        JsonNode raw = fetch(OPERATIONS.get(kind), Map.of("itemSeq", itemSeq), FIXTURES.get(kind));
        return parse(raw, kind);
    }

    /**
     * Which of {@code otherItemSeqs} must not be taken alongside {@code itemSeq}.
     *
     * <p>There is no endpoint that accepts two drugs, so we fetch this drug's contraindicated
     * partners and intersect. For a basket of N medicines that is N calls and a set intersection.
     */
    public Set<String> contraindicatedWith(String itemSeq, Set<String> otherItemSeqs) {
        Set<String> hits = new LinkedHashSet<>();
        for (DurWarning w : byKind(itemSeq, DurWarning.Kind.COMBINATION)) {
            if (w.pairedItemSeq() != null && otherItemSeqs.contains(w.pairedItemSeq())) {
                hits.add(w.pairedItemSeq());
            }
        }
        return hits;
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
                log.warn("DUR {} failed, falling back to fixture: {}", operation, e.getMessage());
                return fixtures.load(fixture);
            }
            throw new PublicApiException("DUR " + operation + " failed", e);
        }
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
            if (itemSeq == null) {
                continue;
            }
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
}
