package com.mermaid.chat;

import com.mermaid.chat.dto.MermAidAnswer;
import java.util.List;

/** Server-owned response for a turn with no official medicine record to ground an answer. */
final class ServerAuthoredEmptyAnswer {

    static final String ANSWER_ID = "server-empty-official-data";
    static final String SUMMARY =
            "I could not verify official medicine information for this request. "
                    + "No medicine recommendation is shown. Please ask a licensed pharmacist or doctor.";
    static final String URGENCY_TITLE = "Urgency was not assessed";
    static final String URGENCY_MESSAGE =
            "No verified medicine information is available for this response. This does not indicate "
                    + "whether your symptoms are urgent. If symptoms are severe or worsening, seek medical care.";

    private ServerAuthoredEmptyAnswer() {}

    static MermAidAnswer answer() {
        return new MermAidAnswer(
                MermAidAnswer.SCHEMA_VERSION,
                ANSWER_ID,
                "en",
                MermAidAnswer.DataStatus.UNAVAILABLE,
                new MermAidAnswer.Urgency(
                        MermAidAnswer.Urgency.Level.UNKNOWN,
                        URGENCY_TITLE,
                        URGENCY_MESSAGE,
                        List.of(),
                        List.of()),
                SUMMARY,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                StructuredOutputFallback.DISCLAIMER);
    }
}
