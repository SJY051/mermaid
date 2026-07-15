---
title: Korean public-holiday calendar (DEV-208)
status: approved
created: 2026-07-15
owner: BE-2 (임수혁)
tags: [facility, calendar, holiday, DEV-208]
---

# Korean public-holiday calendar (DEV-208)

## Context & problem

`HolidayCalendar.isHoliday()` currently always returns `false`. Both the pharmacy weekly timetable
and the HIRA hospital detail carry holiday-specific rules, so this labels a facility from weekday
hours on 설날·추석·법정공휴일. A person who is unwell can be sent to a closed facility.

The Korea Astronomy and Space Science Institute special-day API (data.go.kr 15012690) is the
official source. Its `getRestDeInfo` operation provides public-holiday dates for a solar year.

## Goals / non-goals

- **Goals:** Replace the hard-coded weekday result with verified Korean public-holiday dates.
- **Goals:** Cache the full date set under the existing six-hour cache TTL; never issue an upstream
  request per facility.
- **Goals:** Reuse the existing `HolidayCalendar` boundary so pharmacy holiday row 8 and HIRA
  `noTrmtHoli` use the same answer.
- **Goals:** Preserve fixture/hybrid development without presenting uncaptured calendar data as live.
- **Non-goals:** Lunar-date conversion, holiday-name UI, substitute-holiday policy authored by us,
  or modifying a provider's published hours.

## Requirements

- **FR-001:** `HolidayCalendar.isHoliday(LocalDate)` MUST resolve the calendar year containing the
  requested date and return true only when the official row identifies it as a public holiday.
- **FR-002:** The adapter MUST request `SpcdeInfoService/getRestDeInfo` with the decoded service key,
  `solYear`, `pageNo`, and a page size that covers the year. It MUST use the provider's verified
  response format; do not assume JSON if the captured response is XML.
- **FR-003:** The parsed year value MUST contain only `LocalDate` values whose `isHoliday` flag is
  `Y`; anniversaries and non-holiday special days are not public holidays.
- **FR-004:** Calendar results MUST be cached by year. A facility search for many rows MUST not
  multiply calendar calls.
- **FR-005:** Fixture mode MUST use the captured 2026 official calendar response. In configured
  hybrid/live mode, a calendar lookup failure MUST fail rather than combine a fixture calendar
  decision with a live facility card whose source cannot represent both origins.
- **FR-006:** A valid official holiday date MUST make pharmacy operation use weekly-hours holiday row
  8, and make a HIRA row with `noTrmtHoli=휴진` closed. A normal weekday must retain current behaviour.
- **FR-007:** URI construction MUST encode the decoding service key exactly once and MUST NOT log
  request URIs or upstream exception text that could contain it.

## User scenarios

### Holiday pharmacy search (P1)

- **Given** a captured public-holiday date and a pharmacy with different weekday and holiday hours
- **When** a user searches on that date in KST
- **Then** the card uses the holiday schedule rather than weekday hours.

### Holiday hospital closure (P1)

- **Given** an official public-holiday date and a HIRA hospital marked `noTrmtHoli=휴진`
- **When** a user searches nearby hospitals
- **Then** that hospital is closed for that date; the same record remains governed by its normal
  timetable on a non-holiday.

## Success criteria

- **SC-001:** Fixture tests prove that an official `isHoliday=Y` date returns true and an `N` date
  returns false.
- **SC-002:** Replacing the holiday predicate with `false` turns both the pharmacy holiday-row and
  hospital holiday-closure tests red.
- **SC-003:** Repeated date checks in one year use the same cached calendar result.
- **SC-004:** `cd backend && ./gradlew test` exits 0.

## Decisions

- The approved API is parsed as XML; do not send a JSON format parameter.
- The original target was one upstream lookup per calendar year. The shared cache expires after six
  hours, so the implemented policy refreshes the complete annual set at most once per TTL instead.
  This keeps late-declared temporary holidays observable without allowing a facility row to trigger
  its own lookup.
- Fixture mode ships the captured 2026 calendar only. A request for another fixture year fails
  loudly rather than treating unknown future holidays as weekdays.
- A calendar lookup failure remains an error even after an earlier successful lookup. The cache's
  six-hour TTL is the accepted freshness bound; an unbounded in-memory fallback could miss a
  late-declared temporary holiday and turn uncertainty into an incorrect opening decision.

## Future expansion

If the adapter grows beyond a year cache and a single operation, add `plan.md` and `tasks.md` for
calendar refresh/expiry policy and any user-facing holiday explanation.
