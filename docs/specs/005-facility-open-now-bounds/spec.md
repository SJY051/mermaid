---
title: Bounded open-now facility search
status: implemented
created: 2026-07-14
owner: 임수혁
tags: [facility, DEV-202, DEV-203, performance]
---

# Bounded open-now facility search

## Context & problem

The prior pharmacy path inspected every in-radius timetable before applying its returned-result
`limit`. That preserved distant open pharmacies but made a cold request sequential and expensive.
Moving `limit` ahead of the timetable lookup would reduce that fan-out but wrongly hide a farther
open pharmacy. The two roles must therefore be separated. HIRA hospital details are also an N+1
fan-out, but are substantially more expensive and already have a bounded nearest-result policy.

## Goals / non-goals

- **Goals:** Find a wider bounded set of open pharmacies; bound both upstream fan-outs; preserve
  `isOpenNow: null` as unknown; state the intentional hospital trade-off in the contract.
- **Non-goals:** Promise every open facility in an arbitrary radius; add pharmacy list pagination;
  change HIRA hospital candidate selection; implement frontend loading or empty-state copy.

## Requirements

- **FR-001:** For pharmacy requests with `open_now=false`, the service MUST rank by distance and
  inspect weekly timetables only for the nearest requested `limit` candidates.
- **FR-002:** For pharmacy requests with `open_now=true`, the service MUST inspect a distance-ranked
  candidate set wider than `limit`, capped at the rows supplied by the pharmacy location API, then
  return the nearest requested `limit` candidates whose operation is known to be open.
- **FR-003:** Pharmacy weekly-timetable fetches MUST use bounded concurrency. A malformed or
  unavailable timetable MUST still produce the existing unknown/inferred status rules; it must not
  be guessed open or closed.
- **FR-004:** Hospital requests MUST keep their existing nearest-`limit` detail-fetch bound before
  `open_now` filtering. This is an intentional HIRA quota and latency trade-off, not an exhaustive
  hospital-open search.
- **FR-005:** The API/spec documentation MUST distinguish returned-result `limit` from the bounded
  pharmacy candidate set and describe the hospital asymmetry.

## User scenarios

### Distant open pharmacy is discoverable (P1)

- **Given** the closest requested pharmacy candidates are closed and a farther pharmacy inside the
  returned location rows is open
- **When** a user requests pharmacies with `open_now=true`
- **Then** the farther open pharmacy is eligible for the returned nearest-open results.

### Ordinary map load stays bounded (P1)

- **Given** a user requests pharmacies with `open_now=false` and `limit=10`
- **When** more than ten location rows are available
- **Then** at most ten weekly-timetable calls are made and at most ten pins are returned.

### Hospital detail budget remains bounded (P1)

- **Given** a dense HIRA radius and `limit=10`
- **When** a user requests hospitals, including with `open_now=true`
- **Then** at most ten eligible hospital detail calls are made before filtering.

## Success criteria

- **SC-001:** A regression test proves an open pharmacy beyond the first requested `limit` can be
  returned by `open_now=true`.
- **SC-002:** A regression test proves pharmacy `open_now=false` does not fetch more timetables than
  its requested `limit`.
- **SC-003:** A regression test proves pharmacy timetable concurrency is bounded.
- **SC-004:** Existing hospital limit-before-detail tests remain green and the documented contract
  explicitly names the non-exhaustive `open_now=true` hospital behavior.
- **SC-005:** `cd backend && ./gradlew test` passes.

## Open questions

- None for this backend slice. FE-2 should separately decide the loading and honest empty-state
  copy because those components own the map UI.

## Future expansion

If product requirements need exhaustive pharmacy coverage beyond the first 100 location rows, add
pharmacy list pagination and revisit the upstream quota budget in a separate spec.
