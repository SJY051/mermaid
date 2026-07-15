# Track B final evidence audit

- Immutable source target: `654f906e00e81648d1482210b6a9171747dddd75`
- Exact-origin browser capture: `http://localhost:5173`, 2026-07-16 00:04 KST
- Browser-served checkout at capture time: `f68cc39948bdb11139af07017336e45ef07e2325`
- Browser backend: Vite proxy target `localhost:8080`, pre-existing PID 8415, `DATA_MODE=fixture`
- Audit result: **the Naver key was accepted and the map rendered; pharmacy and hospital pins were absent because both facility requests returned HTTP 500 from a stale JVM. ER is a separate, expected unsupported backend type.**

## Evidence attribution

The browser capture did not execute the immutable target byte-for-byte: the Vite page was at `f68cc39948bdb11139af07017336e45ef07e2325`. A target-to-browser-revision diff over the Track B paths changes only `MapScreen.tsx` presentation and adds a disabled ER control; `useNaverMap.ts`, `facilities.ts`, `NearbyFacilities.tsx`, and the backend facility/common paths are unchanged. Source line citations below use the immutable target. Runtime status/request IDs use the capture revision and running processes and are labelled separately.

The exact browser/network/console capture is `diagnostic_drafts/evidence/naver_5173_console_network_20260716.md` (SHA-256 `cbab96008330ef853f46405324bc6115cc90968f7a3e72b05dd5aac87c7d468c`). The screenshot `/private/tmp/mermaid-map.png` (SHA-256 `126bff27a484d1481b54efc1fc62a0c47afdea582077ce38c7f14e0274c1a4c1`) visibly contains Naver tiles, `© NAVER Corp.`, no facility pins, and the backend error text.

## 1. Naver load verdict at exact `http://localhost:5173`

**Verdict: accepted and rendered. It was not key-rejected, never-appended, or late-callback.**

The capture proves all of the following together:

- `GET https://oapi.map.naver.com/openapi/v3/maps.js?ncpKeyId=<public-id>&language=en` returned HTTP 200. The parameter is the required `ncpKeyId`, not `ncpClientId`.
- The SDK auth request for `url=http://localhost:5173/` returned HTTP 200.
- `navermap_authFailure` was a function, the passive wrapper was installed before Map-tab activation/script append, and no auth-failure event fired. `nav__authFailure` remained undefined and did not fire.
- `window.naver.maps` was defined; `mapError` was null; `mapLoading` was false; the canvas had four children.
- Naver base/terrain/satellite styles and multiple base tiles returned HTTP 200, and the rendered screenshot contains `© NAVER Corp.`.
- The only two console errors were the facility HTTP 500 responses. There was no Naver request failure and no page exception.

The auth endpoint's 200 is not, by itself, sufficient because a bad key can still load `maps.js`. The decisive evidence is the combination of **no auth-failure callback plus successful style/tile loads plus a visibly rendered map**.

The immutable source has the correct ordering: global callback assignment at `frontend/src/hooks/useNaverMap.ts:48-53`; `ncpKeyId` script creation/append at `:69-85`; per-hook auth listener installation before `loadNaverMapsScript()` at `:110-121`; map construction and `tilesloaded` gating at `:123-135`. Thus the callback was not registered late. The reported `:5174` allowlist gap is irrelevant to this exact `:5173` observation.

## 2. What the `:5173` browser actually received for facilities

Both browser requests were non-2xx; neither was `200 []`:

| Browser request | HTTP | `X-Request-Id` / body `request_id` | Exact response body |
|---|---:|---|---|
| `/api/v1/facilities?...&type=pharmacy` | 500 | `33bcc094-7d03-4f32-9826-e329efc9dc8a` | `{"error":{"code":"INTERNAL_ERROR","message":"Something went wrong on our side.","retryable":false,"request_id":"33bcc094-7d03-4f32-9826-e329efc9dc8a"}}` |
| `/api/v1/facilities?...&type=hospital` | 500 | `13f9d64a-ba4e-4a32-8a34-aba78a162ba9` | `{"error":{"code":"INTERNAL_ERROR","message":"Something went wrong on our side.","retryable":false,"request_id":"13f9d64a-ba4e-4a32-8a34-aba78a162ba9"}}` |

