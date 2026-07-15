---
title: Emergency room nearby search
status: approved
created: 2026-07-15
owner: BE-2 (임수혁)
tags: [facility, emergency, nmc]
---

# Emergency room nearby search

## Context & problem

An English-speaking user who needs urgent care must be able to find nearby emergency rooms separately from ordinary hospitals. The National Medical Center (NMC) location operation supplies official emergency-facility records, including a stable HPID and coordinates.

## Goals / non-goals

- **Goals:** Return nearby NMC emergency-room facilities from `GET /facilities?type=emergency_room`.
- **Goals:** Preserve per-fetch live/fixture provenance and calculate a caller-specific distance in metres from official coordinates.
- **Goals:** Keep `isOpenNow` unknown rather than treating a location record or bed count as an opening-hours schedule.
- **Non-goals:** Real-time bed availability (`hvec`) and its frontend contract. This requires a separately reviewed field and user-facing freshness copy.
- **Non-goals:** Inferring emergency-room hours, triage, or clinical advice.

## Requirements

- **FR-001:** `FacilityType` MUST accept and serialize `emergency_room`.
- **FR-002:** The NMC `getEgytLcinfoInqire` request MUST send `_type=json`, `WGS84_LON`, and `WGS84_LAT` with the decoding service key.
- **FR-003:** A valid NMC row MUST become an `emergency_room` facility with id `facility:nmc-emergency:<hpid>` and a distance in metres, recalculated from the caller's coordinates.
- **FR-003a:** The NMC list request MUST be centred on its ~100 m cache-grid cell, so all callers sharing a cache entry receive a stable provider list. NMC has no radius parameter, so no grid-margin expansion applies.
- **FR-004:** Rows without HPID or finite coordinates MUST be excluded; no id or map position is invented.
- **FR-005:** Every returned emergency facility MUST have `operation.isOpenNow == null` and `UNKNOWN` confidence. NMC location data is not an operating-hours source.
- **FR-006:** Fixture and hybrid-fallback records MUST be marked fixture in `source`.
- **FR-007:** `open_now=true` MUST return no emergency-room rows because their current opening status is unknown, not closed.

## User scenarios

### Nearby emergency rooms (P1)

- **Given** an emergency-room NMC response near Seoul City Hall
- **When** the client requests `type=emergency_room`
- **Then** it receives only rows within the requested radius, sorted nearest first, with a distance shown in metres and hours marked unknown.

## Success criteria

- **SC-001:** Fixture mode returns NMC emergency facilities as `emergency_room` with a caller-specific distance in metres.
- **SC-002:** A row beyond the requested radius is not returned.
- **SC-003:** The test suite proves that changing unknown operation to closed or making a shared-list distance independent of the caller makes a test fail.

## Open questions

- [NEEDS CLARIFICATION: When capacity is introduced, should the UI show only `hvec`, or other NMC capacity fields too?]

## Future expansion

Add a bounded, cached HPID join to `getEmrrmRltmUsefulSckbdInfoInqire` only after the frontend capacity contract and stale-data copy are approved.
