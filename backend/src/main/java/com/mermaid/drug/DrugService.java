package com.mermaid.drug;

import com.mermaid.common.NotFoundException;
import com.mermaid.common.SourceRef;
import com.mermaid.config.DataModeProperties;
import com.mermaid.drug.domain.Drug;
import com.mermaid.drug.domain.DurWarning;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
@RequiredArgsConstructor
public class DrugService {

    private static final String PROVIDER = "mfds"; // 식품의약품안전처

    private final DrugPermissionApiClient permissionClient;
    private final EasyDrugApiClient easyDrugClient;
    private final DurApiClient durClient;
    private final AllergyChecker allergyChecker;
    private final IngredientNormalizer normalizer;
    private final DataModeProperties dataMode;
    private final Clock clock;

    /**
     * Search by product name. Ingredients and the allergy verdict come back with every row; the
     * narrative and DUR warnings do not (that would be one extra call per result).
     *
     * @param avoidedKeys normalised ingredient keys the user must avoid; empty when they gave none
     */
    public List<Drug> searchByName(String itemName, Set<String> avoidedKeys) {
        return permissionClient.findByName(itemName).stream()
                .filter(DrugPermissionApiClient.Permitted::licenceCurrent)
                .map(p -> summary(p, avoidedKeys))
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
        return permissionClient.findByIngredient(term).stream()
                .filter(DrugPermissionApiClient.Permitted::licenceCurrent)
                .map(p -> summary(p, avoidedKeys))
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
        DrugPermissionApiClient.PermittedDetail permitted =
                permissionClient
                        .detail(itemSeq)
                        .orElseThrow(() -> new NotFoundException("No drug with ITEM_SEQ " + itemSeq));

        Optional<EasyDrugApiClient.Narrated> narrated = easyDrugClient.findBySeq(itemSeq);
        List<DurWarning> warnings = durClient.warningsFor(itemSeq);

        return new Drug(
                Drug.idOf(itemSeq),
                itemSeq,
                permitted.nameKo(),
                null, // the detail op publishes no English product name
                permitted.manufacturerKo(),
                permitted.ingredientsEn(),
                permitted.mainIngredientKo(),
                permitted.prescriptionStatus(),
                narrated.map(EasyDrugApiClient.Narrated::narrative).orElse(Drug.Narrative.EMPTY),
                warnings,
                allergyChecker.check(permitted.ingredientsEn(), avoidedKeys),
                source(itemSeq));
    }

    /**
     * Drug context for pass 2 of the RAG flow, and the set of names {@code AnswerValidator} will
     * allow the model to mention.
     */
    public RetrievedContext retrieve(String query, Set<String> avoidedKeys) {
        List<Drug> candidates = searchByName(query, avoidedKeys);
        Set<String> allowedNames =
                candidates.stream().map(Drug::nameKo).collect(java.util.stream.Collectors.toSet());
        return new RetrievedContext(candidates, allowedNames);
    }

    private Drug summary(DrugPermissionApiClient.Permitted p, Set<String> avoidedKeys) {
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
                source(p.itemSeq()));
    }

    private SourceRef source(String itemSeq) {
        return new SourceRef(
                "src:" + PROVIDER + ":" + itemSeq,
                "식품의약품안전처 의약품 제품 허가정보",
                itemSeq,
                Instant.now(clock),
                dataMode.isFixtureOnly() ? SourceRef.DataMode.FIXTURE : SourceRef.DataMode.LIVE,
                "MFDS — drug product licence information");
    }

    /**
     * @param drugs what we actually retrieved
     * @param allowedProductNames the only Korean product names the model may name. Anything else is
     *     an invention and {@code AnswerValidator} rejects it (invariant 6).
     */
    public record RetrievedContext(List<Drug> drugs, Set<String> allowedProductNames) {}
}
