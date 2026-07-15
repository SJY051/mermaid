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
 *     "exclude_ingredients": ["Ibuprofen"],
 *     "unverified_allergens": ["Yellow dye"]
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
 * <p>Bounded on purpose. A verified entry affects upstream filtering, and neither user-authored
 * list may become an unbounded request payload.
 */
final class MermaidRequestExtension {

    static final String FIELD = "mermaid";

    private static final String EXCLUDE_INGREDIENTS = "exclude_ingredients";
    private static final String UNVERIFIED_ALLERGENS = "unverified_allergens";
    private static final String UNANSWERED_QUESTIONS = "unanswered_questions";
    private static final int MAX_ENTRIES = 10;
    private static final int MAX_TERM_LENGTH = 100;
    /** A question, not an ingredient name — it gets a sentence's worth of room. */
    private static final int MAX_QUESTION_LENGTH = 2000;

    private MermaidRequestExtension() {}

    /**
     * The structured avoided list, plus whether we hold ALL of it.
     *
     * <p>{@code incomplete} is the truncation signal the gate needs: this field's contract is "the
     * complete list of ingredients this session must avoid", so an entry the bounds forced us to
     * drop means the avoided set the gate computes is NOT the user's list — retrieval on it could
     * show a product containing the dropped allergen as {@code no_match_found}. The gate treats an
     * incomplete list exactly like an unresolved entry: it clarifies (spec 005 FR-004).
     */
    record StructuredExclusions(
            Set<String> terms,
            Set<String> unverifiedTerms,
            Set<String> unansweredQuestions,
            boolean incomplete) {
        static final StructuredExclusions NONE =
                new StructuredExclusions(Set.of(), Set.of(), Set.of(), false);
    }

    private record ParsedList(Set<String> terms, boolean incomplete) {}

    /**
     * Raw, unnormalised ingredient strings as the user typed them. Never null.
     *
     * <p>Every level of the path follows one rule: absent (or an explicit JSON {@code null}, the
     * idiom for "none") is a complete empty list, but a level that is PRESENT in the wrong shape —
     * a string or object where the extension or the array should be — is the same
     * serialization-loss case as a non-textual entry. It may be a mangled list carrying an
     * allergen we cannot see, so it flags {@code incomplete} and the gate clarifies.
     */
    static StructuredExclusions excludedIngredients(JsonNode clientRequest) {
        JsonNode extension = clientRequest.path(FIELD);
        if (extension.isMissingNode() || extension.isNull()) {
            return StructuredExclusions.NONE;
        }
        if (!extension.isObject()) {
            return new StructuredExclusions(Set.of(), Set.of(), Set.of(), true);
        }
        ParsedList exclusions = parseList(extension, EXCLUDE_INGREDIENTS, MAX_TERM_LENGTH);
        ParsedList unverified = parseList(extension, UNVERIFIED_ALLERGENS, MAX_TERM_LENGTH);
        ParsedList unanswered = parseList(extension, UNANSWERED_QUESTIONS, MAX_QUESTION_LENGTH);
        return new StructuredExclusions(
                exclusions.terms(),
                unverified.terms(),
                unanswered.terms(),
                exclusions.incomplete() || unverified.incomplete() || unanswered.incomplete());
    }

    private static ParsedList parseList(JsonNode extension, String field, int maxLength) {
        JsonNode node = extension.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return new ParsedList(Set.of(), false);
        }
        if (!node.isArray()) {
            return new ParsedList(Set.of(), true);
        }
        Set<String> terms = new LinkedHashSet<>();
        boolean incomplete = false;
        for (JsonNode entry : node) {
            if (!entry.isTextual()) {
                // An object, array, number or null in the list is a client serialization bug, and
                // it may be carrying an allergen we cannot read (`asText` would flatten it to ""
                // and the blank branch below would call that "no allergen"). Unreadable ≠ empty.
                incomplete = true;
                continue;
            }
            String raw = entry.asText().trim();
            if (raw.isEmpty()) {
                continue; // a blank string carries no allergen; dropping it loses nothing
            }
            if (raw.length() > maxLength || terms.size() >= MAX_ENTRIES) {
                // A real entry we cannot keep. The bounds stay (an unbounded list is one upstream
                // search per entry), but dropping silently would launder the loss — flag it.
                incomplete = true;
                continue;
            }
            terms.add(raw);
        }
        return new ParsedList(java.util.Collections.unmodifiableSet(terms), incomplete);
    }
}
