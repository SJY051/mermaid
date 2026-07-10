package com.mermaid.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mermaid.chat.dto.MedicalResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Turns whatever the model actually said into a {@link MedicalResponse} the client can render
 * (NFR-04, TC-03).
 *
 * <p>This is not defensive paranoia. We point the proxy at any OpenAI-compatible endpoint and swap
 * models freely, and support for {@code response_format} varies by model — a free model will happily
 * answer a JSON-schema request with a paragraph of prose, or wrap valid JSON in a markdown fence.
 * The client must not crash either way.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StructuredOutputFallback {

    /** SA-02: the client renders this. It is never absent. */
    public static final String DISCLAIMER =
            "This is general information, not medical advice or a diagnosis. "
                    + "Consult a licensed pharmacist or doctor. In an emergency, call 119.";

    private final ObjectMapper objectMapper;

    /**
     * @param rawContent the assistant message's {@code content}, as the model wrote it
     * @return a valid response — always, even if {@code rawContent} is prose or malformed
     */
    public MedicalResponse coerce(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return safeDefault("I could not produce an answer. Please try rephrasing.");
        }

        String candidate = stripMarkdownFence(rawContent.trim());
        try {
            MedicalResponse parsed = objectMapper.readValue(candidate, MedicalResponse.class);
            return withGuarantees(parsed);
        } catch (Exception e) {
            log.warn(
                    "Model ignored the response schema; falling back to plain text. reason={}",
                    e.getMessage());
            // The prose is still useful to the user — show it rather than an error.
            return safeDefault(rawContent);
        }
    }

    /** Fills in the fields the client depends on, whatever the model left out. */
    private MedicalResponse withGuarantees(MedicalResponse r) {
        return new MedicalResponse(
                r.reply() == null ? "" : r.reply(),
                r.urgency() == null ? MedicalResponse.Urgency.SEE_PHARMACIST : r.urgency(),
                r.medications() == null ? List.of() : r.medications(),
                r.map(),
                (r.disclaimer() == null || r.disclaimer().isBlank()) ? DISCLAIMER : r.disclaimer());
    }

    private MedicalResponse safeDefault(String reply) {
        return new MedicalResponse(
                reply, MedicalResponse.Urgency.SEE_PHARMACIST, List.of(), null, DISCLAIMER);
    }

    /** Models love to answer a JSON request with ```json … ``` around it. */
    static String stripMarkdownFence(String s) {
        if (!s.startsWith("```")) {
            return s;
        }
        int firstNewline = s.indexOf('\n');
        if (firstNewline < 0) {
            return s;
        }
        int closing = s.lastIndexOf("```");
        if (closing <= firstNewline) {
            return s;
        }
        return s.substring(firstNewline + 1, closing).trim();
    }
}
