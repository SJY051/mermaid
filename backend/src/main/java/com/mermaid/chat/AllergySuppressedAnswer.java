package com.mermaid.chat;

import com.mermaid.chat.dto.MermAidAnswer;
import java.util.List;

/** Server-authored terminal answer when SA-08 removes every AI-selected medicine. */
public final class AllergySuppressedAnswer {

    private AllergySuppressedAnswer() {}

    public static MermAidAnswer answer() {
        return new MermAidAnswer(
                MermAidAnswer.SCHEMA_VERSION,
                "allergy-suppressed",
                "en",
                MermAidAnswer.DataStatus.UNAVAILABLE,
                new MermAidAnswer.Urgency(
                        MermAidAnswer.Urgency.Level.UNKNOWN,
                        "Urgency was not assessed",
                        "An allergy list cannot determine how urgent your symptoms are. "
                                + "If symptoms are severe or worsening, seek medical care.",
                        List.of(),
                        List.of()),
                "Because you told us about an allergy, no AI-selected medicine is shown. "
                        + "We cannot verify whether a different medicine could be related to what "
                        + "you react to. Ask a licensed pharmacist or doctor before taking any "
                        + "medicine.",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                StructuredOutputFallback.DISCLAIMER);
    }
}
