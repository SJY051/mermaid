package com.mermaid.chat;

import com.mermaid.chat.dto.MermAidAnswer;
import java.util.List;

/** Server-owned response when Pass 1 could not prepare an official medicine lookup. */
final class ServerAuthoredSearchUnavailableAnswer {

    static final String ANSWER_ID = "server-search-unavailable";
    static final String SUMMARY =
            "I could not prepare an official medicine lookup for this request, so no medicine "
                    + "recommendation is shown. Please try again, or ask a licensed pharmacist or doctor.";
    static final String URGENCY_TITLE = "Urgency was not assessed";
    static final String URGENCY_MESSAGE =
            "Medicine lookup was unavailable for this response. This does not indicate whether your "
                    + "symptoms are urgent. If symptoms are severe or worsening, seek medical care.";

    private ServerAuthoredSearchUnavailableAnswer() {}

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