Vite's immutable proxy configuration points `/api` to `http://localhost:8080` (`frontend/vite.config.ts:53-64`). PID 8415 was still listening there; its environment says `DATA_MODE=fixture`, and its cwd/classpath point into the now-missing `.claude/worktrees/nifty-bohr-af339a/backend/build/{classes,resources}` paths.

Because that runtime was in fixture mode, **there was no pharmacy list call, hospital list call, detail call, government HTTP status, or quota consumption on this failure path**. The runtime failed before a provider could return rows. A same-runtime direct repeat, correlated by JFR timestamp, proves the working exception:

```text
NoClassDefFoundError: com/mermaid/facility/FacilityService$1
  at FacilityService.findNearby(FacilityService.java:44)   # stale loaded class
  at FacilityController.nearby(FacilityController.java:39)
Caused by: ClassNotFoundException: com.mermaid.facility.FacilityService$1
```

The repeat response IDs were pharmacy `7a987d59-5c05-42b9-a294-322691297af3` and hospital `e2fe6a75-6176-497e-8d55-530578db4823`; `/private/tmp/old8080-facility-exceptions.txt` is the JFR extraction (SHA-256 `ce6b268b6dc2d7954d09ac8fc6ecc6cf8de1d13697c4b77a575fcd818f944187`). The immutable source's corresponding enum switch is `backend/src/main/java/com/mermaid/facility/FacilityService.java:93-99`.

Attribution limit: the exact midnight browser IDs cannot be joined directly to a log line because the runtime log pattern does not print MDC request IDs. The stack proof belongs to the immediately controlled same-runtime repeats, not to an ID-bearing log line for `33bcc...` or `13f9...`. The identical runtime, endpoint, response envelope, and durable missing synthetic class make the linkage strong, but the final report must preserve this evidence distinction.

Earlier same-origin direct `curl` evidence also returned 500, but it is corroboration, not the exact browser capture:

| Direct Vite-proxy call | HTTP | request ID | Artifact |
|---|---:|---|---|
| pharmacy | 500 | `4a5cc55b-9688-471d-960d-4caefb772ef1` | `/private/tmp/diag-5173-pharmacy.{headers,body}` |
| hospital | 500 | `1e1499f2-513f-4036-85bc-3af3f3e2d9cd` | `/private/tmp/diag-5173-hospital.{headers,body}` |

## 3. `DATA_MODE`, clean controls, and quota/upstream distinction

The exact broken browser runtime was **fixture**, not live or hybrid. A clean fixture server on port 18081 returned non-empty rows, as fixture mode is required to do regardless of government quota:

| Mode | type | application HTTP | request ID | count | row provenance |
|---|---|---:|---|---:|---|
| fixture | pharmacy | 200 | `a2bfc663-5742-489c-af77-73ed3e8179d4` | 3 | all `fixture` |
| fixture | hospital | 200 | `d5848cff-9266-4747-9476-662f27938196` | 2 | all `fixture` |

A clean `DATA_MODE=live` control on port 18080 returned:

| Mode | type | application HTTP | request ID | count | row provenance |
|---|---|---:|---|---:|---|
| live | pharmacy | 200 | `e2ddcd04-7c90-4a82-9b70-d039e7aeb262` | 50 | all `live` |
| live | hospital | 200 | `7b4dea90-7732-458f-9309-8f357c928a5e` | 50 | all `live` |

These are application endpoint statuses and bodies, verified directly from `/private/tmp/clean-live-*` and `/private/tmp/fixture-*-fresh.*`. They are not preserved raw data.go.kr HTTP traces. Because the adapters do not log successful raw upstream codes and a cache can satisfy a request, the evidence does **not** justify assigning an exact raw upstream HTTP status to every live list/detail operation.

What is settled is narrower and sufficient for this symptom:

- The broken `:5173` run was fixture, so neither `getParmacyLcinfoInqire` nor `getParmacyBassInfoInqire`, `getHospBasisList`, or hospital detail was called. Its 500 cannot be a 429 or government outage.
- In live code, `PharmacyApiClient.findNear()` uses `getParmacyLcinfoInqire` and `retrieve()` (`PharmacyApiClient.java:49,79-104,112-120`); a live list error becomes `PublicApiException` and then 503 `SOURCE_UNAVAILABLE` (`GlobalExceptionHandler.java:62-65`, `ErrorCode.java:29-30`). It does not become `200 []`.
- Pharmacy weekly-hours detail uses `getParmacyBassInfoInqire` (`PharmacyApiClient.java:175-212`). `FacilityService.java:140-175` catches a detail `PublicApiException`, retains the row, and marks hours unknown. That is distinct from a list failure.
- Hospital list uses `getHospBasisList` (`HospitalApiClient.java:27,76-93,104-118,142-148`). Hospital detail is not caught in `FacilityService.toHospital()` (`FacilityService.java:284-318`), so either live list or detail failure can fail the application request; no raw upstream failure occurred on the fixture browser path.

