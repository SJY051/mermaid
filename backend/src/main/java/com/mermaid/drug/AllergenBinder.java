package com.mermaid.drug;

import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * SCAFFOLD (DEV-560, spec 005). Binds free-text allergen names to canonical ingredient keys so an
 * allergy declared in prose actually reaches the allergy comparison — the gap {@code EX-02} left
 * open (see {@code docs/specs/005-medical-profile/spec.md}).
 *
 * <p>Two rules make this safe and are the whole point of the class:
 * <ul>
 *   <li><b>Origin binding (FR-001, the EX-01 principle).</b> A candidate name counts only if it
 *       occurs literally (case-insensitively) in the user's own text. A model-proposed allergen the
 *       user never typed must not inject — or clear — an allergy.
 *   <li><b>Signed rows only (FR-002).</b> Binding uses {@link IngredientNormalizer}, and only
 *       {@code EXACT} or a human-reviewed {@code SYNONYM} key may go into the avoided set that can
 *       {@code block}. An unreviewed guess contributes {@code warning} at most, never a block.
 * </ul>
 *
 * <p>The result also tells the caller whether <i>anything</i> resolved. That drives FR-004: a
 * declared allergy that binds to nothing must become a clarifying question, never a silent
 * {@code no_match_found}.
 */
@Component
@RequiredArgsConstructor
public class AllergenBinder {

    private final IngredientNormalizer normalizer;

    /**
     * @param candidateAllergens allergen names the pass-1 extractor proposed (FR-001 field)
     * @param userText the user's own turn, for the origin check
     * @return the avoided keys that may block, plus which candidates did not resolve
     */
    public BoundAllergens bind(List<String> candidateAllergens, String userText) {
        // TODO(DEV-560): implement per FR-001/FR-002/FR-006.
        //  1. keep only candidates that occur (case-insensitively) in userText  [origin binding]
        //  2. normalizer.normalize(name) each survivor; keep keys whose confidence is EXACT or
        //     SYNONYM in avoidedKeys; anything else goes to unresolved (feeds the clarifying question)
        //  3. never let an UNKNOWN/PARTIAL key into avoidedKeys — that is the block/warning boundary
        // Behaviour-neutral stub until implemented: resolves nothing.
        return BoundAllergens.NONE;
    }

    /**
     * The outcome of binding. {@code avoidedKeys} feed {@link AllergyChecker}; {@code anyResolved}
     * and {@code unresolved} drive the FR-004 fail-closed clarifying question.
     */
    public record BoundAllergens(Set<String> avoidedKeys, boolean anyResolved, List<String> unresolved) {
        public static final BoundAllergens NONE = new BoundAllergens(Set.of(), false, List.of());
    }
}
