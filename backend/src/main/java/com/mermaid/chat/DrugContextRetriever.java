package com.mermaid.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mermaid.chat.dto.MermAidAnswer;
import com.mermaid.common.SourceRef;
import com.mermaid.drug.DrugService;
import com.mermaid.drug.DrugService.RetrievalQuery;
import com.mermaid.drug.DrugService.RetrievedContext;
import com.mermaid.drug.IngredientNormalizer;
import com.mermaid.drug.domain.Drug;
import com.mermaid.drug.domain.DurWarning;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
public class DrugContextRetriever {

    private final SearchTermExtractor extractor;
    private final DrugService drugService;
    private final IngredientNormalizer normalizer;
    private final ObjectMapper objectMapper;

    @Autowired
    public DrugContextRetriever(
            SearchTermExtractor extractor,
            DrugService drugService,
            IngredientNormalizer normalizer,
            ObjectMapper objectMapper) {
        this.extractor = extractor;
        this.drugService = drugService;
        this.normalizer = normalizer;
        this.objectMapper = objectMapper;
    }

    /**
     * @param userText the newest user turn. Already screened by {@link EmergencyTriage}.
     * @param allUserText every user turn in the request, joined — the same text the triage screens.
     *     The allergy scan must see the whole conversation: the bare reply to our own clarifying
     *     question ("ibuprofen") carries no allergy keyword, and scanning only the newest turn would
     *     let that turn retrieve unguarded and show the person the very ingredient they just declared
     *     (spec 005 FR-013). A client-forged history can only push the outcome toward the
     *     clarification, never unlock retrieval.
     * @param exclusions the structured {@code mermaid.exclude_ingredients} field: the raw terms,
     *     plus whether the parser had to drop any (bounds). By contract the client sends the
     *     COMPLETE list; it is the only channel that may authorize retrieval under a declared
     *     allergy — so a list we do not hold in full authorizes nothing.
     */
    public DrugContext retrieve(
            String userText, String allUserText, MermaidRequestExtension.StructuredExclusions exclusions) {
        Set<String> excludedIngredients = exclusions.terms();
        // The allergy gate runs BEFORE the extractor, like EmergencyTriage runs before the model:
        // a turn that fails closed must not depend on — or pay for — a model call.
        //
        // Free-text allergen extraction has no authority here, on purpose. The 2026-07-13 design
        // (a pass-1 `allergens` field, origin-bound, signed-row-bound) lost a stated allergen four
        // distinct ways — mixed resolution, cap clipping, a laundered clipping signal, shape
        // rejection below the cap — each found in review only after the previous was fixed. The
        // common cause is structural: the server can never verify that an extraction from free text
        // is complete. So a free-text declaration always becomes the server-authored clarifying
        // question, and only the client-complete structured list may proceed (spec 005, 2026-07-14).
        boolean currentTurnDeclares = AllergyDeclaration.presentIn(userText);
        boolean anyTurnDeclares = currentTurnDeclares || AllergyDeclaration.presentIn(allUserText);
        boolean allergyDeclared =
                anyTurnDeclares || !excludedIngredients.isEmpty() || exclusions.incomplete();

        Set<String> avoidedKeys = new HashSet<>();
        List<String> unresolved = new ArrayList<>();
        for (String term : excludedIngredients) {
            IngredientNormalizer.NormalizedTerm normalized = normalizer.normalize(term);
            if (normalizer.isReviewedBinding(normalized)) {
                avoidedKeys.add(normalized.key());
            } else {
                unresolved.add(term);
            }
        }

        // Fail closed to the clarification when:
        //  - this turn declares an allergy in free text (FR-001): the new prose may name an allergen
        //    the structured list does not carry, and we cannot tell, or
        //  - the parser had to drop a structured entry (bounds): the avoided set we computed is not
        //    the user's complete list, and a product containing the dropped allergen could come
        //    back no_match_found — the same completeness principle as FR-001, or
        //  - an allergy is in play (this turn, an earlier turn, or the field) and the structured
        //    list is absent or has any entry no signed row resolves (FR-004/FR-013).
        if (currentTurnDeclares
                || exclusions.incomplete()
                || (allergyDeclared && (avoidedKeys.isEmpty() || !unresolved.isEmpty()))) {
            log.info(
                    "Allergy declared (currentTurn={}, structuredIncomplete={}, resolved={},"
                            + " unresolved={}) — returning server clarification",
                    currentTurnDeclares, exclusions.incomplete(), avoidedKeys.size(), unresolved.size());
            return DrugContext.allergyClarification();
        }

        long startedAt = System.nanoTime();
        RetrievalQuery extracted = extractor.extract(userText);

        // Under a declared allergy the model does not choose medicines (SA-08): its proposed
        // ingredients are dropped and only products the person named themselves are looked up.
        boolean suppressed = allergyDeclared && !extracted.ingredientsEn().isEmpty();
        if (suppressed) {
            log.info("Allergy declared this turn — dropping {} model-proposed ingredient(s): {}",
                    extracted.ingredientsEn().size(), extracted.ingredientsEn());
        }
        RetrievalQuery query = allergyDeclared ? extracted.withoutProposedIngredients() : extracted;

        if (query.isEmpty()) {
            log.debug("No drug search terms in this turn; the model gets an empty context");
            return suppressed ? DrugContext.allergySuppressed() : DrugContext.empty();
        }
        long extractedAt = System.nanoTime();

        RetrievedContext retrieved = drugService.retrieve(query, avoidedKeys);
        // Cold, the retrieval is roughly thirty sequential calls to 식약처. Warm, Redis answers.
        // Both numbers belong in the log; the gap between them is the case for parallelising.
        log.info("RAG pass 1: terms={}/{} → {} drug(s). extract {}ms, retrieve {}ms",
                query.ingredientsEn(), query.productNamesKo(), retrieved.drugs().size(),
                millisBetween(startedAt, extractedAt), millisBetween(extractedAt, System.nanoTime()));

        return new DrugContext(
                render(retrieved, allergyDeclared), groundedDrugs(retrieved.drugs()), retrieved.sources());
    }

