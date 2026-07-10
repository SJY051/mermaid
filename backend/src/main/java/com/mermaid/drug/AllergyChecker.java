package com.mermaid.drug;

import com.mermaid.chat.dto.AllergyCheck;
import com.mermaid.profile.domain.MatchConfidence;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Decides the four allergy states (FR-04, spec §2-12).
 *
 * <p>The whole design is one asymmetry: <b>blocking is expensive to get wrong in one direction and
 * fatal in the other.</b> Telling someone a safe medicine is off-limits costs them a worse night.
 * Telling someone an unsafe medicine is fine can kill them. So:
 *
 * <ul>
 *   <li>Only {@link MatchConfidence#EXACT} or a human-reviewed {@link MatchConfidence#SYNONYM} may
 *       produce {@link AllergyCheck.Status#BLOCKED}.
 *   <li>A partial or compound match produces {@link AllergyCheck.Status#WARNING}.
 *   <li>If we hold no ingredient list at all, the answer is {@link AllergyCheck.Status#UNKNOWN} — not
 *       "no match".
 *   <li>{@link AllergyCheck.Status#NO_MATCH_FOUND} means exactly what it says, and the message never
 *       contains the word "safe".
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class AllergyChecker {

    private final IngredientNormalizer normalizer;

    /**
     * @param drugIngredientsEn the product's ingredients, English, from 허가정보
     * @param avoidedKeys the user's normalised ingredient keys — {@link
     *     IngredientNormalizer#normalize} output, not raw text
     */
    public AllergyCheck check(List<String> drugIngredientsEn, Set<String> avoidedKeys) {
        if (avoidedKeys == null || avoidedKeys.isEmpty()) {
            // Nothing to avoid. Not a verdict about the drug at all.
            return AllergyCheck.noMatch();
        }
        if (drugIngredientsEn == null || drugIngredientsEn.isEmpty()) {
            return AllergyCheck.unknown(
                    "We could not read this product's ingredients, so we could not check it against "
                            + "what you avoid. Ask a pharmacist before taking it.");
        }

        Set<String> blocking = new LinkedHashSet<>();
        Set<String> warning = new LinkedHashSet<>();

        for (String ingredient : drugIngredientsEn) {
            for (String avoided : avoidedKeys) {
                MatchConfidence confidence = normalizer.compare(ingredient, avoided);
                if (confidence.canBlock()) {
                    blocking.add(ingredient);
                } else if (confidence == MatchConfidence.PARTIAL) {
                    warning.add(ingredient);
                }
            }
        }

        if (!blocking.isEmpty()) {
            return new AllergyCheck(
                    AllergyCheck.Status.BLOCKED,
                    List.copyOf(blocking),
                    "Contains " + String.join(", ", blocking) + ", which you asked to avoid.");
        }
        if (!warning.isEmpty()) {
            return new AllergyCheck(
                    AllergyCheck.Status.WARNING,
                    List.copyOf(warning),
                    "This product contains "
                            + String.join(", ", warning)
                            + ", which may be related to an ingredient you avoid. Confirm with a pharmacist.");
        }
        return AllergyCheck.noMatch();
    }

    /**
     * Products a user must avoid, given one ingredient.
     *
     * <p>Used the other way round from {@link #check}: the assistant asks "what can I take instead?"
     * and we need to exclude, not annotate.
     */
    public boolean isBlocked(List<String> drugIngredientsEn, Set<String> avoidedKeys) {
        return check(drugIngredientsEn, avoidedKeys).status() == AllergyCheck.Status.BLOCKED;
    }
}
