---
title: Hospital facility adapter (DEV-203)
status: approved
created: 2026-07-13
owner: 임수혁
tags: [facility, hospital, public-api, be-2]
---

# Hospital facility adapter (DEV-203)

## Context & problem

Before DEV-203, `GET /facilities?type=hospital` answered `501 NOT_IMPLEMENTED`. A user looking
for care therefore could not distinguish “no hospitals nearby” from “the service cannot
search yet.” The HIRA APIs provide the required data in two calls: a radius-bound list
lookup supplies a `ykiho`, and a per-hospital detail lookup supplies operating hours.

## Goals / non-goals

- **Goals:** Return normalized hospital `Facility` values from verified HIRA data; compute
  `isOpenNow` only from published schedules; preserve fixture/live provenance; cache the
  N+1 detail lookup by `ykiho`.
- **Non-goals:** Diagnosis, emergency triage changes, hospital detail UI, and acquiring holiday
  dates (DEV-208). When the existing `HolidayCalendar` identifies a holiday, this adapter will
  still honour HIRA's `noTrmtHoli` flag; it will not invent holiday dates itself. A missing schedule
  is never presented as closed.

## Requirements

- **FR-001 (DEV-203a):** The system MUST query
  `hospInfoServicev2/getHospBasisList` with `xPos` (longitude), `yPos` (latitude), a
  mandatory `radius` in metres, `numOfRows`/`pageNo`, and `_type=json`. It MUST follow the
  response's `totalCount` up to a hard cap of 20 pages / 2,000 rows; a dense radius must not silently
  return only HIRA's first page. The bounded cap protects one request from a malformed upstream count.
- **FR-002 (DEV-203a):** The list parser MUST read `XPos` as longitude and `YPos` as
  latitude, preserve `postNo` as text, and expose `ykiho`, name, address, phone, and facility
  type in a normalized raw value. HIRA's metre `distance` is a 39-digit decimal string, so the
  parser MUST use `PublicApiResponse.number()` / `double`, never integer parsing or exact-string
  comparison.
- **FR-003 (DEV-203b):** The system MUST request
  `MadmDtlInfoService2.8/getDtlInfo2.8?ykiho=...&_type=json` for official weekday/Saturday hours.
  Sunday’s missing fields plus `noTrmtSun: "휴진"` mean no Sunday schedule. It MUST parse
  `lunchWeek` in the provider's `HH:mm ~ HH:mm` form and treat that interval as closed rather than
  reporting a hospital open during lunch. It MUST preserve `emyDayYn`/`emyNgtYn` as nullable
  advisory emergency-availability flags (`Y` → true, `N` → false, otherwise null).
- **FR-004 (DEV-203c):** Hospital detail values MUST be cached by `ykiho` with a
  Redis-serializable representation. Cache values must round-trip through the configured
  JSON serializer.
- **FR-005 (DEV-203c):** The N+1 detail calls MUST use the existing `Parallel.map` pattern,
  preserving list order, with a bounded concurrency of **16**. The earlier value of 4 was inherited
  from `DrugService`, whose MFDS DUR endpoint throttles at four; HIRA's detail endpoint does not —
  measured against the live endpoint (100 calls) it scales almost linearly: concurrency 4 ≈ 2.4 req/s,
  8 ≈ 5.8, 16 ≈ 11.4, 32 ≈ 25 (2026-07-14). In-app, a cold `open_now=true` Seoul City Hall load went
  from a ~57 s median (worst 86 s) at concurrency 4 to ~17 s (worst 25 s) at 16, both settings
  alternated in one window. 16 rather than 32 is a manners bound on sustained sockets to a government
  API, not a measured ceiling.
  The HIRA list pages after the first MUST use the same bounded pattern: page 1 alone reports
  `totalCount`, the rest are independent, and HIRA does not sort by distance — so every page must be
  read. Reading the 8 pages of a 2 km Seoul City Hall radius one after another cost 23.9/35.0/25.0 s
  cold, against 9.9/10.0/10.2 s concurrently (measured live 2026-07-14, both builds alternated inside
  one window). HIRA's own latency drifts by more than these changes are worth, so any future
  comparison MUST measure both sides in the same window — a single timing proves nothing here.
