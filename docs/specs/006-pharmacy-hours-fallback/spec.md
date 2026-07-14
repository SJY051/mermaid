---
title: Pharmacy weekly-hours failure fallback
status: draft
created: 2026-07-14
owner: 임수혁
tags: [facility, issue-69, resilience]
---

# Pharmacy weekly-hours failure fallback

## Context & problem

`FacilityService.pharmacies()` first fetches the pharmacy directory (location, name, address,
distance) from one `getParmacyLcinfoInqire` call, then — in `toFacility()`, once per in-radius row —
calls `PharmacyApiClient.weeklyHours(hpid)` (`getParmacyBassInfoInqire`) for the official timetable.
That per-pharmacy detail call is an N+1 issued sequentially.

In live-only mode `weeklyHours()` rethrows any upstream failure as `PublicApiException`. That
exception escapes `toFacility()` → `pharmacies()`, and `GlobalExceptionHandler` maps it via
`ErrorCode.SOURCE_UNAVAILABLE` to **503**. So a single failed timetable lookup discards the *entire*
map response — including the pins whose location, name, address, and distance the directory call had
already returned successfully. The user reads "no pharmacies nearby" or "location is broken" when the
directory data was fine.

The directory row is the required fact; the weekly timetable is optional enrichment. Losing the
optional part must not delete the required part. This is the same class of harm as §2-3: not knowing
a facility's hours must never hide a pharmacy that exists.

**Live contract clarification.** `live` means "do not substitute fixtures", not "every optional
sub-call must also succeed". A failed *directory* fetch still returns 503 (no list can be built); a
failed *timetable* fetch degrades that one row and the list still returns 200.

## Goals / non-goals

- **Goals:** Preserve every successful directory row when a single optional timetable lookup fails;
  keep the affected card's operating status honest (`INFERRED` or `UNKNOWN`, never `CLOSED`); keep the
  card's directory provenance `LIVE` since the list fact did come from the live API; preserve upstream
  failure visibility in logs.
- **Non-goals:**
  - Treat unknown as closed, or render `isOpenNow: null` as "Closed" (§2-3).
  - Change how a *directory-fetch* failure is handled — it stays 503.
  - Catch the failure *inside* the cached `weeklyHours()` and return an empty table as a normal
    success: that would let a transient upstream failure be cached as a real "no hours" answer for
    the cache's lifetime. The catch belongs at the aggregation boundary, outside the cache.
  - Broad `catch (Exception)`. Only the typed `PublicApiException` is a partial failure; anything
    else is a programming error and must keep propagating.
  - Add timeout, bounded concurrency, or the front-end "keep last-good markers on refetch" defense —
    see Future expansion. This spec fixes correctness only.

## Requirements

- **FR-001:** `FacilityService` MUST catch `PublicApiException` thrown by an individual
  `weeklyHours(hpid)` lookup at the per-pharmacy conversion boundary (`toFacility()`) and continue
  assembling the rest of the directory result. The catch MUST be outside the cached method so a
  transient failure is never cached as an empty timetable.
- **FR-002:** On such a failure the affected row MUST fall through to the existing `operationOf`
  fallback with an empty `DutyTable`: `INFERRED` when the directory row has usable `startTime` and
  `endTime`, otherwise `UNKNOWN` with `isOpenNow: null`. The row's `SourceRef` provenance MUST stay
  `LIVE` (the list fact came from the live API; only the hours are unknown).
- **FR-003:** Other directory rows MUST remain in the response, and the facilities endpoint MUST
  return 200 — an individual timetable failure MUST NOT surface as 503.
- **FR-004:** Exceptions other than `PublicApiException` MUST keep propagating, so an internal defect
  is never silently converted into an hours-unknown card.

## User scenarios

### One of two timetable lookups fails (P1)

- **Given** two in-radius pharmacy directory rows, A and B, where `weeklyHours(A)` throws
  `PublicApiException` and `weeklyHours(B)` returns a valid weekly table
- **When** the user opens the pharmacy map with `open_now=false`
- **Then** the response is 200 and contains both A and B; B is `OPEN`/`CLOSED` with
  `OFFICIAL_SCHEDULE`; A is `INFERRED` if its directory row has usable `startTime`/`endTime`, else
  `UNKNOWN` with `isOpenNow: null`; A is never rendered as `CLOSED`.

## Success criteria

- **SC-001:** A regression test with two in-radius pharmacies (A fails in `weeklyHours()`, B
  succeeds) proves the response is 200 and retains **both** rows — the failure discards neither A nor
  B — and that B was actually processed to `OFFICIAL_SCHEDULE`.
- **SC-002:** The same test proves the failed row A is `isOpenNow: null` / `UNKNOWN` when its
  directory row has no usable times, is never `CLOSED`, and is not dropped by the `open_now=false`
  filter.
- **SC-003:** The test is a real guard, not decoration: removing the FR-001 catch (or making it
  rethrow) MUST turn it red. State this mutation in the test so a future reader can confirm it.
- **SC-004:** `cd backend && ./gradlew test` passes.
- **SC-005:** With a live fault injected into a single detail call, a real browser still shows the
  other pharmacy's marker and an `Hours unknown` card for the failed one — the map is not emptied
  (repo §4: a browser-rendered change is verified in a browser).

## Open questions

- None. The existing `open_now=true` rule continues to exclude both `false` and `unknown` statuses;
  this issue only preserves the normal map response and its honest card state.

## Future expansion

Correctness (this spec) comes first; the following are separate follow-ups, in order, and none of
them alone fixes #69:

1. **Per-detail timeout** on each `weeklyHours()` call — measured, not guessed. Without item-level
   isolation this only turns a slow full-503 into a fast full-503.
2. **Bounded concurrency** for the N+1 detail fan-out, respecting the 1,000/day quota (see the
   `TODO(BE-2)` in `FacilityService.pharmacies()`). Parallelizing without per-item failure isolation
   reproduces #69 faster.
3. **Result cap / distance pre-selection / observability** — a bounded metric keyed by operation and
   provider status, logging neither service keys nor user coordinates.
4. **Front-end secondary defense:** do not clear the last-good markers the instant a refetch starts.
   Useful, but the server discarding an already-assembled list is the primary bug and is fixed here.

If per-day cache reuse were disabled, cold-cache refreshes would re-issue every detail call and make
this failure more frequent — another reason the 200-with-partial-degradation guarantee must hold
independent of cache state.