    private Map<String, GroundedDrug> groundedDrugs(List<Drug> drugs) {
        Map<String, GroundedDrug> grounded = new LinkedHashMap<>();
        int rejectedCount = 0;
        for (Drug drug : drugs) {
            Set<String> ingredientKeys = new HashSet<>();
            boolean groundable = true;
            for (String ingredient : drug.ingredientsEn()) {
                String key = normalizer.normalizeIdentity(ingredient).key();
                if (key == null) {
                    // An ingredient we cannot normalize means we cannot verify a card's ingredient
                    // claims for this product, so we do not trust it for validation.
                    groundable = false;
                    break;
                }
                ingredientKeys.add(key);
            }
            if (!groundable) {
                rejectedCount++;
                continue;
            }
            // A product with NO English ingredient list is still grounded, with an empty key set:
            // MFDS guidance with AllergyCheck.UNKNOWN is a real retrieved product, and render() still
            // shows it to the model. Dropping it would make INV6 reject a card we actually fetched
            // (INV6_PRODUCT_NOT_RETRIEVED — the #60 P1). INV6 still rejects any card that invents
            // ingredients for it: an empty grounded set never equals a non-empty answer set.
            grounded.put(
                    drug.nameKo(), new GroundedDrug(drug.source().id(), Set.copyOf(ingredientKeys)));
        }
        if (rejectedCount > 0) {
            log.warn("drug_grounding_failed code=UNNORMALIZABLE_INGREDIENT count={}", rejectedCount);
        }
        return Map.copyOf(grounded);
    }

    private static long millisBetween(long fromNanos, long toNanos) {
        return (toNanos - fromNanos) / 1_000_000;
    }

