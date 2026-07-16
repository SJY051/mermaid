package com.mermaid.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.mermaid.chat.dto.MermAidAnswer;
import com.mermaid.chat.dto.UiAction;
import com.mermaid.drug.IngredientNormalizer;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The model cannot be trusted with this. Asked about "crushing chest pain and I cannot breathe
 * properly", a live model on our endpoint answered {@code urgency: "unknown"} with no emergency
 * action, and every downstream check passed it — the invariant that demands SHOW_EMERGENCY_CALL only
 * fires once the model has already said "emergency".
 */
class EmergencyTriageTest {

    private final EmergencyTriage triage = new EmergencyTriage();
    private final AnswerValidator validator = new AnswerValidator(new IngredientNormalizer());

    @ParameterizedTest
    @DisplayName("red flags in the user's own words are caught")
    @ValueSource(
            strings = {
                "I have crushing chest pain and I cannot breathe properly.",
                "chest tightness since this morning",
                "I can't breathe",
                "my father is having difficulty breathing",
                "her face is drooping and her speech is slurred",
                "the bleeding won't stop",
                "he passed out and won't wake up",
                "I feel suicidal",
            })
    void catchesRedFlags(String text) {
        assertThat(triage.screen(text)).isPresent();
    }

    @ParameterizedTest
    @DisplayName("approved anaphylaxis and sudden airway swelling phrases are escalated")
    @ValueSource(
            strings = {
                "I am having an anaphylactic reaction.",
                "My throat is suddenly swelling.",
                "I had an anaphylactic reaction five years ago.",
            })
    void escalatesApprovedAnaphylaxisAndAirwaySwelling(String text) {
        assertThat(triage.screen(text)).isPresent();
    }

    @ParameterizedTest
    @DisplayName("nearby non-emergency throat and lip phrases are not escalated")
    @ValueSource(
            strings = {
                "My throat is sore.",
                "My lips are dry.",
            })
    void doesNotEscalateNonEmergencyThroatAndLipPhrases(String text) {
        assertThat(triage.screen(text)).isEmpty();
    }

    @ParameterizedTest
    @DisplayName("approved sudden or severe abdominal pain phrases are escalated")
    @ValueSource(
            strings = {
                "I have sudden abdominal pain.",
                "I have severe stomach pain.",
            })
    void escalatesApprovedAbdominalPain(String text) {
        assertThat(triage.screen(text)).isPresent();
    }

    @ParameterizedTest
    @DisplayName("mild abdominal complaints are not escalated")
    @ValueSource(
            strings = {
                "I have mild stomach pain.",
                "I have some abdominal discomfort.",
            })
    void doesNotEscalateMildAbdominalComplaints(String text) {
        assertThat(triage.screen(text)).isEmpty();
    }

    @ParameterizedTest
    @DisplayName("ordinary complaints are not escalated")
    @ValueSource(
            strings = {
                "I have a sore throat and a fever, and it's 11pm.",
                "My child has a mild cough.",
                "Where can I buy painkillers near me?",
                "I need something for a headache.",
                "I am allergic to ibuprofen. What can I take?",
            })
    void doesNotEscalateOrdinary(String text) {
        assertThat(triage.screen(text)).isEmpty();
    }

    @Test
    @DisplayName("null and blank input do not fire")
    void emptyInput() {
        assertThat(triage.screen(null)).isEmpty();
        assertThat(triage.screen("  ")).isEmpty();
    }

    @Test
    @DisplayName("the emergency answer recommends no medicine and carries the call action")
    void emergencyAnswerShape() {
        MermAidAnswer a = triage.emergencyAnswer("CHEST_PAIN");

        assertThat(a.urgency().level()).isEqualTo(MermAidAnswer.Urgency.Level.EMERGENCY);
        assertThat(a.drugs()).isEmpty();
        assertThat(a.uiActions()).anyMatch(x -> x instanceof UiAction.ShowEmergencyCall);
        assertThat(a.urgency().reasonCodes()).contains("CHEST_PAIN");
        assertThat(a.summary()).contains("119");
        assertThat(a.disclaimer()).isNotBlank();
    }

    @Test
    @DisplayName("the emergency answer passes every post-processing invariant")
    void emergencyAnswerIsValid() {
        MermAidAnswer a = triage.emergencyAnswer("BREATHING");

        // No retrieved drugs, so an answer naming any drug would be rejected — it names none.
        assertThat(validator.validate(a, Map.of())).isEmpty();
    }
}
