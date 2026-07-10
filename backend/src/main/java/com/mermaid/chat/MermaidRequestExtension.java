package com.mermaid.chat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The one non-standard field we accept on {@code POST /chat/completions}.
 *
 * <pre>
 * {
 *   "messages": [ … ],           ← OpenAI's
 *   "stream": false,             ← OpenAI's
 *   "mermaid": {                 ← ours
 *     "exclude_ingredients": ["Ibuprofen", "Paracetamol 500mg"]
 *   }
 * }
 * </pre>
 *
 * <p>The {@code openai} JS SDK forwards unknown top-level body keys untouched, so the frontend can
 * send this without leaving the SDK. {@link ChatProxyService} strips the key again before proxying —
 * an upstream provider would reject it.
 *
 * <p>Why a request field rather than a lookup by {@code deviceId}: allergies are session input by
 * default and only reach our database on explicit opt-in (spec §2-5). A user who has not opted in
 * still has an allergy, and it still has to filter their results. The browser holds it; the browser
 * sends it; we never store it.
 *
 * <p>Bounded on purpose. An unbounded list would become one upstream search per entry.
 */
final class MermaidRequestExtension {

    static final String FIELD = "mermaid";

    private static final String EXCLUDE_INGREDIENTS = "exclude_ingredients";
    private static final int MAX_EXCLUDED = 10;
    private static final int MAX_TERM_LENGTH = 100;

    private MermaidRequestExtension() {}

    /** Raw, unnormalised ingredient strings as the user typed them. Never null. */
    static Set<String> excludedIngredients(JsonNode clientRequest) {
        JsonNode node = clientRequest.path(FIELD).path(EXCLUDE_INGREDIENTS);
        if (!node.isArray()) {
            return Set.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        for (JsonNode entry : node) {
            String raw = entry.asText("").trim();
            if (!raw.isEmpty() && raw.length() <= MAX_TERM_LENGTH && terms.size() < MAX_EXCLUDED) {
                terms.add(raw);
            }
        }
        return terms;
    }
}
