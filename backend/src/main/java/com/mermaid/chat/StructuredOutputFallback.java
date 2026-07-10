package com.mermaid.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mermaid.chat.dto.MermAidAnswer;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Turns whatever the model actually said into a {@link MermAidAnswer} the client can render
 * (NFR-04, TC-03).
 *
 * <p>This is not defensive paranoia. We point the proxy at any OpenAI-compatible endpoint and swap
 * models freely, and support for {@code response_format} varies by model — a free model will happily
 * answer a JSON-schema request with a paragraph of prose, or wrap valid JSON in a markdown fence.
 *
 * <p>Note what the safe answer contains: no drugs, no actions, no sources. When we cannot trust the
 * structure we do not guess at the content. Prose is still useful to a sick person; an invented
 * medicine is not.
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
     * @return a valid answer — always, even if {@code rawContent} is prose or malformed
     */
    public MermAidAnswer coerce(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return safeAnswer("I could not produce an answer. Please try rephrasing.");
        }

        String candidate = stripMarkdownFence(rawContent.trim());
        try {
            MermAidAnswer parsed = objectMapper.readValue(candidate, MermAidAnswer.class);
            // Valid JSON of the WRONG shape is the sneakier failure: Jackson ignores unknown fields,
            // so a model that answered with `reply` instead of `summary` parses cleanly into an
            // object with nothing to render. An empty summary and no drugs means we learned nothing,
            // and the raw text is more use to the reader than a blank card.
            boolean empty =
                    (parsed.summary() == null || parsed.summary().isBlank())
                            && (parsed.drugs() == null || parsed.drugs().isEmpty());
            if (empty) {
                log.warn("Model returned JSON with no summary and no drugs; falling back to prose");
                return safeAnswer(rawContent);
            }
            return withGuarantees(parsed);
        } catch (Exception e) {
            log.warn("Model ignored the response schema; falling back to prose. reason={}", e.getMessage());
            return safeAnswer(rawContent);
        }
    }

    /** Fills in the fields the client depends on, whatever the model left out. */
    private MermAidAnswer withGuarantees(MermAidAnswer r) {
        return new MermAidAnswer(
                MermAidAnswer.SCHEMA_VERSION,
                r.answerId() == null ? "unknown" : r.answerId(),
                "en",
                r.dataStatus() == null ? MermAidAnswer.DataStatus.UNAVAILABLE : r.dataStatus(),
                r.urgency() == null ? unknownUrgency() : r.urgency(),
                r.summary() == null ? "" : r.summary(),
                nullSafe(r.clarifyingQuestions()),
                nullSafe(r.guidance()),
                nullSafe(r.drugs()),
                nullSafe(r.uiActions()),
                nullSafe(r.sourceRefs()),
                nullSafe(r.warnings()),
                (r.disclaimer() == null || r.disclaimer().isBlank()) ? DISCLAIMER : r.disclaimer());
    }

    /** No drugs, no actions, no sources — we do not trust the structure, so we claim nothing. */
    public MermAidAnswer safeAnswer(String summary) {
        return new MermAidAnswer(
                MermAidAnswer.SCHEMA_VERSION,
                "local-fallback",
                "en",
                MermAidAnswer.DataStatus.UNAVAILABLE,
                unknownUrgency(),
                summary,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                DISCLAIMER);
    }

    private static MermAidAnswer.Urgency unknownUrgency() {
        return new MermAidAnswer.Urgency(
                MermAidAnswer.Urgency.Level.UNKNOWN,
                "More information is needed",
                "I could not determine urgency from what you told me.",
                List.of(),
                List.of());
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
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
