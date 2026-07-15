package com.mermaid.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.mermaid.chat.dto.MermAidAnswer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ServerAuthoredTerminalAnswerTest {

    @Test
    @DisplayName("a completed empty official lookup has the approved fixed copy and empty shape")
    void emptyOfficialResultIsPinned() {
        MermAidAnswer answer = ServerAuthoredEmptyAnswer.answer();

        assertThat(answer.answerId()).isEqualTo("server-empty-official-data");
        assertThat(answer.summary())
                .isEqualTo(
                        "I could not verify official medicine information for this request. "
                                + "No medicine recommendation is shown. Please ask a licensed pharmacist or doctor.");
        assertThat(answer.urgency().title()).isEqualTo("Urgency was not assessed");
        assertThat(answer.urgency().message())
                .isEqualTo(
                        "No verified medicine information is available for this response. This does not indicate "
                                + "whether your symptoms are urgent. If symptoms are severe or worsening, seek medical care.");
        assertEmptyTerminalShape(answer);
    }

    @Test
    @DisplayName("Pass 1 unavailability has distinct approved copy and the same empty shape")
    void unavailableSearchIsPinnedAndDistinct() {
        MermAidAnswer answer = ServerAuthoredSearchUnavailableAnswer.answer();

        assertThat(answer.answerId()).isEqualTo("server-search-unavailable");
        assertThat(answer.summary())
                .isEqualTo(
                        "I could not prepare an official medicine lookup for this request, so no medicine "
                                + "recommendation is shown. Please try again, or ask a licensed pharmacist or doctor.")
                .isNotEqualTo(ServerAuthoredEmptyAnswer.SUMMARY);
        assertThat(answer.urgency().title()).isEqualTo("Urgency was not assessed");
        assertThat(answer.urgency().message())
                .isEqualTo(
                        "Medicine lookup was unavailable for this response. This does not indicate whether your "
                                + "symptoms are urgent. If symptoms are severe or worsening, seek medical care.");
        assertEmptyTerminalShape(answer);
    }

    private static void assertEmptyTerminalShape(MermAidAnswer answer) {
        assertThat(answer.schemaVersion()).isEqualTo(MermAidAnswer.SCHEMA_VERSION);
        assertThat(answer.language()).isEqualTo("en");
        assertThat(answer.dataStatus()).isEqualTo(MermAidAnswer.DataStatus.UNAVAILABLE);
        assertThat(answer.urgency().level()).isEqualTo(MermAidAnswer.Urgency.Level.UNKNOWN);
        assertThat(answer.urgency().reasonCodes()).isEmpty();
        assertThat(answer.urgency().actions()).isEmpty();
        assertThat(answer.clarifyingQuestions()).isEmpty();
        assertThat(answer.guidance()).isEmpty();
        assertThat(answer.drugs()).isEmpty();
        assertThat(answer.uiActions()).isEmpty();
        assertThat(answer.sourceRefs()).isEmpty();
        assertThat(answer.warnings()).isEmpty();
        assertThat(answer.disclaimer()).isEqualTo(StructuredOutputFallback.DISCLAIMER);
    }
}
