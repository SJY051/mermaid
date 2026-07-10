package com.mermaid.chat.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;

/**
 * The result of comparing a drug's ingredients against what the user must avoid (FR-04).
 *
 * <p>Four states, not a nullable warning. The v1 model used {@code String allergyWarning} where
 * {@code null} meant both "no match" and "could not check" — to a parent with an allergic child
 * those are opposite answers.
 *
 * <p>Rules that are not negotiable (spec §2-12):
 *
 * <ul>
 *   <li>Never derive a match from an LLM alone. Use a reviewed synonym dictionary.
 *   <li>Never mark {@code BLOCKED} on spelling similarity.
 *   <li>Never render {@link Status#NO_MATCH_FOUND} as "safe to take".
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AllergyCheck(Status status, List<String> matchedIngredients, String message) {

    public enum Status {
        /** Exact match, or a match through a reviewed synonym. */
        BLOCKED("blocked"),
        /** Partial match, compound ingredient, or an uncertain normalisation. */
        WARNING("warning"),
        /** Nothing matched in the ingredient list we hold. <b>Not a safety guarantee.</b> */
        NO_MATCH_FOUND("no_match_found"),
        /** We could not compare — missing ingredients, or unparseable user input. */
        UNKNOWN("unknown");

        private final String wire;

        Status(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }

        @JsonCreator
        public static Status from(String value) {
            for (Status s : values()) {
                if (s.wire.equalsIgnoreCase(value)) {
                    return s;
                }
            }
            // An unrecognised state must degrade to "we don't know", never to "no match".
            return UNKNOWN;
        }
    }

    public static AllergyCheck unknown(String reason) {
        return new AllergyCheck(Status.UNKNOWN, List.of(), reason);
    }

    public static AllergyCheck noMatch() {
        return new AllergyCheck(
                Status.NO_MATCH_FOUND,
                List.of(),
                "No match found in the listed ingredients. This is not a guarantee — confirm with a pharmacist.");
    }
}
