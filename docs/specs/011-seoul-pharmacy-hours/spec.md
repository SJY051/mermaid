---
title: Seoul pharmacy operating-hours augmentation
status: in-progress
created: 2026-07-16
owner: BE-2 (임수혁)
tags: [facility, pharmacy, seoul-open-data]
---

# Seoul pharmacy operating-hours augmentation

## Context & problem

HIRA is the primary pharmacy directory and NMC is its fallback. When the selected provider has no
verified weekly table, the service correctly returns `isOpenNow: null` rather than calling a pharmacy
closed. Seoul Open Data Plaza's `TbPharmacyOperateInfo` can improve coverage for Seoul pharmacies,
but it must not turn an ambiguous record into a guessed schedule.

On 2026-07-16, a live XML request to the official endpoint returned `RESULT.CODE=INFO-000`,
`list_total_count=5494`, and these relevant fields:

| Purpose | Verified fields |
|---|---|
| Stable join | `HPID` |
| Identity cross-check | `DUTYNAME`, `DUTYADDR`, `DUTYTEL1`, `WGS84LAT`, `WGS84LON` |
| Hours | `DUTYTIME1S` … `DUTYTIME8C` (strings; examples such as `0830` and `1900`) |
| Provider data update | `WORK_DTTM` (example format `yyyy-MM-dd HH:mm:ss.S`) |

The dataset page URL is not an API endpoint and returns HTML. The verified request shape is:

```text
http://openapi.seoul.go.kr:8088/{server-only-key}/xml/TbPharmacyOperateInfo/{start}/{end}
```

The key is server-only. It MUST NOT use a `VITE_` name, appear in source, fixtures, logs, exception
causes, URLs recorded in error output, or this document.

## Goals / non-goals

- **Goals:** Augment an NMC pharmacy's missing weekly table only when the Seoul row has the exact same
  `HPID`.
- **Goals:** Preserve `Hours unknown` when no exact, complete, parseable Seoul schedule exists.
- **Goals:** Carry Seoul provenance and the provider's schedule-update time honestly, while keeping
  upstream failure behaviour safe.
- **Non-goals:** Fuzzy joins by name, address, telephone, postal code, or coordinates.
- **Non-goals:** Augmenting a HIRA directory row, whose `ykiho` is not an NMC `HPID`, until a reviewed
  provider crosswalk exists.
- **Non-goals:** Inferring lunch breaks: the verified response has no lunch field.
- **Non-goals:** Presenting Seoul data as live NMC/HIRA data.

## Requirements

- **FR-001:** Configuration MUST use one server-only `SEOUL_PHARMACY_OPERATING_API_URL`. Its value is
  the full Seoul Open API service prefix, including the API key in the URL path and ending at
  `.../xml/TbPharmacyOperateInfo`; it is not the `data.seoul.go.kr` dataset page. The adapter appends
  only the pagination range (`/{start}/{end}`).
- **FR-002:** The adapter MUST request XML using the exact path order
  `{base}/{key}/xml/TbPharmacyOperateInfo/{start}/{end}` and validate the service `RESULT` before
  treating a response as data.
- **FR-003:** The adapter MUST paginate deliberately. The verified total is 5,494 rows, requiring six
  1,000-row pages. A live probe confirmed that neither a query-string nor a path `HPID` filter reduces
  the result set, so the adapter MUST fetch and cache the complete six-page table and perform the exact
  `HPID` lookup locally; it MUST NOT silently treat the first page as all Seoul pharmacies.
- **FR-004:** A Seoul row may augment only an NMC pharmacy with an exact `HPID` match. Identity fields
  are cross-check evidence and MUST NOT become an alternate join key.
- **FR-005:** `DUTYTIME1S` … `DUTYTIME8C` MUST be parsed as the same eight-day weekly-table convention
  used by the existing NMC adapter (Monday through Friday, Saturday, Sunday, holiday). Blank,
  malformed, or incomplete intervals are unavailable for that day, never guessed open or closed.
