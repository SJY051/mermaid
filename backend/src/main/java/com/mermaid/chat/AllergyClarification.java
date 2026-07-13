package com.mermaid.chat;

import com.mermaid.chat.dto.MermAidAnswer;
import java.util.List;

/**
 * DEV-560 (spec 005). Server-authored clarifying question for a declared allergy whose
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
                    + "Please type the exact ingredient name shown on the label, if you know it.";

    private AllergyClarification() {}

    /**
     * Builds the answer shown when a declared allergy did not resolve.
     *
     * <p>The answer carries {@link #QUESTION} in {@code clarifyingQuestions[]}, names no medicine,
     * keeps {@code drugs} empty, and retains the universal disclaimer. No model text participates.
     */
    public static MermAidAnswer answer() {
        return new MermAidAnswer(
                MermAidAnswer.SCHEMA_VERSION,
                "allergy-clarification",
                "en",
                MermAidAnswer.DataStatus.UNAVAILABLE,
                new MermAidAnswer.Urgency(
                        MermAidAnswer.Urgency.Level.ROUTINE,
                        "One allergy detail is needed",
                        "I need the ingredient name before I can compare it with official product data.",
                        List.of(),
                        List.of()),
                "I will not suggest or check a medicine until the allergy ingredient is clear.",
                List.of(QUESTION),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                StructuredOutputFallback.DISCLAIMER);
    }
}
