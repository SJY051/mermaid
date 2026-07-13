package com.mermaid.chat;

/**
 * SCAFFOLD (DEV-560, spec 005). Server-authored clarifying question for a declared allergy whose
 * allergen did not resolve to a signed ingredient (FR-004, FR-010).
 *
 * <p>Why server-authored, like the emergency code path: the question is safety-critical. If a
 * prompt-injected model could suppress or reword it, the fail-closed guarantee would be the model's
 * to break. So the server owns the text and the decision to show it; the model never authors this.
 *
 * <p>The FR-004 rule this serves: a declared allergy that binds to nothing must become this
 * question, never a silent {@code no_match_found}. The model's own drug suggestions stay suppressed
 * meanwhile (SA-08).
 */
public final class AllergyClarification {

    /** Fixed, server-owned copy. English, because the reader is an English speaker in Korea. */
    public static final String QUESTION =
            "I want to check that safely. Which ingredient are you allergic to? "
                    + "Please type its name (for example, \"ibuprofen\").";

    private AllergyClarification() {}

    /**
     * Builds the answer shown when a declared allergy did not resolve.
     *
     * <p>TODO(DEV-560): return a {@code MermAidAnswer} that carries {@link #QUESTION} in
     * {@code clarifyingQuestions[]}, names no medicine, keeps {@code drugs} empty, and leaves the
     * disclaimer in place. Reuses the answer shape but with server-controlled content (FR-010).
     * Wire it into {@code DrugContextRetriever}/{@code ChatProxyController} at the point where a
     * declared allergy resolves to an empty avoided set.
     */
    // MermAidAnswer unresolvedAllergyAnswer() { ... }
}
