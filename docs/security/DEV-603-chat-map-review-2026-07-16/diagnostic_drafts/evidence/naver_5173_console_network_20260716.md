# Naver Maps console/network capture at `http://localhost:5173`

- Captured: 2026-07-16 00:04 KST
- Browser: fresh headless Chromium context through Playwright; geolocation permission granted at Seoul City Hall (`37.5665,126.9780`)
- Runtime attribution: the live Vite page at the repository's then-current checkout (`f68cc39948bdb11139af07017336e45ef07e2325`), not the immutable security-scan revision. Backend traffic was proxied to the pre-existing PID 8415 runtime on port 8080.
- Interaction: opened `/`, installed passive assignment wrappers for `navermap_authFailure` and `nav__authFailure` before selecting the Map tab, selected `Map`, and observed for 12 seconds.

## Console

```json
[
  {"type":"debug","text":"[vite] connecting..."},
  {"type":"debug","text":"[vite] connected."},
  {"type":"error","text":"Failed to load resource: the server responded with a status of 500 (Internal Server Error)"},
  {"type":"error","text":"Failed to load resource: the server responded with a status of 500 (Internal Server Error)"}
]
```

There were no page exceptions. Both console errors corresponded to the two facility API responses below, not to Naver Maps.

## Critical network trace

| Method | URL / operation | HTTP | Observation |
|---|---|---:|---|
| GET | `https://oapi.map.naver.com/openapi/v3/maps.js?ncpKeyId=ea2sjk439m&language=en` | 200 | Correct `ncpKeyId` parameter; script loaded |
| GET | `http://oapi.map.naver.com/v3/auth?...url=http%3A%2F%2Flocalhost%3A5173%2F...` | 200 | Origin-auth request accepted |
| GET | `nrbe.map.naver.net/styles/basic.json` | 200 | Base style loaded |
| GET | `nrbe.map.naver.net/styles/terrain.json` | 200 | Terrain style loaded |
| GET | `nrbe.map.naver.net/styles/satellite.json` | 200 | Satellite style loaded |
| GET | multiple `nrbe.map.naver.net/styles/basic/...png` tiles | 200 | Map tiles rendered; no failed Naver request was observed |
| GET | `/api/v1/facilities?...&type=pharmacy` | 500 | `X-Request-Id: 33bcc094-7d03-4f32-9826-e329efc9dc8a` |
| GET | `/api/v1/facilities?...&type=hospital` | 500 | `X-Request-Id: 13f9d64a-ba4e-4a32-8a34-aba78a162ba9` |

Facility response bodies:

```json
{"error":{"code":"INTERNAL_ERROR","message":"Something went wrong on our side.","retryable":false,"request_id":"33bcc094-7d03-4f32-9826-e329efc9dc8a"}}
{"error":{"code":"INTERNAL_ERROR","message":"Something went wrong on our side.","retryable":false,"request_id":"13f9d64a-ba4e-4a32-8a34-aba78a162ba9"}}
```

## Callback and rendered state

```json
{
  "authFailureEvents": [],
  "navermap_authFailureType": "function",
  "nav__authFailureType": "undefined",
  "naverType": "object",
  "naverMapsType": "object",
  "mapLoading": false,
  "mapError": null,
  "mapCanvasChildren": 4
}
```

The auth callback wrapper was installed before Map-tab activation and therefore before the application assigned and appended the script. No auth-failure callback fired. The SDK namespace, map canvas children, styles, and tiles all existed. This settles the Naver-load branch as **accepted**, not rejected, never-appended, or late-callback. The visibly empty facility layer is instead explained by the two backend HTTP 500 responses.