    private String render(RetrievedContext retrieved, boolean allergyDeclared) {
        ArrayNode drugs = objectMapper.createArrayNode();
        for (Drug drug : retrieved.drugs()) {
            drugs.add(toContextNode(drug));
        }
        return DrugContext.preamble(retrieved.drugs().size(), allergyDeclared)
                + "\n"
                + drugs.toPrettyString();
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
     * @param groundedDrugs server-owned source and ingredient identities for every product. Empty
     *     means the answer must name no medicine at all.
     * @param sources server-authored provenance. The model is told to leave {@code source_refs} empty;
     *     {@link ChatProxyController} fills it from here.
     */
    public record DrugContext(
            String systemMessage,
            Map<String, GroundedDrug> groundedDrugs,
            List<SourceRef> sources,
            Optional<MermAidAnswer> directAnswer) {

        public DrugContext {
            groundedDrugs = Map.copyOf(groundedDrugs);
            sources = List.copyOf(sources);
        }

        public DrugContext(
                String systemMessage, Map<String, GroundedDrug> groundedDrugs, List<SourceRef> sources) {
            this(systemMessage, groundedDrugs, sources, Optional.empty());
        }

        public Set<String> allowedProductNames() {
            return groundedDrugs.keySet();
        }

        static DrugContext empty() {
            return new DrugContext(preamble(0, false) + "\n[]", Map.of(), List.of());
        }

        static DrugContext allergyClarification() {
            return new DrugContext("", Map.of(), List.of(), Optional.of(AllergyClarification.answer()));
        }

        /**
         * Nothing retrieved because we took the choice of medicine away from the model.
         *
         * <p>Distinct from {@link #empty()} on purpose: "we found nothing" and "we refused to look"
         * are different answers, and the person deserves to be told which one they got.
         */
        static DrugContext allergySuppressed() {
            return new DrugContext(preamble(0, true) + "\n[]", Map.of(), List.of());
        }

        /**
         * @param allergyDeclared the person told us about an allergy this turn, so the model may not
         *     propose a medicine — not even one it believes to be unrelated. We match allergens by
         *     ingredient name and hold no drug-class knowledge, so "unrelated" is not ours to say.
         */
        static String preamble(int count, boolean allergyDeclared) {
            if (count == 0) {
                return allergyDeclared
                        ? """
                        DRUG_CONTEXT: nothing was retrieved for this turn.

                        The person told you about an allergy. We compare allergens by exact ingredient \
                        name and we do not check whether a medicine is related to one someone reacts \
                        to, so we did not look for an alternative and you must not name one. Set \
                        `drugs` to an empty array and name no medicine at all, in any field. Say \
                        plainly that you cannot suggest an alternative for someone with an allergy \
                        and that a pharmacist can. Recommend visiting a pharmacy.
                        """
                        : """
                        DRUG_CONTEXT: nothing was retrieved for this turn.
                        You must set `drugs` to an empty array and name no medicine at all, in any field. \
                        Recommend visiting a pharmacy, and ask a clarifying question if that would help.
                        """;
            }
            return grounded(count) + (allergyDeclared ? NO_ALTERNATIVES : "");
        }

        /**
         * Appended when the person is allergic and named a product themselves. The allowlist already
         * stops the model naming a different medicine; this stops it gesturing at one in prose.
         */
        private static final String NO_ALTERNATIVES =
                """

                The person told you about an allergy. Do not suggest an alternative medicine, \
                ingredient or drug family — we did not check whether anything is related to what they \
                react to. Tell them a pharmacist can advise on alternatives.
                """;

        private static String grounded(int count) {
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

    /** The narrow server facts needed by invariants 1 and 6; model-authored prose stays separate. */
    public record GroundedDrug(String sourceRefId, Set<String> ingredientKeys) {
        public GroundedDrug {
            ingredientKeys = Set.copyOf(ingredientKeys);
        }
    }
}
