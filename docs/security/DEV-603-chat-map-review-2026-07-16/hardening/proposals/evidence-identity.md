# Security Hardening Proposal: Provenanced evidence envelope

## Decision

Decide whether each adapter, cache, service, grounding map, and fallback independently reconstructs identity/freshness/data-mode semantics, or whether every retrieved fact travels in one envelope that makes those properties inseparable from the value.

## Executive Recommendation

- **Option 1: Binding checks and cache namespaces.** Patch request-response identity checks, include mode/origin in keys, preserve original timestamps, and label every fallback as fixture.
- **Option 2: Provenanced evidence envelope.** Represent query contract, stable record identity, source agency, observation time, data mode, and derivation/fallback status together; cache and release only that type.

I recommend Option 2. Option 1 is the necessary immediate containment, but the scan shows the same metadata being lost and recreated in drug, DUR, pharmacy, hospital, cache, hybrid, and grounding paths.

## Evidence

| Evidence | Finding or audit | What it establishes |
| --- | --- | --- |
| `R01-CAN-098` | [Cached pharmacy facts are restamped as freshly retrieved](../../artifacts/central_validation_round06/batch-03/validation.jsonl) | **Observed:** cache age is replaced with request time, directly weakening server-owned provenance. |
| `R01-CAN-105` | [Hybrid fallback is stamped live and current](../../artifacts/central_validation_round06/batch-03/validation.jsonl) | **Observed:** repository sample data can acquire live/current authority. |
| `R01-CAN-109` | [Fixed fixture product is bound to an arbitrary requested ID](../../artifacts/central_validation_round06/batch-03/validation.jsonl) | **Observed:** query identity and returned record identity diverge while the server authors the result. |
| `R01-CAN-113` | [DUR fallback warnings are not bound to the requested medicine](../../artifacts/central_validation_round06/batch-04/validation.jsonl) | **Observed:** warnings can be attached to a different medicine. |
| `R05-CAN-023` | [Cached drug facts are restamped as freshly retrieved](../../artifacts/central_validation_round06/batch-05/validation.jsonl) | **Observed:** the same freshness loss recurs across medicine facts. |
| `R05-CAN-004` | [Display-name-keyed grounding collision](../../artifacts/central_validation_round06/batch-05/validation.jsonl) | **Needs review:** a lossy identity key is observed; harmful real collision remains conditional. |
| `EV-B` | [Track B final audit](../../artifacts/finalization/track_b_final_audit.md) | **Observed:** facility cache keys omit `DATA_MODE`, allowing fixture/live reuse even though it was not the zero-pin root cause. |

**Inferred:** the repeated defect is metadata being treated as decoration. Once a raw row becomes an ordinary domain value, downstream code cannot tell whether it satisfied the query, came from live or fixture data, or was observed six hours ago. It then invents that context from the current request.

## Current Design And Failure Mode

Adapters return domain-like records; fallback branches can substitute fixed fixtures; caches store and later return values; services reconstruct source references and timestamps; grounding may key by display name. Each step is locally understandable, but their composition allows an unrelated or stale value to look verified, live, and current.

This is especially dangerous in hybrid mode. “Fallback” is an availability decision, not proof that a fixture answers the caller's query. If fallback identity is not exact, the only truthful result may be unavailable/fixture—not a server-authored live fact. Cache boundaries must preserve the time the upstream fact was observed, not the time it was read from Redis.

## Desired Invariants

- Every fact has a stable provider record identity and the exact query contract it satisfied.
- `dataMode`, agency, original observation time, and fallback/derivation status survive caching unchanged.
- A fixture envelope can never serialize as live; a fallback that does not satisfy the query cannot become a result.
- Cache keys include every input that changes fact identity or interpretation, including mode and origin when distance is stored.
- Grounding and source references join on stable identity, never display text alone.
- Missing or contradictory identity fails to an explicit unavailable/unknown state, not a fabricated match.

## Constraints And Non-Goals

Fixture mode remains essential for offline development. Redis remains a performance tool, not the source of truth. The proposal does not decide whether hybrid mode should be user-visible or permitted for every endpoint; that is a product/operations decision. It does not treat provider strings as executable trust.

## Before Architecture

[Current identity/provenance flow](../diagrams/evidence-identity-before.mmd)

The before diagram's restamping edge is the structural tell: cache/fallback outputs lack enough durable identity, so the service reconstructs provenance from “now” and the active request.

## Options

### Option 1: Binding checks and cache namespaces

This option adds endpoint-specific response binding, rejects unrelated first rows, namespaces cache keys by data mode and query inputs, preserves cached timestamps, and marks fallback rows as fixture. It can directly address the known findings without changing the central domain model.

The strongest case is low migration risk. The concern is repetition: every adapter and new cache must remember the same identity tuple, and a domain record can still be created without it. Local checks can also disagree about whether a transformed record remains the “same” evidence.

[Option 1 after view](../diagrams/evidence-identity-local-guards-after.mmd)

| Change | Before | After | Security consequence | Cost |
| --- | --- | --- | --- | --- |
| Response binding | First/matching-looking row | Endpoint-specific ID/query check | Blocks fixed/unrelated fallback | Repeated adapter logic |
| Cache identity | Incomplete mode/origin inputs | Expanded namespaces/keys | Reduces cross-mode/origin reuse | Key migration and lower hit rate |
| Freshness | Restamped on read | Preserve source observation time | Truthful age | DTO/cache compatibility |
| Grounding | Display-field joins | Stable ID where available | Narrows collisions | Per-path retrofit |

