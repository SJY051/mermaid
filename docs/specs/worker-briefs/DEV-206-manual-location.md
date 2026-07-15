---
title: DEV-206 — Manual location (slice A: drop a pin)
status: ready
created: 2026-07-14
owner: 윤서진 (FE-2, taken over from 박주형)
issue: "#13"
lane: FE-2
---

# DEV-206 slice A — let someone who denied GPS choose where they are

## The situation

`resolveLocation()` (`frontend/src/lib/facilities.ts`) treats **denied, unavailable and timed-out
alike**: all three fall back to 서울시청, and the UI says so. What a person cannot do is **tell us
where they actually are.** They are sick, in a country whose language they do not read, and the map
is showing pharmacies next to a city hall they have never visited.

This slice gives them a pin to drop. Two other routes are deliberately **out of scope** and will
follow as their own slices — do not build them here:

- **Address search** — needs Naver Geocoding, which is not subscribed on our NCP application
  (probed 2026-07-14: `403 / 401 "A subscription to the API is required"`). Blocked on a console
  action, not on code.
- **Photo EXIF** — reading a photo's GPS in the browser. Separate slice, separate privacy copy.

## What exists (read these first, then explain them back in your own words)

| | |
|---|---|
| `frontend/src/lib/facilities.ts` | `resolveLocation()`, `SEOUL_CITY_HALL`, `ResolvedLocation { lat, lng, fromDevice }` |
| `frontend/src/components/MapScreen.tsx` | holds its own `location` state, calls `resolveLocation()` |
| `frontend/src/components/NearbyFacilities.tsx` | holds its own `location` state, calls `resolveLocation()` — the chat's map |
| `frontend/src/components/FacilityMap.tsx` | `FacilityMapProps { center, zoom?, facilities?, caption?, notice? }` |
| `frontend/src/lib/storage.ts` | `Preferences { rememberAllergies, allergies, defaultRadiusM }`, localStorage, `mermaid.preferences.v1` |
| `frontend/src/lib/facilities.test.ts` | covers location resolution today |

Note the duplication: **two components each resolve location separately.** Whatever you build must
work in both, because the chat opens a map too.

## The design (decided — implement it, do not redesign it)

**1. `ResolvedLocation` says where it came from.**

```ts
export type LocationSource = 'device' | 'manual' | 'fallback'
export interface ResolvedLocation {
  lat: number
  lng: number
  source: LocationSource
}
```

`fromDevice` goes away; its two consumers and their tests move to `source === 'device'`. It is a
shared shape — change every consumer in the same commit.

**2. Precedence, and it is not negotiable: a real device fix always wins.**

```
GPS succeeds            → source: 'device'
GPS denied/unavailable  → stored manual location, if there is one → source: 'manual'
                        → otherwise 서울시청                        → source: 'fallback'
```

A manual pin must **never** override a live GPS fix. The person's actual position is the better
answer, and silently preferring a pin they dropped last week would put them at the wrong pharmacy.
The pin-drop UI therefore only appears when the source is not `device`.

**3. Storage — `Preferences` gains a manual location.**

```ts
manualLocation: { lat: number; lng: number; label: string } | null
```

localStorage (`mermaid.preferences.v1`), same class as saved places: it is the user's own choice
about themselves, and someone who denied GPS should not re-drop the pin on every visit. It must be
clearable from the UI. **Nothing about symptoms ever goes near it** — coordinates and a label only.

**4. The pin-drop UI.**

When `source !== 'device'`, offer **"Set your location"**. It puts the existing Naver map into a
choose-a-spot mode: the map pans under a fixed centre crosshair, and **"Use this spot"** stores the
centre as the manual location. Also offer **"Clear"**, which drops back to the fallback. No new
library, no new API, no key: the map is already on screen.

Then **re-fetch facilities from the new centre.** A pin that moves the map but leaves the old list
underneath is worse than no pin at all.

## Safety invariants — these are the review gate (§2, all P0)

1. **A centre that is not the device's position is never presented as the user's position.** The map
   `notice` must say which one it is, in these words or closer:
   - `device` — no notice.
   - `manual` — "Centred on the spot you chose — not your device's location. Distances are measured
     from there."
   - `fallback` — the existing Seoul City Hall copy, unchanged.
2. **Sweep the distance copy.** Anything reading "N km from you" is now a lie for two of the three
   sources. Grep every surface that renders a distance (list, drawer, cards) and make it say what it
   means — from the map's centre, not from the person. Fix the whole class, not the one you were shown.
3. **`isOpenNow: null` is still "Hours unknown", never "Closed"** (§2-3). You are touching the map's
   data path; do not disturb it.
4. **Fixture data is still labelled as fixture** (§2-9). Same reason.

## Tests (each one must fail before it passes — show the mutation)

- `facilities.test.ts` — precedence: device beats a stored manual; manual beats the fallback;
  nothing stored → fallback. Mutation: make manual win over device → red.
- Pin-drop flow — deny geolocation, set a pin, and assert the facilities request goes out with the
  **new** coordinates. Mutation: keep the old centre → red.
- Notice copy — `manual` renders the "spot you chose" notice and **never** the word "your location";
  `fallback` keeps its notice; `device` has none.
- Clearing a manual location returns the source to `fallback`.
- Storage — a stored manual location survives a reload; clearing removes it.

## Done means

```bash
cd frontend && pnpm test        # includes your new tests
cd frontend && pnpm exec tsc -b
cd frontend && pnpm build
```

All three exit 0, **and you have opened a browser**: deny the location permission, drop a pin, and
watch the pharmacy list change to the new area. Screenshot it. `curl` cannot see any of this.

## Boundaries

- Frontend only. No backend endpoint, no new dependency, no Geocoding.
- Do not touch chat, allergy or drug code. Do not edit fixtures or unrelated tests.
- If something here is wrong or impossible, **stop and say so** — do not invent a way around it.
