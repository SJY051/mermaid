package com.mermaid.chat;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Did this turn tell us the person has an allergy? A text rule, not a clinical judgement.
 *
 * <p>It runs <b>before</b> the model is allowed to propose a medicine, which is the same shape as
 * {@link EmergencyTriage} and for the same reason: a model that gets this wrong gets it wrong
 * silently (SA-03).
 *
 * <p><b>Why it exists.</b> {@link SearchTermExtractor} asks a model for ingredients "commonly
 * available over the counter for the symptoms described". Told that someone reacts to ibuprofen, the
 * model proposes naproxen. Both are NSAIDs, and reacting to one predicts reacting to the other.
 * {@link com.mermaid.drug.AllergyChecker} then compares ingredient <i>names</i> and reports {@code
 * no_match_found}, which is honest and is not protection. Post-processing invariant 6 checks that a
 * named product exists in what we retrieved — it cannot check that offering it was a good idea.
 *
 * <p>Deciding that one drug may stand in for another is clinical knowledge. Neither a developer nor a
 * model may invent it (spec §2-12). So when this returns {@code true} the model's proposals are
 * dropped and we offer nothing. That is a narrowing of scope, not a medical opinion — which is
 * exactly why it needs no reviewer, and why a drug-class table would.
 *
 * <p><b>Over-eager on purpose.</b> "My allergies are acting up" fires it, and that person is told to
 * see a pharmacist rather than shown an antihistamine. A false positive costs one worse answer. A
 * false negative offers an allergic person a drug from the class they react to.
 *
 * <p><b>It is not complete, and cannot be.</b> "Ibuprofen gives me hives" declares an allergy without
 * naming one and does not fire. But we never learn of that allergy at all, so a reviewer-signed class
 * table would not have caught it either: this rule is not the weaker half of a better mechanism.
 *
 * <p>TODO(team, DEV-405): English only, like {@link EmergencyTriage}. Korean phrasings (알레르기, 알러지)
 * belong here, and should be reviewed alongside that class's red flags rather than bolted on alone.
 */
final class AllergyDeclaration {

    private static final List<Pattern> PATTERNS =
            List.of(
                    Pattern.compile("(?i)\\ball?erg(?:y|ies|ic)\\b"),
                    Pattern.compile("(?i)\\banaphyla(?:xis|ctic)\\b"),
                    Pattern.compile("(?i)\\bintoleran(?:t|ce)\\b"));

    private AllergyDeclaration() {}

    /**
     * @param userText user text exactly as written — one turn, or every user turn in the request
     *     joined. The caller scans the whole conversation (spec 005 FR-013): the bare reply to our
     *     own clarifying question carries no allergy keyword, and screening only the newest turn
     *     would let it retrieve unguarded.
     */
    static boolean presentIn(String userText) {
        if (userText == null || userText.isBlank()) {
            return false;
        }
        return PATTERNS.stream().anyMatch(p -> p.matcher(userText).find());
    }
}
