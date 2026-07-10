package com.mermaid.drug;

import com.fasterxml.jackson.databind.JsonNode;
import com.mermaid.common.PublicApiException;
import com.mermaid.common.PublicApiUriBuilder;
import com.mermaid.config.PublicApiProperties;
import java.net.URI;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 식약처 DUR 품목정보 (data.go.kr 15059486, {@code 1471000/DURPrdlstInfoService03}).
 *
 * <p>DUR = Drug Utilization Review: the contraindications the government publishes. Reporting one
 * ("this product carries a published contraindication for children under 12") is <i>information</i>,
 * not medical advice — which is why adding this API lowers the policy risk in spec §7 rather than
 * raising it.
 *
 * <p>Four facts that shape this class:
 *
 * <ul>
 *   <li><b>The JSON parameter is {@code type=json}</b>, not {@code _type=json}. The pharmacy and
 *       HIRA services use the underscore; all three MFDS services do not. One character, and you
 *       silently get XML.
 *   <li><b>There is no pairwise endpoint.</b> You cannot ask "are A and B contraindicated?". You
 *       query by one drug and scan the returned {@code MIXTURE_ITEM_SEQ} values for the other.
 *   <li><b>Age contraindications have no structured age field.</b> "12세 미만" lives inside the
 *       Korean free text of {@code PROHBT_CONTENT}. Machine-readable thresholds are only in the
 *       sibling <i>성분정보</i> service (dataset 15056780), field {@code AGE_BASE}.
 *   <li>{@code INGR_CODE} here is a {@code D######} code. It does <b>not</b> join to 허가정보's
 *       {@code [M######]} codes. Join on {@code ITEM_SEQ}, or on DUR's own {@code MAIN_INGR}.
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DurApiClient {

    /** 병용금기 — the only operation whose response names a second drug. */
    private static final String OP_COMBINATION_TABOO = "getUsjntTabooInfoList03";
    /** 특정연령대금기 */
    private static final String OP_AGE_TABOO = "getSpcifyAgrdeTabooInfoList03";
    /** 임부금기 */
    private static final String OP_PREGNANCY_TABOO = "getPwnmTabooInfoList03";
    /** 노인주의 */
    private static final String OP_ELDERLY_CAUTION = "getOdsnAtentInfoList03";

    private static final int MAX_ROWS = 100;

    private final WebClient publicApiWebClient;
    private final PublicApiProperties properties;

    /**
     * Every DUR warning attached to one product, keyed by {@code itemSeq}.
     *
     * <p>Cached hard: a contraindication notice changes when the ministry issues one, i.e. rarely.
     */
    @Cacheable(value = "durWarnings", key = "#itemSeq")
    public List<DurWarning> warningsFor(String itemSeq) {
        if (!properties.isConfigured()) {
            log.warn("DATA_GO_KR_SERVICE_KEY is not set — returning no DUR warnings");
            return List.of();
        }
        // TODO(team, DEV-303): call each operation and merge.
        //   Start with OP_AGE_TABOO, OP_PREGNANCY_TABOO and OP_ELDERLY_CAUTION — they share a
        //   ~21-field response shape, so one parser handles all three. OP_COMBINATION_TABOO has
        //   its own 40-field shape with the MIXTURE_* block.
        //   Each item carries PROHBT_CONTENT (the reason, in Korean) and NOTIFICATION_DATE.
        log.warn("DurApiClient.warningsFor is not implemented yet — returning no warnings");
        return List.of();
    }

    /**
     * Which of {@code otherItemSeqs} must not be taken alongside {@code itemSeq}.
     *
     * <p>There is no endpoint that takes two drugs, so we fetch this drug's contraindicated partners
     * and intersect. For a basket of N drugs that is N calls, then a set intersection.
     */
    public List<String> contraindicatedWith(String itemSeq, Set<String> otherItemSeqs) {
        // TODO(team, DEV-303): GET OP_COMBINATION_TABOO?itemSeq=…, then collect
        //   response.body.items.item[].MIXTURE_ITEM_SEQ and retain those present in otherItemSeqs.
        return List.of();
    }

    /** Builds a DUR request URI. Left here because the parameter name traps are easy to re-introduce. */
    URI uriFor(String operation, String itemSeq) {
        return PublicApiUriBuilder.of(properties.durBaseUrl(), operation)
                .serviceKey(properties.serviceKey())
                .param("itemSeq", itemSeq)
                .param("numOfRows", MAX_ROWS)
                .param("pageNo", 1)
                .param("type", "json") // NOT `_type`. See the class doc.
                .build();
    }

    /**
     * TODO(team, DEV-303): parse {@code response.body.items.item[]}.
     *
     * <p>Same traps as the pharmacy client: a single result arrives as an object rather than an
     * array, and a bad service key returns HTTP 200 carrying an error envelope. Check {@code
     * response.header.resultCode} — {@code "00"} is success, {@code "01"} an application error,
     * {@code "11"} a missing required parameter — and throw {@link PublicApiException} otherwise.
     */
    private List<DurWarning> parse(JsonNode body, DurWarning.Kind kind) {
        return List.of();
    }

    /**
     * One published contraindication or caution.
     *
     * @param prohibitContent {@code PROHBT_CONTENT} — the government's reason text, in Korean. It
     *     must be translated or summarised before an English-speaking user sees it, and it must not
     *     be paraphrased into a recommendation.
     * @param notificationDate {@code NOTIFICATION_DATE}, YYYYMMDD
     * @param mixtureItemName for {@link Kind#COMBINATION}, the drug that must not be co-administered
     */
    public record DurWarning(
            Kind kind,
            String itemSeq,
            String itemName,
            String prohibitContent,
            String notificationDate,
            String mixtureItemSeq,
            String mixtureItemName) {

        public enum Kind {
            COMBINATION,
            AGE,
            PREGNANCY,
            ELDERLY
        }
    }
}
