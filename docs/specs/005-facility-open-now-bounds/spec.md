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
open facility. The two roles must therefore be separated. HIRA hospital details are also an N+1
fan-out, so their wider open-now candidate set must have a fixed safety cap.

## Goals / non-goals

- **Goals:** Find a wider bounded set of open pharmacies and hospitals; bound both upstream
  fan-outs; preserve `isOpenNow: null` as unknown; distinguish the returned-result limit from the
  inspected candidate set.
- **Non-goals:** Promise every open facility in an arbitrary radius; add pharmacy list pagination;
  remove HIRA's bounded detail fan-out; implement frontend loading or empty-state copy.

## Requirements

- **FR-001:** For pharmacy requests with `open_now=false`, the service MUST rank by distance and
  inspect weekly timetables only for the nearest requested `limit` candidates.
- **FR-002:** For pharmacy requests with `open_now=true`, the service MUST inspect a distance-ranked
  candidate set wider than `limit`, capped at the rows supplied by the pharmacy location API, then
  return the nearest requested `limit` candidates whose operation is known to be open.
- **FR-003:** Pharmacy weekly-timetable fetches MUST use bounded concurrency. A malformed or
  unavailable timetable MUST still produce the existing unknown/inferred status rules; it must not
  be guessed open or closed.
- **FR-004:** For hospital requests with `open_now=false`, the service MUST inspect details only for
  the nearest requested `limit` candidates. With `open_now=true`, it MUST inspect a distance-ranked,
  fixed maximum of 100 eligible candidates before returning the nearest requested `limit` whose
  operation is known to be open. Detail calls MUST remain bounded-concurrent.
- **FR-005:** The API/spec documentation MUST distinguish returned-result `limit` from the bounded
  candidate set for both facility types.

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

### Farther open hospital is discoverable within the safety cap (P1)

- **Given** the nearest requested hospital candidates are closed and a farther eligible candidate
  within the first 100 is open
- **When** a user requests hospitals with `open_now=true`
- **Then** that farther hospital is eligible for the returned nearest-open results, and no more than
  100 detail calls are made.

## Success criteria

- **SC-001:** A regression test proves an open pharmacy beyond the first requested `limit` can be
  returned by `open_now=true`.
- **SC-002:** A regression test proves pharmacy `open_now=false` does not fetch more timetables than
  its requested `limit`.
- **SC-003:** A regression test proves pharmacy timetable concurrency is bounded.
- **SC-004:** A regression test proves an open hospital beyond the first requested `limit` can be
  returned by `open_now=true`, while the detail fan-out never exceeds 100 candidates.
- **SC-005:** `cd backend && ./gradlew test` passes.

## Open questions

- None for this backend slice. FE-2 should separately decide the loading and honest empty-state
  copy because those components own the map UI.

## Future expansion

If product requirements need exhaustive pharmacy coverage beyond the first 100 location rows, add
pharmacy list pagination and revisit the upstream quota budget in a separate spec.
