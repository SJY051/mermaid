---
title: Hospital facility adapter (DEV-203)
status: approved
created: 2026-07-13
owner: 임수혁
tags: [facility, hospital, public-api, be-2]
---

# Hospital facility adapter (DEV-203)

## Context & problem

`GET /facilities?type=hospital` currently answers `501 NOT_IMPLEMENTED`. A user looking
for care therefore cannot distinguish “no hospitals nearby” from “the service cannot
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
  mandatory `radius` in metres, and `_type=json`.
- **FR-002 (DEV-203a):** The list parser MUST read `XPos` as longitude and `YPos` as
  latitude, preserve `postNo` as text, and expose `ykiho`, name, address, phone, and facility
  type in a normalized raw value. HIRA's metre `distance` is a 39-digit decimal string, so the
  parser MUST use `PublicApiResponse.number()` / `double`, never integer parsing or exact-string
  comparison.
- **FR-003 (DEV-203b):** The system MUST request
  `MadmDtlInfoService2.8/getDtlInfo2.8?ykiho=...&_type=json` for official weekday/Saturday hours.
  Sunday’s missing fields plus `noTrmtSun: "휴진"` mean no Sunday schedule. It MUST parse
  `lunchWeek` in the provider's `HH:mm ~ HH:mm` form and treat that interval as closed rather than
  reporting a hospital open during lunch.
- **FR-004 (DEV-203c):** Hospital detail values MUST be cached by `ykiho` with a
  Redis-serializable representation. Cache values must round-trip through the configured
  JSON serializer.
- **FR-005 (DEV-203c):** The N+1 detail calls MUST use the existing `Parallel.map` pattern with
  a bounded concurrency of **4**, preserving list order. Four matches the measured upstream limit
  used by `DrugService` and avoids turning the first map load into an unbounded API fan-out.
- **FR-006 (DEV-203d):** `FacilityService.hospitals()` MUST replace `501` with filtered,
  distance-sorted `Facility` results using IDs in the `facility:hira:<ykiho>` namespace.
- **FR-007 (DEV-203d):** If a hospital has no usable timetable, its `isOpenNow` MUST be
  `null` / `UNKNOWN`, never `false` / `CLOSED`.
- **FR-008 (DEV-203d):** When the existing `HolidayCalendar` says a date is a holiday and HIRA
  reports `noTrmtHoli: "휴진"`, the operation status MUST be closed for that holiday. Without a
  calendar match, the adapter MUST not infer that a date is a holiday.
- **FR-009 (DEV-203d):** Provenance MUST travel with each list/detail fetch. In hybrid fallback,
  a hospital card MUST be marked `fixture`; deriving provenance from the app-wide
  `isFixtureOnly()` switch is forbidden because it labels fallback data as live.
- **FR-010 (DEV-203e):** Fixture-mode tests MUST cover parsing, coordinate order, the 39-digit
  metre distance string, missing Sunday hours, lunch closure, holiday closure when the calendar
  identifies a holiday, provenance, bounded detail concurrency, and the service-level result.

## User scenarios

### Nearby hospital search (P0)

- **Given** a user requests hospitals near Seoul City Hall with a 1,000 m radius
- **When** the HIRA list fixture returns hospitals with HIRA metre distances
- **Then** only hospitals within 1,000 m appear, closest first, with `facility:hira:` IDs.

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

- [NEEDS CLARIFICATION: Should emergency-night availability (`emyNgtYn`) be exposed now or
  retained only as future detail data?]

## Future expansion

If implementation grows beyond the DEV-203a~e WBS slices, add a `plan.md` for the
adapter/cache rollout and a `tasks.md` for independently reviewable commits.
