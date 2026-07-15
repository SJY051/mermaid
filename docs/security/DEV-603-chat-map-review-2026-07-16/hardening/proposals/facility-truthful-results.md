# Security Hardening Proposal: Truthful facility result algebra

## Decision

Decide whether facility availability continues to be encoded as lists, exceptions, booleans, nulls, and UI timing conventions, or whether the backend and frontend share an exhaustive result model for complete, partial, verified-empty, unavailable, and unsupported searches while preserving tri-state hours.

## Executive Recommendation

- **Option 1: Harden existing branches.** Add loading state, preserve unknown hours, validate envelopes/numerics, catch detail failures per row, and bound fan-out/deadlines locally.
- **Option 2: Typed facility result algebra.** Make search outcome and row certainty explicit in the API; pair it with one orchestrator-owned request/quota/deadline budget.

I recommend Option 2 with product/accessibility and operations review. Option 1 remains the fastest direct response to P0/P1 findings and must not wait for the contract redesign.

## Evidence

| Evidence | Finding or audit | What it establishes |
| --- | --- | --- |
| `R01-CAN-037` | [Pending work renders as completed no-results](../../artifacts/central_validation_round06/batch-01/validation.jsonl) | **Observed:** `NearbyFacilities` can claim absence before lookup completes. |
| `R01-CAN-094/095` | [Malformed hospital/pharmacy hours collapse to closed](../../artifacts/central_validation_round06/batch-03/validation.jsonl) | **Observed:** unknown schedule facts become negative care guidance, violating §2-3. |
| `R03-CAN-001` | [One detail failure discards all nearby hospitals](../../artifacts/central_validation_round06/batch-04/validation.jsonl) | **Observed:** partial upstream failure becomes whole-response unavailability. |
| `R04-CAN-011` | [Open-now flow discards unknown-hours facilities](../../artifacts/central_validation_round06/batch-04/validation.jsonl) | **Observed:** potentially open facilities disappear rather than render “Hours unknown.” |
| `R04-CAN-013` | [Malformed successful envelopes become empty success](../../artifacts/central_validation_round06/batch-04/validation.jsonl) | **Observed:** provider/schema failure can be presented as no results. |
| `R01-CAN-082/084` | [Pharmacy and hospital fan-out can exhaust constrained upstream capacity](../../artifacts/central_validation_round06/batch-03/validation.jsonl) | **Observed:** cold requests multiply into scarce list/detail calls. |
| `EV-B` | [Track B final map/facility audit](../../artifacts/finalization/track_b_final_audit.md) | **Observed:** Naver rendered correctly; stale fixture backend returned 500 before provider dispatch; clean fixture/live controls returned rows; ER is explicitly unsupported; main map distinguishes error/empty while assistant surface has transient false-empty. |

**Inferred:** list contents alone cannot carry whether lookup completed, which providers failed, whether a row has unknown hours, or whether a type is unsupported. Each caller reconstructs those facts differently, so availability and truthfulness drift together.

## Current Design And Failure Mode

The service fans out from a query to list and detail adapters, parses coordinates and schedules, filters/ranks/caps results, and returns either rows or an exception. Some detail failures degrade a row; others collapse the whole list. Some malformed success envelopes become empty. The UI uses local loading/error/list conditions, and the assistant-opened component briefly interprets “zero rows so far” as “no facilities found.”

This is a security issue because a person may choose whether and where to seek care. Empty means the lookup completed and found none; unavailable means we could not look; partial means some verified places remain; unknown hours means call to confirm; unsupported means the product has no adapter. Those are distinct facts, not copy variants.

## Desired Invariants

- No-results is emitted only after every required search operation completed successfully and the verified result set is empty.
- Provider/schema/runtime failure cannot be serialized as verified empty.
- `isOpenNow` remains `open`, `closed`, or `unknown`; malformed/missing hours never become closed or disappear solely because open-now was requested.
- Partial detail failure preserves valid list facts and identifies degraded fields without inventing them.
- Unsupported facility types are explicit and cannot look like empty coverage.
- One orchestrator owns request deadline, bounded concurrency, call budget, cache policy, and cancellation for the whole fan-out.
- UI rendering is exhaustive over loading, complete, partial, verified-empty, unavailable, and unsupported states.

## Constraints And Non-Goals

Government quota and latency remain external constraints. The design does not promise emergency-room search before an adapter exists. It must preserve successful rows during partial failure without presenting stale/fixture facts as live. Product/accessibility owners approve state copy; operations approves budgets and fallback policy.

## Before Architecture

[Current facility result flow](../diagrams/facility-truthful-results-before.mmd)

The before view compresses heterogeneous provider and schedule outcomes into a row list or error before the UI decides what “zero” means.

## Options

### Option 1: Harden existing branches

The focused option adds explicit loading to `NearbyFacilities`, validates envelopes and coordinate ranges, maps malformed schedules to unknown, retains unknown-hours rows, catches detail failures per facility, and applies endpoint-specific timeouts/concurrency/call caps. It directly restores truthful behavior for known cases with limited public-contract change.

The residual problem is convention. A `List<Facility>` still cannot prove whether it is complete or partial, and every UI must combine `loading`, `error`, and rows in the right order. New adapters can repeat all-or-nothing mapping and malformed-empty behavior.

[Option 1 after view](../diagrams/facility-truthful-results-local-guards-after.mmd)

| Change | Before | After | Security consequence | Cost |
| --- | --- | --- | --- | --- |
| Pending state | Zero rows may render empty | Explicit loading guard | Removes transient false absence | Frontend state/tests |
| Schedule parse | Malformed may become closed | Tri-state unknown | Preserves §2-3 | Per-adapter parsing changes |
| Detail failure | Can collapse all rows | Per-row degradation | Preserves available care facts | Partial-warning representation |
| Fan-out | Distributed caps/timeouts | Local bounded calls | Reduces quota/thread exhaustion | Possible fewer enriched rows |

