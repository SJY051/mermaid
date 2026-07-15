package com.mermaid.drug;

import com.mermaid.chat.dto.AllergyCheck;
import com.mermaid.common.ApiException;
import com.mermaid.common.FixtureIntegrityException;
import com.mermaid.common.NotFoundException;
import com.mermaid.common.Parallel;
import com.mermaid.common.PublicApiException;
import com.mermaid.common.SourceRef;
import com.mermaid.config.DataModeProperties;
import com.mermaid.drug.domain.Drug;
import com.mermaid.drug.domain.PrescriptionStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Assembles one medicine from three government services, joined on {@code ITEM_SEQ} (spec §2-10).
 *
 * <pre>
 *   허가정보 (DrugPermissionApiClient) → 성분(영문), 전문/일반   ← the allergy check runs on this
 *   e약은요  (EasyDrugApiClient)       → 효능·복용법·주의사항
 *   DUR     (DurApiClient)            → 병용·연령·임부 금기, 노인주의
 * </pre>
 *
 * <p>{@code ITEM_SEQ} really is the same value across all three; verified against live responses
 * (product {@code 202005623} = 어린이타이레놀산). The permission API is the spine of the join, because
 * it alone knows the ingredients — the original plan of filtering allergies with e약은요 could never
 * have worked (spec §2-8).
 *
 * <p>This service is also <b>pass 1 of the two-pass RAG</b> (spec §2-2). The chat flow calls it to
 * build DRUG_CONTEXT, then hands the model only what came back. {@code AnswerValidator} later rejects
 * any product name the model invented on top of it.
 */