Therefore the observed zero pins were **not** caused by today's pharmacy quota, and no particular upstream operation can honestly be labelled rate-limited from this reproduction.

## 4. Pharmacy, hospital, and ER breakdown

### Pharmacy

**Implemented; zero pins are a runtime regression/environment failure, not expected empty.** The clean controls returned 3 fixture and 50 live rows. The stale browser backend returned 500 before `FacilityService` could dispatch or read fixtures.

### Hospital

**Implemented; zero pins are a runtime regression/environment failure, not expected empty.** `FacilityType` contains `HOSPITAL` (`FacilityType.java:12-14`) and the service dispatches it (`FacilityService.java:95-98`). The clean controls returned 2 fixture and 50 live rows.

The source comment in `frontend/src/lib/facilities.ts:18-19` claiming hospital returns 501 until DEV-203 is stale and contradicted by executable backend behavior. It is not the cause of the failure, but it is an unsupported claim that should be identified as stale documentation.

### ER

**Expected unsupported, not a regression.** The backend enum contains only `pharmacy` and `hospital` (`FacilityType.java:12-14`), and there is no ER adapter or dispatch branch. Hospital rows may contain `emergencyDay`/`emergencyNight`, but the server does not transform them into a separate `emergency_room` facility/marker type.

Preserved evidence for `type=er`: HTTP 400, request ID `9187988f-072e-4c00-ab37-65a46a327163`, body `INVALID_REQUEST` / `Parameter 'type' has the wrong type.` (`/private/tmp/fixture-er.{headers,body}`). A fresh clean-fixture confirmation during this audit returned the same HTTP 400/body shape for both spellings:

- `type=er`: request ID `bd5b3a86-e90f-4d34-ab9e-687b09a3259c`; body `{"error":{"code":"INVALID_REQUEST","message":"Parameter 'type' has the wrong type.","retryable":false,"request_id":"bd5b3a86-e90f-4d34-ab9e-687b09a3259c"}}`
- `type=emergency_room`: request ID `ace6bc42-e1f8-47da-a9d0-7c4d7abfcf45`; body `{"error":{"code":"INVALID_REQUEST","message":"Parameter 'type' has the wrong type.","retryable":false,"request_id":"ace6bc42-e1f8-47da-a9d0-7c4d7abfcf45"}}`

The draft's older `type=emergency_room` request ID `f5fee3cf-b262-4437-88b5-0756a0f339cf` is consistent with the fixture server log's invalid-type event, but no raw header/body artifact for that ID remains. Prefer the preserved `918...` pair and the fresh `ace6...` confirmation in the final wording.

The immutable `MapScreen.tsx:277` says ER joins only after its backend adapter exists. The later browser revision renders a disabled ER control with “Emergency-room results are not available yet”; this presentation is newer than the immutable target and must not be cited as target source behavior.

## 5. Does the frontend tell `200 []` from “we could not look”?

### Main Map screen

**Yes, in its settled state.** `fetchFacilities()` throws on every non-2xx and uses the server's error message (`frontend/src/lib/facilities.ts:33-37`). `MapScreen` preserves rejected requests as `fetchError` (`MapScreen.tsx:133-176`). Its empty condition requires `!loadingFacilities`, `!fetchError`, and zero rows (`:244-252`); render order is loading, result/error, then successful empty (`:311-330`).

Consequences:

- A successful HTTP 200 with `[]` renders “No … found within 1000m.”
- A 429/500/503 renders the error rather than no-results.
- In the `all` filter, if one type succeeds and one fails, successful pins remain and the error is also shown; if both fail, there are zero pins plus the error.

The exact browser screenshot follows this contract: rendered Naver map + “Something went wrong on our side.” and no “No facilities found” claim.

### Assistant-opened `NearbyFacilities`

**P1 transient false-empty remains.** It clears rows and begins a request without a loading state (`NearbyFacilities.tsx:59-70`), then renders no-results whenever `error == null && facilities.length == 0` (`:127-132`). During a slow pending request, it therefore says no facilities exist before lookup has completed. After a rejection it changes to the error, so settled non-2xx handling is honest; the pending state is not.