Performance should improve under failure and abuse through bounded fan-out; successful cold paths may return less enrichment. Memory is neutral. Reliability improves locally, but behavior can still diverge across pharmacy, hospital, chat action, and map paths. Rollback is focused, though reverting truthful unknown/partial behavior would not be acceptable once public copy depends on it.

### Option 2: Typed facility result algebra

The structural option returns a result such as `Complete(rows)`, `Partial(rows, degradedFields, providerFailures)`, `VerifiedEmpty`, `Unavailable(reason)`, or `Unsupported(type)`. Each facility carries tri-state hours and a provenanced evidence envelope. One orchestrator supplies a deadline and call/quota budget to list/detail adapters, applies backpressure/cancellation, and decides whether the overall result is complete or partial.

The benefit is exhaustiveness across the trust boundary. The frontend cannot render a bare list without handling outcome, and the backend cannot call a malformed envelope empty without constructing `VerifiedEmpty` under stricter proof. Unknown hours remain row truth rather than a filter failure. Chat-triggered facility actions and the main map can share the same state semantics.

The public contract becomes richer, which adds migration and accessibility-copy work. Partial results can confuse users if the warning dominates or is too subtle, so product testing matters as much as the enum. The orchestrator's budget may reduce maximum coverage; operations must set it from quota/latency evidence rather than optimism. Memory grows slightly with outcome metadata. Rollback can adapt the new result to the old UI only if the adapter maps `Partial` and `Unavailable` honestly—never to empty.

[Option 2 after view](../diagrams/facility-truthful-results-result-algebra-after.mmd)

| Change | Before | After | Security consequence | Cost |
| --- | --- | --- | --- | --- |
| Search outcome | Rows or exception | Exhaustive typed outcome | Empty/unavailable/unsupported cannot collapse | API/DTO migration |
| Row certainty | Nullable/derived fields | Tri-state hours plus degraded-field metadata | Preserves unknown and partial truth | Richer UI/card model |
| Fan-out authority | Per-client/service behavior | Orchestrator request budget | Contains quota and stall blast radius | Budget policy/telemetry |
| UI | Timing conventions | Exhaustive result rendering | Prevents transient/settled false claims | Product/accessibility review |

## Comparison

| Dimension | Option 1: branch hardening | Option 2: result algebra |
| --- | --- | --- |
| Security | Addresses known false states | Makes false state collapse harder by construction |
| Performance | Bounded fan-out can improve tail behavior | Same, with small outcome-encoding cost |
| Memory | Neutral | Small metadata overhead |
| Reliability | More partial degradation, local divergence remains | Uniform partial/unavailable semantics; orchestrator is critical path |
| Operability | Endpoint-specific metrics | Outcome and budget reason metrics across surfaces |
| Migration | Focused FE/BE fixes | Versioned API and exhaustive UI migration |
| Rollback | Revert local changes | Adapter must preserve non-empty truth semantics |

## Recommendation

I recommend Option 2 because the service's job is not merely to fetch rows; it is to tell the truth about whether and how it looked. Option 1 remains proportionate for immediate bug fixes and should provide the test corpus for the eventual algebra.

## Evidence Coverage And Residual Risk

| Evidence | Option 1 | Option 2 | Tactical fix still required? |
| --- | --- | --- | --- |
| `R01-CAN-037` — pending false empty | Addresses | Addresses through exhaustive loading/outcome state | Yes |
| `R01-CAN-094/095`, `R04-CAN-011` — unknown becomes closed/dropped | Addresses parsers/filters | Addresses via tri-state row contract | Yes |
| `R03-CAN-001` — detail failure collapses list | Addresses catch boundary | Addresses via `Partial` | Yes |
| `R04-CAN-013` — malformed envelope becomes empty | Addresses adapter parser | Addresses by strict `VerifiedEmpty` construction | Yes |
| `R01-CAN-082/084` — quota fan-out | Mitigates with caps | Mitigates through orchestrator budget | Yes |
| Track B stale JVM | Unaffected except honest error | Unaffected; demo artifact proposal owns prevention | Separate runtime control required |

Residual risk includes incomplete upstream coverage, stale but valid data, holiday semantics, provider outages, inaccessible copy, and budgets that omit a real facility beyond the inspection cap.

## Migration And Rollout

Direct fixes for unknown/empty/partial states remain required during any contract transition. A compatibility adapter may exist only if it preserves all result distinctions. Detailed sequencing is deferred.

## Validation Plan

- Exercise complete, verified-empty, list failure, one-detail failure, all-detail failure, malformed envelope, unsupported type, timeout, cancellation, and quota-budget exhaustion.
- Mutation-test every conversion from unknown to closed/empty and every branch that drops a row.
- Browser-test main map and assistant surfaces for loading, partial, empty, unavailable, unknown hours, and unsupported copy with accessibility review.
- Measure call count, quota use, time to first row, completion latency, and retained rows under representative pharmacy/hospital workloads.
- Verify source/data-mode/freshness remain truthful for every partial row.

## Implementation Work Packages

Intentionally omitted pending option selection and product/operations approval.

## Open Questions

- Which provider failures permit partial results, and which invalidate the entire search?
- What call/deadline budget preserves useful coverage within the pharmacy quota?
- How should partial results and unknown hours be worded for an English-speaking user making a care decision?
- Should unsupported ER search be a distinct API outcome or rejected request contract?
