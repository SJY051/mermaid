package com.mermaid.profile.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * How sure we are that a user's typed ingredient means the ingredient we matched it to (spec §2-12).
 *
 * <p>This is the difference between {@code blocked} and {@code warning}. A drug is only ever blocked
 * on an {@link #EXACT} or a reviewed {@link #SYNONYM} match. Spelling similarity is not evidence.
 */
public enum MatchConfidence {
    /** Byte-for-byte, after normalisation. */
    EXACT("exact"),
    /** A mapping a human reviewed and signed off (DEV-305). */
    SYNONYM("synonym"),
    /** Substring or compound-ingredient overlap. Enough to warn, never enough to block. */
    PARTIAL("partial"),
    /** We could not normalise the input at all. */
    UNKNOWN("unknown");

    private final String wire;

    MatchConfidence(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    /** Only a confident match may remove a medicine from someone's options. */
    public boolean canBlock() {
        return this == EXACT || this == SYNONYM;
    }
}