@Slf4j
@Service
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class DrugService {

    private static final String PROVIDER = "mfds"; // 식품의약품안전처

    /** How many drugs the model is shown. Three cards is what a chat answer can carry honestly. */
    private static final int MAX_CONTEXT_DRUGS = 3;

    /** How deep we walk the ranked list looking for three with published guidance. */
    private static final int MAX_DETAIL_PROBES = 8;

    /**
     * How many calls to 식약처 may be in flight at once from one chat turn.
     *
     * <p>Four, and raising it will not help. Measured 2026-07-10: four DUR calls take 5.77s in
     * sequence and 2.70s together — a 2.1× speed-up, not 4×. The ministry throttles concurrent
     * requests per service key, so past a handful of sockets we would only be queueing on their side
     * while looking busy on ours. Four is where the curve flattens.
     */
    private static final int UPSTREAM_CONCURRENCY = 4;

    /**
     * The upstream returns products in no useful order, so we impose one.
     *
     * <p>Over-the-counter first — a traveller cannot buy 전문의약품 without seeing a doctor. Then the
     * ones whose ingredients raise no allergy question. Then the simplest formulation: a
     * single-ingredient acetaminophen tablet answers "I have a headache" better than a six-ingredient
     * cold syrup that happens to contain acetaminophen, and it carries fewer allergens the user never
     * asked about. Name last, so the ordering is total and the tests are deterministic.
     */
    private static final Comparator<Drug> BY_USEFULNESS =
            Comparator.comparingInt((Drug d) -> d.prescriptionStatus() == PrescriptionStatus.OTC ? 0 : 1)
                    .thenComparingInt(d -> d.allergyCheck().status() == AllergyCheck.Status.WARNING ? 1 : 0)
                    .thenComparingInt(d -> d.ingredientsEn().size())
                    .thenComparing(Drug::nameKo);

    private final DrugPermissionApiClient permissionClient;
    private final EasyDrugApiClient easyDrugClient;
    private final DurApiClient durClient;
    private final AllergyChecker allergyChecker;
    private final IngredientNormalizer normalizer;

    public DrugService(
            DrugPermissionApiClient permissionClient,
            EasyDrugApiClient easyDrugClient,
            DurApiClient durClient,
            AllergyChecker allergyChecker,
            IngredientNormalizer normalizer,
            DataModeProperties ignoredDataMode,
            Clock ignoredClock) {
        this(permissionClient, easyDrugClient, durClient, allergyChecker, normalizer);
    }

    /**
     * Search by product name. Ingredients and the allergy verdict come back with every row; the
     * narrative and DUR warnings do not (that would be one extra call per result).
     *
     * @param avoidedKeys normalised ingredient keys the user must avoid; empty when they gave none
     */
    public List<Drug> searchByName(String itemName, Set<String> avoidedKeys) {
        DrugPermissionApiClient.PermissionBatch batch = permissionClient.findByNameBatch(itemName);
        return batch.rows().stream()
                .filter(DrugPermissionApiClient.Permitted::licenceCurrent)
                .map(p -> summary(p, avoidedKeys, batch.origin(), batch.retrievedAt()))
                .toList();
    }

    /**
     * Search by ingredient.
     *
     * <p>The caller may type anything — "ibuprofen", "Ibuprofen 200mg", "paracetamol". We normalise
     * it to the form the upstream service understands before asking.
     *
     * <p>That matters more than it sounds. 허가정보's ingredient filter is a <b>case-sensitive
     * substring</b> match: {@code Ibuprofen} finds 282 products, {@code ibuprofen} finds 142 — all of
     * them <i>Dex</i>ibuprofen, and no plain ibuprofen at all. Passing a user's own capitalisation
     * through would answer an allergy question with the wrong medicines.
     */
    public List<Drug> searchByIngredient(String ingredient, Set<String> avoidedKeys) {
        String term = normalizer.toSearchTerm(ingredient);
        if (term.isEmpty()) {
            return List.of();
        }
        DrugPermissionApiClient.PermissionBatch batch = permissionClient.findByIngredientBatch(term);
        return batch.rows().stream()
                .filter(DrugPermissionApiClient.Permitted::licenceCurrent)
                .map(p -> summary(p, avoidedKeys, batch.origin(), batch.retrievedAt()))
                .toList();
    }

    /**
     * Everything we know about one product: ingredients, guidance text, and every DUR warning the
     * ministry has published for it.
     *
     * <p>Four upstream calls for the warnings alone, all cached. A product with no contraindications
     * — most of them — comes back with an empty list, which is a fact and not an error.
     */
    public Drug detail(String itemSeq, Set<String> avoidedKeys) {
        // Three services, one key, and no dependency between them. Fetched together they cost one
        // round-trip instead of three, and the DUR call is itself four more, also concurrent.
        var fetched =
                Mono.zip(
                                Parallel.async(() -> permissionClient.detailBatch(itemSeq)),
                                Parallel.async(() -> easyDrugClient.findBySeqBatch(itemSeq)),
                                Parallel.async(() -> durClient.warningsForBatch(itemSeq)))
                        .block();
        if (fetched == null) {
            throw new NotFoundException("No drug with ITEM_SEQ " + itemSeq);
        }

        DrugPermissionApiClient.PermissionDetailBatch permission = fetched.getT1();
        DrugPermissionApiClient.PermittedDetail permitted = permission.row();
        if (permitted == null) {
            throw new NotFoundException("No drug with ITEM_SEQ " + itemSeq);
        }
        EasyDrugApiClient.NarratedDetailBatch easy = fetched.getT2();
        DurApiClient.DurBatch dur = fetched.getT3();
        Optional<EasyDrugApiClient.Narrated> narrated = Optional.ofNullable(easy.row());
        String actualItemSeq = permitted.itemSeq();
        SourceRef.DataMode origin =
                permission.origin() == SourceRef.DataMode.FIXTURE
                                || easy.origin() == SourceRef.DataMode.FIXTURE
                                || dur.origin() == SourceRef.DataMode.FIXTURE
                        ? SourceRef.DataMode.FIXTURE
                        : SourceRef.DataMode.LIVE;
        Instant retrievedAt =
                List.of(permission.retrievedAt(), easy.retrievedAt(), dur.retrievedAt()).stream()
                        .min(Instant::compareTo)
                        .orElseThrow();

        return new Drug(
                Drug.idOf(actualItemSeq),
                actualItemSeq,
                permitted.nameKo(),
                null, // the detail op publishes no English product name
                permitted.manufacturerKo(),
                permitted.ingredientsEn(),
                permitted.mainIngredientKo(),
                permitted.prescriptionStatus(),
                narrated.map(EasyDrugApiClient.Narrated::narrative).orElse(Drug.Narrative.EMPTY),
                dur.warnings(),
                allergyChecker.check(permitted.ingredientsEn(), avoidedKeys),
                source(actualItemSeq, origin, retrievedAt));
    }

    /**
     * Pass 1 of the RAG flow: everything the model is allowed to talk about this turn.
     *
     * <p>The query is <b>not</b> the user's sentence. 허가정보 matches product names as a substring, so
     * {@code item_name="I have a headache"} returns {@code totalCount: 0} — verified against the live
     * API. Something has to turn prose into search terms first; that is {@code SearchTermExtractor},
     * and what it produces arrives here.
     *
     * <p>Three filters, each of which changes what a sick person is shown:
     *
     * <ol>
     *   <li><b>Export-only products are dropped.</b> A name containing {@code 수출용} cannot be bought in
     *       a Korean pharmacy. Searching {@code Acetaminophen} returns four of them on page one.
     *       {@code 수출명} is a different thing — 게보린정(수출명:돌로린정) is sold here — and is kept.
     *   <li><b>A product with no e약은요 entry is dropped.</b> Not a heuristic: our whole design is that
     *       the model may only summarise official text, so a product with no official text cannot be
     *       described at all. Empirically these are the same products as (1), plus a few others.
     *   <li><b>Ingredient hits the user is allergic to are dropped;</b> products they named by hand are
     *       kept, marked. Asking "is 부루펜 safe for me?" must get an answer. Being offered 부루펜 for a
     *       headache you asked about while allergic to ibuprofen must not happen.
     * </ol>
     *
     * <p>Ranking matters because the upstream order is arbitrary. {@code Acetaminophen} returns
     * 판콜에이내복액 — a six-ingredient cold syrup — first, and a plain acetaminophen tablet at rank 13.
     */
    public RetrievedContext retrieve(RetrievalQuery query, Set<String> avoidedKeys) {
        Set<String> namedSeqs = new LinkedHashSet<>();
        List<Drug> candidates = new ArrayList<>();

        // What the user named by hand outranks anything we inferred: they asked about it.
        for (List<Drug> hits :
                Parallel.map(query.productNamesKo(), UPSTREAM_CONCURRENCY, n -> searchByName(n, avoidedKeys))) {
            for (Drug d : hits) {
                namedSeqs.add(d.itemSeq());
                candidates.add(d);
            }
        }

        List<List<Drug>> perIngredient =
                Parallel.map(query.ingredientsEn(), UPSTREAM_CONCURRENCY, ingredient ->
                        searchByIngredient(ingredient, avoidedKeys).stream()
                                .filter(d -> d.allergyCheck().status() != AllergyCheck.Status.BLOCKED)
                                .filter(d -> !isExportOnly(d.nameKo()))
                                .sorted(BY_USEFULNESS)
                                .toList());
        candidates.addAll(roundRobin(perIngredient));

        List<Drug> merged =
                candidates.stream()
                        .filter(d -> !isExportOnly(d.nameKo()))
                        .filter(distinctBy(Drug::itemSeq))
                        .limit(MAX_DETAIL_PROBES)
                        .toList();

        List<Drug> chosen = new ArrayList<>();
        // A batch at a time, so the common case — the three best candidates all have guidance —
        // costs one round of probes and one round of assembly, and we never fetch the tail we do
        // not need. Everything inside a batch runs at once.
        for (int from = 0; from < merged.size() && chosen.size() < MAX_CONTEXT_DRUGS; from += MAX_CONTEXT_DRUGS) {
            List<Drug> batch = merged.subList(from, Math.min(from + MAX_CONTEXT_DRUGS, merged.size()));

            // Cheap probe before the six-call detail assembly. Cached, so detail() re-reads it free.
            List<Boolean> hasGuidance =
                    Parallel.map(
                            batch,
                            UPSTREAM_CONCURRENCY,
                            d -> easyDrugClient.findBySeqBatch(d.itemSeq()).row() != null);

            List<Drug> groundable = new ArrayList<>();
            for (int i = 0; i < batch.size(); i++) {
                if (hasGuidance.get(i)) {
                    groundable.add(batch.get(i));
                } else {
                    log.debug("drug_context_skipped reason=GUIDANCE_UNAVAILABLE");
                }
            }

            Parallel.map(groundable, UPSTREAM_CONCURRENCY, d ->
                            detail(d.itemSeq(), avoidedKeys, namedSeqs.contains(d.itemSeq())))
                    .forEach(assembled -> assembled.ifPresent(chosen::add));
        }

        return RetrievedContext.of(chosen.size() <= MAX_CONTEXT_DRUGS
                ? chosen
                : chosen.subList(0, MAX_CONTEXT_DRUGS));
    }

    /**
     * The list operation reports {@code ITEM_INGR_NAME}; the detail operation reports {@code
     * MAIN_INGR_ENG}. They are different fields and can disagree, so the allergy verdict is taken
     * again on the authoritative one. A product that only now turns out to be blocked is dropped —
     * unless the user named it themselves, in which case they are shown it, marked blocked.
     */
    private Optional<Drug> detail(String itemSeq, Set<String> avoidedKeys, boolean userNamedIt) {
        try {
            Drug full = detail(itemSeq, avoidedKeys);
            if (!userNamedIt && full.allergyCheck().status() == AllergyCheck.Status.BLOCKED) {
                log.info("drug_context_dropped reason=DETAIL_ALLERGY_BLOCKED");
                return Optional.empty();
            }
            return Optional.of(full);
        } catch (FixtureIntegrityException e) {
            throw e;
        } catch (RuntimeException e) {
            if (e instanceof PublicApiException || e instanceof ApiException) {
                throw e;
            }
            log.warn("drug_context_skipped reason=ASSEMBLY_FAILED");
            return Optional.empty();
        }
    }

    /**
     * One drug from each ingredient, in the order the ingredients were proposed, then the next from
     * each, and so on.
     *
     * <p>Ranking the ingredients' results as one pool looks equivalent and is not. Every tie-break
     * eventually falls through to the product name, and Korean product names beginning with 나 sort
     * ahead of ones beginning with 삼. Asked for "headache and fever" with {@code [Acetaminophen,
     * Ibuprofen, Naproxen]} proposed in that order, a single pooled sort really did return 나르펜정
     * (ibuprofen), 나로펜정 and 나프록신정 (both naproxen) — three NSAIDs, two of them contraindicated in
     * pregnancy, and not one of the 1,357 acetaminophen products. The alphabet had quietly overruled
     * the ingredient the model put first.
     *
     * <p>Round-robin also means an allergy that rules out one ingredient does not empty the answer.
     */
    private static List<Drug> roundRobin(List<List<Drug>> perIngredient) {
        List<Drug> interleaved = new ArrayList<>();
        int deepest = perIngredient.stream().mapToInt(List::size).max().orElse(0);
        for (int rank = 0; rank < deepest; rank++) {
            for (List<Drug> hits : perIngredient) {
                if (rank < hits.size()) {
                    interleaved.add(hits.get(rank));
                }
            }
        }
        return interleaved;
    }

    /** {@code 수출용} means "for export". {@code 수출명} means "also sold abroad under this name". */
    static boolean isExportOnly(String nameKo) {
        return nameKo != null && nameKo.contains("수출용");
    }

    private static <T> Predicate<T> distinctBy(Function<T, ?> key) {
        Set<Object> seen = ConcurrentHashMap.newKeySet();
        return t -> seen.add(key.apply(t));
    }

    /**
     * What the user asked about, and the only names the model may say.
     *
     * <p>No allergen field, on purpose. One carried the model's allergen candidates from 2026-07-13
     * to 2026-07-14, until review showed the server can never verify such an extraction is complete
     * (four distinct loss paths, each found only after the previous was fixed). Allergens now reach
     * the server only through the client-structured {@code mermaid.exclude_ingredients} field; a
     * free-text declaration fails closed to a clarifying question (spec 005, decision 2026-07-14).
     */
    public record RetrievalQuery(List<String> ingredientsEn, List<String> productNamesKo) {

        public static final RetrievalQuery EMPTY = new RetrievalQuery(List.of(), List.of());

        public boolean isEmpty() {
            return ingredientsEn.isEmpty() && productNamesKo.isEmpty();
        }

        /**
         * The same query without the ingredients the model proposed, keeping the product names the
         * person typed themselves.
         *
         * <p>The two halves have different authors. A product name is theirs — "is 부루펜 safe for
         * me?" deserves an answer, even a blocked one. An ingredient is the model's suggestion of
         * what would help, and choosing a medicine for someone who has just declared an allergy is a
         * clinical act. See {@code AllergyDeclaration}, which decides when to call this.
         */
        public RetrievalQuery withoutProposedIngredients() {
            return new RetrievalQuery(List.of(), productNamesKo);
        }
    }

    private Drug summary(
            DrugPermissionApiClient.Permitted p,
            Set<String> avoidedKeys,
            SourceRef.DataMode origin,
            Instant retrievedAt) {
        return new Drug(
                Drug.idOf(p.itemSeq()),
                p.itemSeq(),
                p.nameKo(),
                p.nameEn(),
                p.manufacturerKo(),
                p.ingredientsEn(),
                null,
                p.prescriptionStatus(),
                Drug.Narrative.EMPTY, // a search result carries no guidance text
                List.of(), // nor DUR warnings; ask for the detail
                allergyChecker.check(p.ingredientsEn(), avoidedKeys),
                source(p.itemSeq(), origin, retrievedAt));
    }

    private SourceRef source(String itemSeq, SourceRef.DataMode origin, Instant retrievedAt) {
        return new SourceRef(
                "src:" + PROVIDER + ":" + itemSeq,
                "식품의약품안전처 의약품 제품 허가정보",
                itemSeq,
                retrievedAt,
                origin,
                "MFDS — drug product licence information");
    }

    /**
     * @param drugs what we actually retrieved, fully assembled
     * @param allowedProductNames the only Korean product names the model may name. Anything else is
     *     an invention and {@code AnswerValidator} rejects it (invariant 6).
     * @param sources the provenance of those drugs. <b>The server authors these, not the model</b> —
     *     it is the only party that knows whether a record came from the live API or a fixture.
     */
    public record RetrievedContext(
            List<Drug> drugs, Set<String> allowedProductNames, List<SourceRef> sources) {

        public static final RetrievedContext EMPTY = new RetrievedContext(List.of(), Set.of(), List.of());

        static RetrievedContext of(List<Drug> drugs) {
            if (drugs.isEmpty()) {
                return EMPTY;
            }

            // Identity is owned by the detail record, not by the list-row id that led us to it.
            // Fixture mode deliberately ignores query parameters, so several list probes can resolve
            // to the same captured detail record.
            List<Drug> uniqueDrugs = drugs.stream().filter(distinctBy(Drug::itemSeq)).toList();
            return new RetrievedContext(
                    List.copyOf(uniqueDrugs),
                    uniqueDrugs.stream().map(Drug::nameKo).collect(Collectors.toUnmodifiableSet()),
                    uniqueDrugs.stream().map(Drug::source).toList());
        }

        public boolean isEmpty() {
            return drugs.isEmpty();
        }
    }
}
