package com.mermaid.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mermaid.common.SourceRef;
import com.mermaid.drug.DrugService;
import com.mermaid.drug.DrugService.RetrievalQuery;
import com.mermaid.drug.DrugService.RetrievedContext;
import com.mermaid.drug.IngredientNormalizer;
import com.mermaid.drug.domain.Drug;
import com.mermaid.drug.domain.DurWarning;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Pass 1 of the two-pass RAG flow, end to end (spec §2-2).
 *
 * <pre>
 *   user's words → SearchTermExtractor → DrugService.retrieve → DRUG_CONTEXT system message
 *                                                             → allowedProductNames  (invariant 6)
 *                                                             → sourceRefs           (invariant 1, 3, 5)
 * </pre>
 *
 * <p>The context is injected as a <b>system</b> message, not a user one, so that a user turn cannot
 * impersonate retrieved data. It carries the ministry's own Korean text verbatim and untruncated: a
 * 주의사항 cut in half is a contraindication the model never sees.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DrugContextRetriever {

    private final SearchTermExtractor extractor;
    private final DrugService drugService;
    private final IngredientNormalizer normalizer;
    private final ObjectMapper objectMapper;

    /**
     * @param userText the newest user turn. Already screened by {@link EmergencyTriage}.
     * @param excludedIngredients raw ingredient strings the user says they must avoid
     */
    public DrugContext retrieve(String userText, Set<String> excludedIngredients) {
        long startedAt = System.nanoTime();
        RetrievalQuery query = extractor.extract(userText);
        if (query.isEmpty()) {
            log.debug("No drug search terms in this turn; the model gets an empty context");
            return DrugContext.empty();
        }
        long extractedAt = System.nanoTime();

        RetrievedContext retrieved = drugService.retrieve(query, normalizeAvoided(excludedIngredients));
        // Cold, the retrieval is roughly thirty sequential calls to 식약처. Warm, Redis answers.
        // Both numbers belong in the log; the gap between them is the case for parallelising.
        log.info("RAG pass 1: terms={}/{} → {} drug(s). extract {}ms, retrieve {}ms",
                query.ingredientsEn(), query.productNamesKo(), retrieved.drugs().size(),
                millisBetween(startedAt, extractedAt), millisBetween(extractedAt, System.nanoTime()));

        return new DrugContext(
                render(retrieved), retrieved.allowedProductNames(), retrieved.sources());
    }

    private static long millisBetween(long fromNanos, long toNanos) {
        return (toNanos - fromNanos) / 1_000_000;
    }

    private Set<String> normalizeAvoided(Set<String> raw) {
        Set<String> keys = new HashSet<>();
        for (String term : raw) {
            Optional.ofNullable(normalizer.normalize(term).key()).ifPresent(keys::add);
        }
        return keys;
    }

    private String render(RetrievedContext retrieved) {
        ArrayNode drugs = objectMapper.createArrayNode();
        for (Drug drug : retrieved.drugs()) {
            drugs.add(toContextNode(drug));
        }
        return DrugContext.preamble(retrieved.drugs().size()) + "\n" + drugs.toPrettyString();
    }

    private ObjectNode toContextNode(Drug drug) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("productNameKo", drug.nameKo());
        node.put("sourceRefId", drug.source().id());
        node.put("manufacturerKo", drug.manufacturerKo());
        node.put("prescriptionStatus", drug.prescriptionStatus().wire());

        ArrayNode ingredients = node.putArray("ingredientsEn");
        drug.ingredientsEn().forEach(ingredients::add);

        // The ministry's own words, in Korean, untouched. The model translates and summarises them;
        // it does not get to add to them.
        ObjectNode official = node.putObject("officialTextKo");
        Drug.Narrative n = drug.narrative();
        putIfPresent(official, "efficacy", n.efficacy());
        putIfPresent(official, "useMethod", n.useMethod());
        putIfPresent(official, "caution", n.caution());
        putIfPresent(official, "warning", n.warning());
        putIfPresent(official, "interaction", n.interaction());
        putIfPresent(official, "sideEffect", n.sideEffect());

        // Age, pregnancy and elderly warnings are properties of this medicine, and go in whole.
        //
        // 병용금기 is not: it is a property of a *pair*. 나르펜정400밀리그램 has twenty of them, and a model
        // told to copy them dutifully produced a card with twenty-six warnings naming Korean medicines
        // the reader has never heard of. Whether any applies depends on what else they take, which we
        // did not ask. So the model gets the count and an instruction, and `GET /drugs/{id}` still
        // returns every one of them for anyone who wants the list.
        ArrayNode dur = node.putArray("durWarnings");
        int combinations = 0;
        for (DurWarning w : drug.durWarnings()) {
            if (w.kind() == DurWarning.Kind.COMBINATION) {
                combinations++;
            } else {
                dur.add(w.describe());
            }
        }
        if (combinations > 0) {
            node.put("combinationContraindicationCount", combinations);
        }

        ObjectNode allergy = node.putObject("allergyCheck");
        allergy.put("status", drug.allergyCheck().status().wire());
        ArrayNode matched = allergy.putArray("matchedIngredients");
        drug.allergyCheck().matchedIngredients().forEach(matched::add);
        allergy.put("message", drug.allergyCheck().message());

        return node;
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }

    /**
     * @param systemMessage injected verbatim after the system prompt
     * @param allowedProductNames the hallucination gate's allowlist. Empty means the answer must name
     *     no medicine at all.
     * @param sources server-authored provenance. The model is told to leave {@code source_refs} empty;
     *     {@link ChatProxyController} fills it from here.
     */
    public record DrugContext(
            String systemMessage, Set<String> allowedProductNames, List<SourceRef> sources) {

        static DrugContext empty() {
            return new DrugContext(preamble(0) + "\n[]", Set.of(), List.of());
        }

        static String preamble(int count) {
            if (count == 0) {
                return """
                    DRUG_CONTEXT: nothing was retrieved for this turn.
                    You must set `drugs` to an empty array and name no medicine at all, in any field. \
                    Recommend visiting a pharmacy, and ask a clarifying question if that would help.
                    """;
            }
            return """
                DRUG_CONTEXT: %d product(s), retrieved just now from 식품의약품안전처 open data.

                These are the ONLY medicines you may name, anywhere in your answer. Use `productNameKo` \
                exactly as written. The server rejects any other product name.

                For each medicine you describe, put its `sourceRefId` in that drug card's \
                `source_ref_id`, and cite the same id in `source_ref_ids` for any guidance you draw \
                from `officialTextKo`. Leave the top-level `source_refs` as an empty array — the server \
                fills it in.

                `officialTextKo` is the ministry's own wording. Translate and summarise it. Do not add \
                indications, dosages or warnings it does not state. Copy every entry of `durWarnings` \
                into that drug's `warnings`. Never describe a medicine as safe.

                Where `combinationContraindicationCount` is present, add exactly one warning saying \
                that this many combination contraindications are published for the medicine and that \
                the reader must tell a pharmacist what else they are taking. Do not invent the list — \
                we did not ask what they take, so we do not know which apply.
                """
                    .formatted(count);
        }
    }
}
