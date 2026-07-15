package com.mermaid.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.mermaid.chat.dto.MermAidAnswer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AllergySuppressedAnswerTest {

    @Test
    @DisplayName("SA-08 terminal output is fixed, medicine-free, and non-reassuring")
    void answerOwnsTheEntireSuppressionState() {
        MermAidAnswer answer = AllergySuppressedAnswer.answer();

        assertThat(answer.schemaVersion()).isEqualTo(MermAidAnswer.SCHEMA_VERSION);
        assertThat(answer.answerId()).isEqualTo("allergy-suppressed");
        assertThat(answer.language()).isEqualTo("en");
        assertThat(answer.dataStatus()).isEqualTo(MermAidAnswer.DataStatus.UNAVAILABLE);
        assertThat(answer.urgency().level()).isEqualTo(MermAidAnswer.Urgency.Level.UNKNOWN);
        assertThat(answer.urgency().title()).isEqualTo("Urgency was not assessed");
        assertThat(answer.urgency().message())
                .isEqualTo(
                        "An allergy list cannot determine how urgent your symptoms are. "
                                + "If symptoms are severe or worsening, seek medical care.");
        assertThat(answer.summary())
                .isEqualTo(
                        "Because you told us about an allergy, no AI-selected medicine is shown. "
                                + "We cannot verify whether a different medicine could be related to "
                                + "what you react to. Ask a licensed pharmacist or doctor before "
                                + "taking any medicine.")
                .doesNotContainIgnoringCase("safe");
        assertThat(answer.drugs()).isEmpty();
        assertThat(answer.guidance()).isEmpty();
        assertThat(answer.clarifyingQuestions()).isEmpty();
        assertThat(answer.uiActions()).isEmpty();
        assertThat(answer.sourceRefs()).isEmpty();
        assertThat(answer.warnings()).isEmpty();
        assertThat(answer.disclaimer()).isEqualTo(StructuredOutputFallback.DISCLAIMER);
    }
}