- **FR-006 (DEV-203d):** `FacilityService.hospitals()` MUST replace `501` with filtered,
  distance-sorted `Facility` results using IDs in the
  `facility:hira:<base64url(ykiho)>` namespace. The encoded segment is required because raw HIRA
  identifiers can contain path-significant base64 characters.
- **FR-007 (DEV-203d):** If a hospital has no usable timetable, its `isOpenNow` MUST be
  `null` / `UNKNOWN`, never `false` / `CLOSED`.
- **FR-008 (DEV-203d):** When the existing `HolidayCalendar` says a date is a holiday and HIRA
  reports `noTrmtHoli: "휴진"`, the operation status MUST be closed for that holiday. Without a
  calendar match, the adapter MUST not infer that a date is a holiday.
- **FR-009 (DEV-203d):** Provenance MUST travel with each list/detail fetch. In hybrid fallback,
  a hospital card MUST be marked `fixture`; deriving provenance from the app-wide
  `isFixtureOnly()` switch is forbidden because it labels fallback data as live. A captured
  fixture detail may be used only for its known `ykiho`; an unmatched ID has unknown hours.
- **FR-010 (DEV-203e):** Fixture-mode tests MUST cover parsing, coordinate order, the 39-digit
  metre distance string, missing Sunday hours, lunch closure, holiday closure when the calendar
  identifies a holiday, provenance, bounded detail concurrency, page caps, public result limits,
  emergency flags, and the service-level result.
- **FR-011 (DEV-203e):** `GET /facilities` MUST accept `limit` from 1 through 50 (default 50) and
  return the nearest requested number of results. Hospital `open_now=false` bounds detail fetches by
  that limit. Hospital `open_now=true` instead checks the nearest eligible 100 candidates before
  returning the nearest requested open results; the cap protects the HIRA detail quota and latency.
  See spec 005 for the shared bounded-open-now policy.
- **FR-012 (DEV-203e):** The default acute-care hospital search MUST exclude only HIRA rows whose
  종별코드 `clCd` is `28` (요양병원), before detail fan-out. It MUST match the stable code, not the
  display label `clCdNm`, which HIRA can re-word without notice. Other and unknown codes remain
  visible; the code does not establish emergency-room availability.

## User scenarios

### Nearby hospital search (P0)

- **Given** a user requests hospitals near Seoul City Hall with a 1,000 m radius
- **When** the HIRA list fixture returns hospitals with HIRA metre distances
- **Then** up to the requested limit of non-nursing hospitals within 1,000 m appear, closest first,
  with `facility:hira:` IDs.

### Missing timetable (P0)

- **Given** a hospital list record has no usable detail timetable
- **When** the user requests hospital results
- **Then** the hospital is shown with `isOpenNow: null` and an unknown-hours message, not
  as closed.

### Lunch closure (P0)

- **Given** a hospital publishes `08:30–17:00` and `lunchWeek: "12:30 ~ 13:30"`
- **When** a user searches at 13:00 KST
- **Then** its operation is closed for lunch, not open.

### Fixture fallback (P0)

- **Given** fixture mode is active, or hybrid mode falls back after an upstream failure
- **When** hospital results are returned
- **Then** every card’s source is marked `fixture`, not `live`.

## Success criteria

- **SC-001:** `GET /facilities?type=hospital` no longer returns `501` in fixture mode.
- **SC-002:** A 1,000 m fixture query returns only HIRA rows at or below 1,000 metres.
- **SC-003:** A fixture hospital with `lunchWeek: "12:30 ~ 13:30"` is closed at 13:00 KST.
- **SC-004:** Every new parser and service branch is covered by `./gradlew test`.
- **SC-005:** The 30-second demo can show hospital pins from fixture data without an
  upstream network call.

## Open questions

- **Resolved:** `emyDayYn`/`emyNgtYn` are parsed into `HospitalDetail` and surfaced on `Facility`
  as nullable `emergencyDay`/`emergencyNight` (`Y`→true, `N`→false, else null). It is advisory only
  and never substitutes for `EmergencyTriage` or a 119 call (§2-4). Frontend rendering of the badge
  is a follow-up in FE-2's lane.

## Future expansion

If implementation grows beyond the DEV-203a~e WBS slices, add a `plan.md` for the
adapter/cache rollout and a `tasks.md` for independently reviewable commits.
