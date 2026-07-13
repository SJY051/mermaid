package com.mermaid.facility.domain;

import com.mermaid.common.SourceRef;
import java.util.List;
import java.util.Map;

/**
 * A pharmacy's raw duty-time table as {@code getParmacyBassInfoInqire} returns it: index 1..8 →
 * {@code [open, close]} as HHMM strings, 1=Mon … 7=Sun, 8=공휴일. A day the pharmacy is closed is
 * simply absent (see {@link WeeklyHours}).
 *
 * <p>This is a record, not a bare {@code Map<Integer, List<String>>}, because it is a cache value and
 * {@link com.mermaid.config.CacheConfig} serializes entries as JSON with {@code
 * DefaultTyping.EVERYTHING}. Two things break a bare map there, and neither is caught by {@code
 * cache.type=simple} tests (§11):
 *
 * <ul>
 *   <li>a {@code String[]} value serializes with type id {@code [Ljava.lang.String;}, which the
 *       polymorphic-type validator rejects — the read throws;
 *   <li>a bare map deserializes into {@code Object}, so its {@code Integer} keys come back as {@code
 *       String} and {@code byDay().get(1)} silently misses every entry.
 * </ul>
 *
 * <p>A typed record fixes both: its {@code @class} carries the concrete type, and the declared field
 * generics restore {@code Integer} keys and {@code List<String>} values verbatim. Guarded by {@code
 * CacheConfigTest}.
 *
 * @param origin whether this table was read live or served from a fixture fallback. Provenance is
 *     per-fetch, not per-app-mode (§2-14): in {@code hybrid} a live directory and a fixture schedule
 *     can reach the same card, and each must be labelled truthfully. Empty tables retain their fetch
 *     origin but are never consulted for provenance (the caller gates on {@code byDay().isEmpty()}).
 */
public record DutyTable(Map<Integer, List<String>> byDay, SourceRef.DataMode origin) {

    public static DutyTable empty(SourceRef.DataMode origin) {
        return new DutyTable(Map.of(), origin);
    }

    // No boolean accessor (e.g. isEmpty()): as a cache value this record is serialized as JSON, and
    // Jackson would treat "isEmpty" as a bean property, write it out, then reject it on read-back
    // (the canonical constructor knows only the record components). Callers ask byDay().isEmpty().
}
