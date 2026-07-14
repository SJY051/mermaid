---
title: DEV-206b — Manual location, slice B: search an address
status: ready
created: 2026-07-14
owner: 윤서진 (FE-1/BE-1)
issue: "#13"
depends_on: "DEV-206 slice A (merged as #76)"
---

# DEV-206b — let them type an address

Slice A gave someone who denied GPS a pin to drop. That works if they can find themselves on a map.
Someone who has just landed, is ill, and cannot read the signs often cannot — but they can read the
address on their hotel booking. This slice turns that address into a location.

## The one thing that is already decided, because it was measured

**Naver Geocoding is now subscribed on our NCP application, and exactly one host answers.**
Probed with the real keys, 2026-07-14:

| host | result |
|---|---|
| `https://maps.apigw.ntruss.com/map-geocode/v2/geocode` | **200 OK** — "서울특별시 중구 세종대로 110" → 서울특별시청, `x=126.9783882`, `y=37.5666103` |
| `https://naveropenapi.apigw.ntruss.com/map-geocode/v2/geocode` | **401** `"A subscription to the API is required"` |

The second host is the one in every blog post. It is wrong for us. Use the first, and do not "fix"
it back.

Auth headers (both required):

```
x-ncp-apigw-api-key-id: <the public client id>
x-ncp-apigw-api-key:    <the SERVER-ONLY secret>
```

Response: `{ "status": "OK", "addresses": [ { "roadAddress", "jibunAddress", "englishAddress", "x", "y" } ] }`
— note **`x` is longitude and `y` is latitude**, and both arrive as strings.

## The design (decided — implement it, do not redesign it)

**1. The browser never calls Naver. The server does.**

`NAVER_MAP_CLIENT_SECRET` is a secret and a `VITE_`-prefixed value is compiled into the shipped
bundle as a string literal (§2-7 — it has already happened once in this repo, and the key was
rotated). So the frontend calls **us**:

```
GET /api/v1/geocode?query=<address>
→ 200 [ { "roadAddress": "...", "jibunAddress": "...", "englishAddress": "...",
          "latitude": 37.5666103, "longitude": 126.9783882 } ]     // at most 5
```

Ours is the shape the frontend already speaks: `latitude`/`longitude` in responses, `snake_case`
query params (AGENTS §8). Translate Naver's `x`/`y` at the boundary; never leak their field names
inward.

**2. Follow the error contract we already have.** Blank or over-long query → `INVALID_REQUEST`.
Upstream failure or a non-OK `status` → `SOURCE_UNAVAILABLE`. Do not invent new codes, and do not
map `IllegalArgumentException` to a client error (§11 — Spring and Jackson throw it for their own
bugs).

**3. The address is not a log line.** Do not log the query. It is a person's location, typed while
they are ill, and a log is a place things persist (§2-5 is about the transcript; this is the same
principle one step out). Log the outcome — a status, a count — never the words.

**4. The frontend puts it where the pin already is.** The "Set your location" panel (slice A) gains
a search box: type an address → results list → pick one → it becomes the manual location, with
`label` set to the address that was picked. Everything else about slice A stays exactly as it is:

- a real device fix **always** wins over a stored manual location;
- a manual centre is **never** called "your location" — the notice says the spot was chosen, and
  distances are measured from there;
- picking a result **re-fetches the facilities from the new centre.**

## Safety invariants — the review gate

1. **No secret reaches the browser** (§2-7). The Naver secret is read server-side, from the
   environment, and appears in no response body, no error message, and no log.
2. **The three location sources stay honest** (slice A, and the same §2-3 family): `device` /
   `manual` / `fallback`, and only `device` may be spoken of as the user's own position.
3. **A failed geocode is not an empty result.** "We could not search" and "no address matched" are
   different sentences, and the second must never be shown for the first.

## Tests (each must fail before it passes — show the mutation)

- Backend: the client parses `x`/`y` into `longitude`/`latitude` **in that order** (swap them and the
  test must go red — this is the classic silent bug: 126,37 is in the Yellow Sea).
- Backend: a non-OK upstream `status` maps to `SOURCE_UNAVAILABLE`, not an empty list.
- Backend: the secret appears in no response and no log line, including the error path.
- Backend: a blank query is `INVALID_REQUEST` before any upstream call is made.
- Frontend: searching, picking a result, and the facilities request going out with **that result's**
  coordinates.
- Frontend: the picked address becomes the visible label, and the notice still says the centre was
  chosen — never "your location".

## Done means

```bash
cd backend  && ./gradlew test
cd frontend && pnpm test && pnpm exec tsc -b && pnpm build
```

All exit 0, **and you have opened a browser**: deny the location permission, search an address, pick
it, and watch the pharmacy list change to that area. Screenshot it.

## Boundaries

- Do not touch chat, allergy, or drug code. Do not edit unrelated tests or fixtures.
- Do not add a new frontend dependency.
- Do not commit `.env` or print a key (§2-8).
- If something here is wrong or impossible, **stop and say so** — do not invent a way around it.
