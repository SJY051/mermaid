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

    private static final String INVALID_STRUCTURE_REFUSAL =
            "I could not verify that answer against official data, so I will not show it. "
                    + "Please describe your symptoms again, or visit a pharmacy.";

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
            // Every model-authored list that reaches a rendered field: a null uiAction crashes the
            // browser at action.type before the safety/disclaimer UI can draw (fail closed instead).
            int nullElements =
                    countNulls(parsed.drugs())
                            + countNulls(parsed.guidance())
                            + countNulls(parsed.uiActions())
                            + countNulls(parsed.urgency() == null ? null : parsed.urgency().actions());
            if (nullElements > 0) {
                log.warn("model_answer_rejected code=NULL_COLLECTION_ELEMENT count={}", nullElements);
                return safeAnswer(INVALID_STRUCTURE_REFUSAL);
            }
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
            // The type, and the field it choked on — not the content. Jackson's own message quotes
            // the source text it failed to parse, and that text is the model's answer about this
            // person's symptoms: a log line is a place things persist, and a consultation does not
            // persist (§2-5). The field path is enough to tell a schema drift from a broken model.
            String path = e instanceof com.fasterxml.jackson.databind.JsonMappingException mapping
                    ? mapping.getPathReference()
                    : "unknown";
            log.warn(
                    "model_answer_rejected code=COERCION_FAILED exception={} path={}",
                    e.getClass().getSimpleName(),
                    path);
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

    private static int countNulls(List<?> list) {
        return list == null ? 0 : (int) list.stream().filter(java.util.Objects::isNull).count();
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