- **FR-006:** A valid Seoul table may produce `OFFICIAL_SCHEDULE`; no table, a failed request, an
  unmatched HPID, or an unparseable interval MUST leave `isOpenNow=null` / `UNKNOWN` rather than
  `false`.
- **FR-007:** Seoul data is used only as a supplement after the selected NMC record lacks a verified
  weekly table. Existing HIRA-primary/NMC-fallback directory selection remains unchanged.
- **FR-008:** The source for an augmented schedule MUST identify 서울특별시 열린데이터광장. `retrievedAt`
  remains the actual fetch time. Parse `WORK_DTTM` in Asia/Seoul and expose it separately as nullable
  `operation.scheduleUpdatedAt`; it MUST NOT be mislabelled as fetch time or `verifiedAt`.
- **FR-009:** API failures MUST preserve the existing facility result and unknown-hours behaviour. Logs,
  thrown exceptions, and throwable graphs MUST contain no API key, request URL, precise caller
  coordinates, or raw provider message.
- **FR-010:** Cache values MUST be JSON-serializable records and cache routes MUST keep live, fixture,
  hybrid fallback, and keyless paths from being represented as the wrong provenance. The full-table
  cache uses the existing six-hour TTL and refreshes lazily: one instance makes at most four six-page
  refreshes (24 requests) per day when it remains warm; it does not run a background refresh loop.

## User scenarios

### Exact Seoul HPID supplies missing NMC hours (P1)

- **Given** an NMC pharmacy without a weekly table and a complete Seoul row with the same `HPID`
- **When** a user searches nearby pharmacies in Seoul
- **Then** the service computes open status from that official Seoul table and identifies the source as
  Seoul Open Data Plaza.

### Ambiguous or incomplete row remains unknown (P0)

- **Given** a similarly named Seoul pharmacy whose `HPID` differs, or a matching row with missing or
  malformed time fields
- **When** a user searches nearby pharmacies
- **Then** it is not merged and the existing `Hours unknown` result is preserved.

### Seoul provider is unavailable (P0)

- **Given** the Seoul API times out or returns a non-success envelope
- **When** a user searches nearby pharmacies
- **Then** the directory result still renders, its schedule remains unknown when no other official
  table exists, and no request secret or location is logged.

## Success criteria

- **SC-001:** A verified live probe proves the endpoint, envelope, field names, string time format,
  and pagination behaviour before implementation.
- **SC-002:** Tests prove an exact `HPID` match augments a missing NMC table, while a name/address-only
  near-match cannot.
- **SC-003:** Tests prove blank, malformed, Sunday, holiday, and no-lunch data are never converted into
  an invented open/closed decision.
- **SC-004:** Tests prove a provider failure preserves unknown hours and does not retain key-, URI-,
  coordinate-, or raw-message-bearing causes/log throwables.
- **SC-005:** Tests prove the six-page full-table cache is reused and a local exact-HPID lookup cannot
  augment a different pharmacy.
- **SC-006:** Tests prove `WORK_DTTM` is exposed as `operation.scheduleUpdatedAt` in Korea time and is
  never substituted for `retrievedAt` or `verifiedAt`.
- **SC-007:** Redis serializer round-trip and provenance tests must pass. Full backend and frontend
  suites, plus a browser map/detail smoke test, remain PR gates because this change is not yet
  rendered.

## Operational note

- The provider's dataset-specific daily quota is not published in the verified material. The chosen
  six-hour lazy cache bounds one warm instance to 24 table-page requests per day; revisit that TTL if
  the provider publishes a stricter service quota or deployment scales horizontally.

- No Seoul operating-hours fixture is committed yet. Fixture mode therefore preserves its existing
  NMC outcome rather than inventing a Seoul schedule; capture a 2026 response before using this path
  in an offline demo.

## Future expansion

After a reviewed HIRA↔NMC crosswalk exists, consider whether exact provider identity can safely extend
this augmentation to HIRA-primary rows. Do not use fuzzy matching as a substitute.