Performance may regress through lower cache reuse, but that reuse was semantically unsafe. Memory can increase because mode/origin variants no longer collide. Reliability improves by preventing wrong facts, while availability can decrease when an invalid fallback is rejected. Those are honest tradeoffs to measure, not reasons to preserve false provenance.

### Option 2: Provenanced evidence envelope

The structural option makes evidence metadata part of the type. An envelope carries the query fingerprint, provider identity, source agency, observation time, mode, derivation/fallback chain, and value. Only adapters can create live envelopes; fixtures create fixture envelopes; caches store the envelope unchanged; an identity verifier decides whether it satisfies the current request before a source reference is released.

This design gives downstream code less freedom to invent authority. Distance derived for an origin can declare that origin; a cache key can be derived from the envelope contract; a model context can receive stable source IDs without raw provenance fabrication. What gives me pause is breadth: drug, facility, and cache domains must agree on a small generic core without erasing their provider-specific traps. We should avoid a universal “metadata map” that is merely an untyped convention.

The additional object fields increase cache memory and serialization size. Cache hit rate may fall after correct namespacing. The validation plan therefore needs representative live/fixture/hybrid workloads, but no percentage should be assumed. Rollback can read old cache entries as invalid/miss while new envelopes are introduced; it must not silently upgrade old entries to fresh live evidence.

[Option 2 after view](../diagrams/evidence-identity-provenanced-envelope-after.mmd)

| Change | Before | After | Security consequence | Cost |
| --- | --- | --- | --- | --- |
| Evidence value | Domain record plus reconstructed metadata | Typed envelope with identity/freshness/mode | Metadata cannot be detached silently | Cross-domain type design |
| Fallback | May substitute fixed rows | Fixture envelope with satisfaction check | Prevents live/provenance laundering | More unavailable outcomes |
| Cache | Stores values under caller-chosen keys | Stores evidence under contract-derived keys | Reduces collision and restamping | Cache migration/memory |
| Release | SourceRef authored late | SourceRef derived from verified envelope | One provenance owner | Serializer and API adapters |

## Comparison

| Dimension | Option 1: local binding | Option 2: evidence envelope |
| --- | --- | --- |
| Security | Addresses known adapters/caches | Makes identity/freshness/mode mandatory by construction |
| Performance | More cache misses on corrected keys | Same plus envelope verification/serialization |
| Memory | More key variants | More key variants and metadata per value |
| Reliability | Wrong data rejected; local drift remains | Consistent unavailable/unknown behavior; broader migration risk |
| Operability | Per-client diagnostics | Uniform evidence/mode/fallback reason codes |
| Migration | Focused cache flush/versioning | Versioned cache/value/API adapters across domains |
| Rollback | Revert individual checks, invalidate affected caches | Dual-read only if old entries remain untrusted misses |

## Recommendation

I recommend Option 2 because provenance is the product's defining integrity claim. It should be impossible to construct a “verified live current” fact without the evidence required to support those words. Option 1 is proportionate if only one adapter is in scope, but this scan proves the problem is already cross-domain.

## Evidence Coverage And Residual Risk

| Evidence | Option 1 | Option 2 | Tactical fix still required? |
| --- | --- | --- | --- |
| `R01-CAN-098`, `R05-CAN-023` — restamped cache facts | Addresses timestamp preservation | Addresses by immutable observation time | Yes |
| `R01-CAN-105` — hybrid stamped live | Addresses explicit label | Addresses by mode-typed envelope | Yes |
| `R01-CAN-109/113` — unrelated product/warnings | Addresses endpoint binding | Addresses query satisfaction centrally | Yes |
| `R05-CAN-004` — display-name collision | Mitigates if stable ID added | Mitigates by identity-keyed registry | Needs-review proof remains open |
| Track B mode-key omission | Addresses key namespace | Addresses contract-derived keying | Yes |

Residual risk includes authoritative upstream data that is itself wrong, provider identity changes, clock quality, stale-but-within-policy facts, and product decisions about when fixture/hybrid output is acceptable.

## Migration And Rollout

Any future migration must treat old cache values as untrusted and preserve direct tactical binding checks until envelope creation is the only path. No ordered work plan is authorized here.

## Validation Plan

- Replay each fixed-fixture and cache-restamping case and prove identity/mode/time remain truthful.
- Property-test that changing query ID, ingredient, origin, or mode cannot reuse an incompatible envelope.
- Compare cache hit rate, serialized size, Redis memory, and endpoint latency on representative live/fixture/hybrid workloads.
- Verify fallback never returns a row whose envelope does not satisfy the query contract.
- Mutation-test display-name joins so duplicate names cannot overwrite stable identities.

## Implementation Work Packages

Intentionally omitted pending option selection and operations/product approval of hybrid semantics.

## Open Questions

- Is hybrid fallback allowed to answer user-visible requests, or only development diagnostics?
- Which provider fields are stable identities for each agency and operation?
- What freshness policy applies to each fact class, and who owns its review?
- Which derived values, such as distance/open-now, must record input origin and clock?
