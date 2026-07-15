package com.mermaid.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mermaid.chat.dto.AllergyCheck;
import com.mermaid.chat.dto.MermAidAnswer;
import com.mermaid.common.SourceRef;
import com.mermaid.drug.DrugService;
import com.mermaid.drug.DrugService.RetrievalQuery;
import com.mermaid.drug.DrugService.RetrievedContext;
import com.mermaid.drug.IngredientNormalizer;
import com.mermaid.drug.domain.Drug;
import com.mermaid.drug.domain.DurWarning;
import com.mermaid.profile.domain.MatchConfidence;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
     *     (spec 005 FR-013). The scan sees only what the request carries, so its guarantee is
     *     exactly this: a declaration in the NEWEST turn always clarifies (FR-001), and one in an
     *     earlier turn clarifies while no fully-resolved structured list is present. Once a
     *     complete structured list arrives, it governs by design — that is the loop-closing path —
     *     so a forged earlier-turn declaration alongside one changes nothing, just as a client
     *     that TRIMS the declaration turn is indistinguishable from a conversation in which it
     *     never happened. Both reduce to the same fact: a client can always under-declare its own
     *     requests; that boundary belongs to the first-party send-every-turn obligation, not to
     *     this scan. Server-owned pending-allergy state is out of scope on purpose: transcripts
     *     stay off the server (§2-5).
     * @param exclusions the two structured allergen fields, plus whether the parser had to drop any
     *     entry. Verified terms alone enter the avoided set; unverified names only annotate retrieved
     *     products. By contract the client sends both complete lists, so a list we do not hold in
     *     full authorizes nothing.
     */
    public DrugContext retrieve(
            String userText, String allUserText, MermaidRequestExtension.StructuredExclusions exclusions) {
        Set<String> excludedIngredients = exclusions.terms();
        Set<String> unverifiedAllergens = exclusions.unverifiedTerms();
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
        // A question the client says never got an answer, and that declares an allergy, is the one
        // declaration a structured list cannot be assumed to cover. The clarification is what turns a
        // declaration into a list — so a declaration whose turn FAILED never reached the picker, and
        // the list the client is sending was built for some earlier one. "I am also allergic to
        // aspirin", lost to a network error, would otherwise sit quietly in the history while the
        // request carried exclude_ingredients: ["ibuprofen"], and the gate — seeing a resolved,
        // complete list — would let retrieval through and hand the person an aspirin product marked
        // no_match_found. The client cannot decide this (it does not know what declares an allergy)
        // and the server cannot see it (every question looks answered on the wire), so the client
        // reports the fact and the server makes the judgement.
        boolean unansweredDeclares =
                exclusions.unansweredQuestions().stream().anyMatch(AllergyDeclaration::presentIn);
        boolean allergyDeclared =
                anyTurnDeclares
                        || unansweredDeclares
                        || !excludedIngredients.isEmpty()
                        || !unverifiedAllergens.isEmpty()
                        || exclusions.incomplete();

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
                || unansweredDeclares
                || exclusions.incomplete()
                || !unresolved.isEmpty()
                || (anyTurnDeclares && avoidedKeys.isEmpty() && unverifiedAllergens.isEmpty())) {
            log.info(
                    "Allergy declared (currentTurn={}, unansweredDeclares={}, structuredIncomplete={},"
                            + " resolved={}, unresolved={}) — returning server clarification",
                    currentTurnDeclares,
                    unansweredDeclares,
                    exclusions.incomplete(),
                    avoidedKeys.size(),
                    unresolved.size());
            return DrugContext.allergyClarification();
        }

        long startedAt = System.nanoTime();
        RetrievalQuery extracted = extractor.extract(userText);

        // Under a declared allergy the model does not choose medicines (SA-08): its proposed
        // ingredients are dropped and only products the person named themselves are looked up.
        boolean suppressed = allergyDeclared && !extracted.ingredientsEn().isEmpty();
        if (suppressed) {
            log.info(
                    "drug_terms_suppressed reason=ALLERGY_DECLARED proposed_ingredient_count={}",
                    extracted.ingredientsEn().size());
        }
        RetrievalQuery query = allergyDeclared ? extracted.withoutProposedIngredients() : extracted;

        if (query.isEmpty()) {
            log.debug("No drug search terms in this turn; the model gets an empty context");
            return suppressed ? DrugContext.allergySuppressed() : DrugContext.empty();
        }
        long extractedAt = System.nanoTime();

        RetrievedContext retrieved = drugService.retrieve(query, avoidedKeys);
        List<Drug> checkedDrugs = applyUnverifiedWarnings(retrieved.drugs(), unverifiedAllergens);
        retrieved = new RetrievedContext(checkedDrugs, retrieved.allowedProductNames(), retrieved.sources());
        // Cold, the retrieval is roughly thirty sequential calls to 식약처. Warm, Redis answers.
        // Both numbers belong in the log; the gap between them is the case for parallelising.
        log.info(
                "rag_pass1_complete ingredient_count={} product_name_count={} drug_count={}"
                        + " extract_ms={} retrieve_ms={}",
                query.ingredientsEn().size(),
                query.productNamesKo().size(),
                retrieved.drugs().size(),
                millisBetween(startedAt, extractedAt), millisBetween(extractedAt, System.nanoTime()));

        return new DrugContext(
                render(retrieved, allergyDeclared), groundedDrugs(retrieved.drugs()), retrieved.sources());
    }

    private List<Drug> applyUnverifiedWarnings(List<Drug> drugs, Set<String> unverifiedAllergens) {
        if (unverifiedAllergens.isEmpty()) {
            return drugs;
        }
        return drugs.stream().map(drug -> applyUnverifiedWarning(drug, unverifiedAllergens)).toList();
    }

    private Drug applyUnverifiedWarning(Drug drug, Set<String> unverifiedAllergens) {
        if (drug.allergyCheck().status() == AllergyCheck.Status.BLOCKED) {
            return drug;
        }

        // No ingredient list, so the name check could not run at all. The verified checker already
        // answers UNKNOWN in this case, but only when it has keys to compare — an unverified-only
        // declaration leaves the avoided set empty, so the product arrives carrying NO_MATCH_FOUND.
        // That state says "we looked and found nothing", and here we did not look (§2-2). Someone
        // who has named an allergen must not read "No match found" off a product we cannot read.
        if (drug.ingredientsEn().isEmpty()) {
            return withAllergyCheck(
                    drug,
                    AllergyCheck.unknown(
                            "We could not read this product's ingredients, so we could not check it "
                                    + "against the allergens you named. Ask a pharmacist before taking it."));
        }

        Set<String> matchedIngredients = new LinkedHashSet<>();
        Set<String> matchedNames = new LinkedHashSet<>();
        for (String ingredient : drug.ingredientsEn()) {
            for (String unverified : unverifiedAllergens) {
                String unverifiedKey = normalizer.canonicalize(unverified);
                if (normalizer.compare(ingredient, unverifiedKey) != MatchConfidence.UNKNOWN) {
                    matchedIngredients.add(ingredient);
                    matchedNames.add(unverified);
                }
            }
        }
        if (matchedIngredients.isEmpty()) {
            return drug;
        }

        String nameMatchMessage = "Name match only: "
                + String.join(", ", matchedIngredients)
                + " matched the unverified allergen name "
                + String.join(", ", matchedNames)
                + ". A pharmacist must confirm this match.";

        // A drug can already carry a WARNING from the verified check — a partial or compound match
        // against exclude_ingredients. That warning names a reviewed ingredient the user actually
        // declared; this one names a string they typed. Overwriting the first with the second would
        // silently downgrade the verified finding to a name match, and the user would never hear
        // about the allergen we did resolve. So carry both: verified message first, then the caveat.
        AllergyCheck existing = drug.allergyCheck();
        boolean hasVerifiedWarning = existing.status() == AllergyCheck.Status.WARNING;

        Set<String> allMatched = new LinkedHashSet<>();
        if (hasVerifiedWarning) {
            allMatched.addAll(existing.matchedIngredients());
        }
        allMatched.addAll(matchedIngredients);

        AllergyCheck warning = new AllergyCheck(
                AllergyCheck.Status.WARNING,
                List.copyOf(allMatched),
                hasVerifiedWarning ? existing.message() + " " + nameMatchMessage : nameMatchMessage);
        return withAllergyCheck(drug, warning);
    }

    private Drug withAllergyCheck(Drug drug, AllergyCheck check) {
        return new Drug(
                drug.id(),
                drug.itemSeq(),
                drug.nameKo(),
                drug.nameEn(),
                drug.manufacturerKo(),
                drug.ingredientsEn(),
                drug.mainIngredientKo(),
                drug.prescriptionStatus(),
                drug.narrative(),
                drug.durWarnings(),
                check,
                drug.source());
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
                    drug.nameKo(),
                    new GroundedDrug(
                            drug.source().id(),
                            Set.copyOf(ingredientKeys),
                            drug.allergyCheck(),
                            drug.nameEn(),
                            List.copyOf(drug.ingredientsEn()),
                            drug.narrative() == null ? null : drug.narrative().useMethod(),
                            drug.narrative() == null ? null : drug.narrative().efficacy(),
                            MermAidAnswer.DrugCard.PrescriptionStatus.from(
                                    drug.prescriptionStatus().wire()),
                            cardWarnings(drug),
                            officialCautionKo(drug)));
        }
        if (rejectedCount > 0) {
            log.warn("drug_grounding_failed code=UNNORMALIZABLE_INGREDIENT count={}", rejectedCount);
        }
        return Map.copyOf(grounded);
    }

    /**
     * The card's warnings, finished, in the server's own words (invariant 8).
     *
     * <p>Until 2026-07-14 the model was <i>asked</i> to copy these onto the card, and nothing
     * checked that it had. A model that dropped one dropped a government contraindication; a model
     * that added one put an unsourced medical claim under a footer naming 식약처. Neither is
     * detectable in an answer that is otherwise entirely correct — same product, same ingredients,
     * same source — which is precisely why the copy could not stay the model's job. We hold the
     * record. We render it.
     *
     * <p>병용금기 stays a count and not a list, as it was in the context: it is a property of a
     * <i>pair</i>, 나르펜정400밀리그램 has twenty of them, and the twenty-six-warning card that came out of
     * a live model named Korean medicines the reader has never heard of. Whether any of them applies
     * depends on what else the person takes, which we did not ask. So the fact we can state is how
     * many there are and who to tell; {@code GET /drugs/{id}} still returns every one.
     */
    private static List<String> cardWarnings(Drug drug) {
        List<String> warnings = new ArrayList<>();
        int combinations = 0;
        for (DurWarning warning : drug.durWarnings()) {
            if (warning.kind() == DurWarning.Kind.COMBINATION) {
                combinations++;
            } else {
                warnings.add(warning.describe());
            }
        }
        if (combinations > 0) {
            warnings.add(
                    "식약처 publishes "
                            + combinations
                            + (combinations == 1
                                    ? " medicine that must not be taken with this one."
                                    : " medicines that must not be taken with this one.")
                            + " Tell the pharmacist everything else you are taking — they can check"
                            + " the list against it. Source: MFDS DUR.");
        }
        return List.copyOf(warnings);
    }

    /**
     * Everything the ministry says about using this medicine carefully, joined, in Korean.
     *
     * <p>All four fields, not just 주의사항: a card's {@code labelCautions} summarises the lot, so this
     * is the text its numbers are checked against, and leaving 상호작용 out would make a faithful
     * sentence about an interaction look invented.
     */
    private static String officialCautionKo(Drug drug) {
        Drug.Narrative n = drug.narrative();
        if (n == null) {
            return null;
        }
        String joined =
                Stream.of(n.caution(), n.warning(), n.interaction(), n.sideEffect())
                        .filter(text -> text != null && !text.isBlank())
                        .collect(Collectors.joining("\n"));
        return joined.isBlank() ? null : joined;
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

        // The model no longer copies these onto the card — the server renders them itself, from the
        // same record (see cardWarnings). They stay in the context because the model still has to
        // write a responsible `summary` about a medicine it is being told is contraindicated in
        // pregnancy, and it cannot do that without knowing.
        //
        // 병용금기 remains a count and not a list, here and on the card, for the reason it always was:
        // it is a property of a *pair*, 나르펜정400밀리그램 has twenty, and whether any applies depends on
        // what else the person takes, which we did not ask.
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
                indications, dosages or cautions it does not state. Never describe a medicine as safe.

                Leave each drug card's `warnings` as an empty array and its `prescriptionStatus` as \
                "unknown". The server writes both from the government record, as it does with \
                `source_refs` — a warning is not yours to copy, and copying is not something we can \
                check. `durWarnings` and `combinationContraindicationCount` are given to you so that \
                you know what this medicine is contraindicated for; write nothing about them in the \
                card.
                """
                    .formatted(count);
        }
    }

    /** The narrow server facts needed by invariants 1 and 6; model-authored prose stays separate. */
    /**
     * What the server knows about one retrieved product, and therefore what the model is not allowed
     * to contradict: which source it came from, which ingredients it holds — and what our own allergy
     * check said about it.
     *
     * <p>{@code allergyCheck} is here for the same reason {@code sourceRefId} is (spec 2-9): the model
     * is handed the verdict and asked to carry it, and a model that carries it wrongly must not be
     * able to change what the user sees. It writes {@code no_match_found} over a {@code blocked} and
     * every other check still passes — same product, same ingredients, same source. So the server
     * stamps its own verdict back onto the card in post-processing rather than trusting the copy.
     */
    public record GroundedDrug(
            String sourceRefId,
            Set<String> ingredientKeys,
            AllergyCheck allergyCheck,
            /**
             * 식약처's own 용법용량 for this product, in Korean, exactly as retrieved — and the only
             * dosing text that exists. The model translates it; post-processing checks that every
             * number it wrote is one of these numbers. Null when the ministry gave us no dosing
             * text: then there is nothing to check the model against, and nothing it may say.
             */
            /**
             * The ministry's own display names — {@code Drug.nameEn} and {@code Drug.ingredientsEn},
             * exactly as retrieved. The model copies them; post-processing stamps them back, AFTER
             * validation, so a card can never SHOW a name the ministry did not return while invariant
             * 6 still gets to judge the name the model actually claimed.
             */
            String productNameEn,
            List<String> ingredientNamesEn,
            String officialDosageKo,
            /**
             * 식약처's 효능효과 for this product, in Korean. What the model's {@code indicationSummary}
             * is checked against — because a field the model owns is a field the model can put a DOSE
             * in, and "For: take 8 tablets every 2 hours" renders above the official dose on the card.
             */
            String officialEfficacyKo,
            /**
             * The MFDS licence record's 전문/일반 classification. A server fact with a wire value
             * already, so there is nothing for the model to add and nothing to check — the server
             * writes it onto the card (invariant 8).
             */
            MermAidAnswer.DrugCard.PrescriptionStatus prescriptionStatus,
            /**
             * The card's {@code warnings}, rendered by the server from {@link Drug#durWarnings()} —
             * the finished English strings, not the model's copy of them. Empty means 식약처 has
             * published no DUR contraindication for this product, which the card says in words.
             */
            List<String> warnings,
            /**
             * 식약처's 주의사항·경고·상호작용·부작용 for this product, joined, in Korean. What a card's
             * {@code labelCautions} is checked against, by the same digit rule as the dosing. Null
             * when the ministry gave us none — then a caution on the card has no source at all.
             */
            String officialCautionKo) {
        public GroundedDrug {
            ingredientKeys = Set.copyOf(ingredientKeys);
            warnings = List.copyOf(warnings);
        }
    }
}
