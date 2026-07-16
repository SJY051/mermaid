package com.mermaid.chat;

import com.mermaid.chat.dto.MermAidAnswer;
import com.mermaid.chat.dto.UiAction;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Rule-based emergency screening that runs <b>before</b> the model (SA-03, DEV-405).
 *
 * <p>Why this exists, concretely: given "I have crushing chest pain and I cannot breathe properly",
 * a live model on our endpoint returned {@code urgency: "unknown"} and no emergency action. The
 * post-processing invariant that requires {@code SHOW_EMERGENCY_CALL} only fires when the model
 * already said {@code emergency} — so a model that fails to recognise a heart attack sails through
 * every check we have. Verified 2026-07-10 against deepseek-v4-flash.
 *
 * <p>So we do not ask. When a red flag matches, we answer from here and never call the model at all:
 * it is faster for someone who is frightened, and it cannot be talked out of the answer.
 *
 * <p>This is <b>not</b> diagnosis. It is deliberately over-eager escalation: a false positive costs
 * one unnecessary "please call 119", a false negative costs much more. Any change to these patterns
 * needs a second reviewer (spec §7, tasks.md §5).
 */
@Component
public class EmergencyTriage {

    /**
     * Red flags, in English, matched case-insensitively against the user's own words.
     *
     * <p>The anaphylaxis, sudden airway-swelling, and sudden/severe abdominal-pain boundaries are
     * product-approved high-sensitivity rules (SJY051, 2026-07-16). They deliberately do not infer
     * tense or negation. Other candidate groups still require a separate decision before addition.
     */
    private static final List<RedFlag> RED_FLAGS =
            List.of(
                    new RedFlag("ANAPHYLAXIS", "(?i)\\b(anaphylaxis|anaphylactic( reaction)?)\\b"),
                    new RedFlag(
                            "AIRWAY_SWELLING",
                            "(?i)(\\bsudden(ly)? swelling (of )?(my |the |his |her |their |our |your )?"
                                    + "(lip|lips|mouth|throat|tongue)\\b"
                                    + "|\\bsudden(ly)? (my |the |his |her |their |our |your )?"
                                    + "(lip|lips|mouth|throat|tongue) swelling\\b"
                                    + "|\\b(my |the |his |her |their |our |your )?"
                                    + "(lip|lips|mouth|throat|tongue) (is |are |started |began )?"
                                    + "sudden(ly)? (swelling|swollen)\\b"
                                    + "|\\b(my |the |his |her |their |our |your )?"
                                    + "(lip|lips|mouth|throat|tongue) sudden(ly)? "
                                    + "(started|began) swelling\\b)"),
                    new RedFlag(
                            "ABDOMINAL_PAIN",
                            "(?i)\\b(sudden|severe) (abdominal|stomach) pain\\b"),
                    new RedFlag("CHEST_PAIN", "(?i)\\b(chest (pain|pressure|tightness)|crushing chest)\\b"),
                    new RedFlag(
                            "BREATHING",
                            "(?i)(can'?t breathe|cannot breathe|difficulty breathing|struggling to breathe|gasping for air)"),
                    new RedFlag(
                            "STROKE",
                            "(?i)(face (is )?drooping|slurred speech|sudden numbness|one side of my (face|body))"),
                    // Both word orders. "won't stop bleeding" and "the bleeding won't stop" are the
                    // same emergency, and only one of them matched the first version of this pattern.
                    new RedFlag(
                            "BLEEDING",
                            "(?i)(severe bleeding|bleeding heavily|won'?t stop bleeding"
                                    + "|bleeding (won'?t|will not|does not|doesn'?t) stop)"),
                    new RedFlag("UNCONSCIOUS", "(?i)(unconscious|passed out|not responding|won'?t wake up)"),
                    new RedFlag(
                            "SELF_HARM",
                            "(?i)(suicidal|kill myself|end my life|want to die|hurt myself)"));

    private final List<CompiledFlag> compiled =
            RED_FLAGS.stream().map(f -> new CompiledFlag(f.code(), Pattern.compile(f.regex()))).toList();

    /** All model-visible user text for this stateless request is screened together. */
    public Optional<String> screen(String userText) {
        if (userText == null || userText.isBlank()) {
            return Optional.empty();
        }
        return compiled.stream()
                .filter(f -> f.pattern().matcher(userText).find())
                .map(CompiledFlag::code)
                .findFirst();
    }

    /**
     * The answer we give instead of asking a model. No medicines, no map filters, no hedging.
     *
     * @param reasonCode which red flag fired, so QA can trace it and the UI can explain it
     */
    public MermAidAnswer emergencyAnswer(String reasonCode) {
        return new MermAidAnswer(
                MermAidAnswer.SCHEMA_VERSION,
                "triage-" + reasonCode.toLowerCase(),
                "en",
                MermAidAnswer.DataStatus.UNAVAILABLE,
                new MermAidAnswer.Urgency(
                        MermAidAnswer.Urgency.Level.EMERGENCY,
                        "This may be a medical emergency",
                        "Based on what you described, please call 119 now or go to the nearest "
                                + "emergency room. Do not wait, and do not take any medicine first.",
                        List.of(reasonCode),
                        List.of(UiAction.ShowEmergencyCall.korea119())),
                "Call 119 immediately. In Korea, 119 handles both fire and medical emergencies, "
                        + "and interpretation is available.",
                List.of(),
                List.of(),
                List.of(), // never recommend a medicine in an emergency
                List.of(UiAction.ShowEmergencyCall.korea119()),
                List.of(),
                List.of("This assessment is based on keywords in your message, not a diagnosis."),
                StructuredOutputFallback.DISCLAIMER);
    }

    private record RedFlag(String code, String regex) {}

    private record CompiledFlag(String code, Pattern pattern) {}
}