## 6. Required corrections to the current final draft

The current Track B conclusion is substantively correct, but the following text is unsupported, stale, or imprecisely attributed.

1. **Replace the B-2 “`:5173`가 실제로 받은” request IDs.** `4a5cc...` and `1e149...` are earlier direct Vite-proxy `curl` calls. The exact browser network IDs are pharmacy `33bcc094-7d03-4f32-9826-e329efc9dc8a` and hospital `13f9d64a-ba4e-4a32-8a34-aba78a162ba9`. Keep the earlier pair only as corroborating direct calls.
2. **Add runtime revision attribution.** The exact browser capture was served from `f68cc39948bdb11139af07017336e45ef07e2325`, while source review is fixed at `654f906...`. State that the Track B behavioral paths are unchanged; only MapScreen presentation/disabled ER control differs.
3. **Do not call the exact browser IDs log-correlated.** The log pattern has no request ID. Attribute the `FacilityService$1` stack to the controlled same-runtime repeats and JFR, then describe its application to the later browser pair as the same-runtime causal inference.
4. **Narrow “clean live list-call was 200.”** The preserved 200 is the application `/api/v1/facilities` response. No raw success status was preserved for each data.go.kr operation, and cache hits are possible. Say that the actual broken path was fixture and made no upstream call; say the live application controls were 200/all-live; leave raw per-operation status unassigned.
5. **Replace or qualify `f5fee3cf...`.** Its raw response artifact is absent. Use preserved `9187988f...` for `type=er` plus fresh `ace6bc42...` for the formal `emergency_room` wire value.
6. **Add the stale claim at `frontend/src/lib/facilities.ts:18-19`.** Hospital is implemented and does not normally return 501. This comment is stale documentation, not the root cause.
7. **Link the exact console/network capture, not only the screenshot.** The capture supplies the status codes, request IDs, callback observation, and style/tile evidence; the screenshot supplies visible rendering only.

Suggested replacement summary language:

> At exact `http://localhost:5173`, Naver Maps was accepted and rendered: `maps.js` used `ncpKeyId` and returned 200; the origin-auth, styles, and tiles loaded; neither auth-failure callback fired; and the canvas visibly contained Naver tiles. The same browser session received HTTP 500 for pharmacy (`33bcc094-7d03-4f32-9826-e329efc9dc8a`) and hospital (`13f9d64a-ba4e-4a32-8a34-aba78a162ba9`), both with `INTERNAL_ERROR`. Vite proxied to stale PID 8415 in `DATA_MODE=fixture`; controlled same-runtime repeats/JFR show `NoClassDefFoundError: FacilityService$1` before provider dispatch. Thus no government operation ran and quota/429 was not this failure. Clean fixture controls returned pharmacy 3 and hospital 2; clean live application controls returned 50/50 with live provenance. Pharmacy and hospital absence is a stale-runtime regression; separate ER search is expected unsupported (HTTP 400). The main Map screen shows non-2xx as an error and reserves no-results for settled `200 []`, while `NearbyFacilities` has a P1 transient false-empty during pending requests.

## Evidence inventory

- Browser capture: `diagnostic_drafts/evidence/naver_5173_console_network_20260716.md`, SHA-256 `cbab96008330ef853f46405324bc6115cc90968f7a3e72b05dd5aac87c7d468c`
- Visible map/error screenshot: `/private/tmp/mermaid-map.png`, SHA-256 `126bff27a484d1481b54efc1fc62a0c47afdea582077ce38c7f14e0274c1a4c1`
- Direct older `:5173` bodies: `/private/tmp/diag-5173-{pharmacy,hospital}.{headers,body}`
- Same-runtime repeat bodies: `/private/tmp/old8080-{pharmacy,hospital}-repeat.{headers,body}`
- Same-runtime JFR extraction: `/private/tmp/old8080-facility-exceptions.txt`, SHA-256 `ce6b268b6dc2d7954d09ac8fc6ecc6cf8de1d13697c4b77a575fcd818f944187`
- Clean live bodies: `/private/tmp/clean-live-{pharmacy,hospital}.{headers,body}`
- Clean fixture bodies: `/private/tmp/fixture-{pharmacy,hospital}-fresh.{headers,body}`
- ER preserved response: `/private/tmp/fixture-er.{headers,body}`
