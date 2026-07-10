import type { Facility, FacilityType } from './types'

export interface FacilityQuery {
  lat: number
  lng: number
  radiusM?: number
  openNow?: boolean
  type?: FacilityType
}

/**
 * `GET /api/v1/facilities`.
 *
 * The request takes `lat`/`lng`; the response answers `latitude`/`longitude`. Not a typo — the
 * asymmetry is real, and `verify-api-doc.sh` asserts it so nobody "fixes" one side.
 *
 * `type=hospital` answers 501 until DEV-203 lands. That is deliberate: an empty array would read
 * as "no hospitals near you" when the truth is that we have not built the lookup.
 */
export async function fetchFacilities(
  { lat, lng, radiusM = 1000, openNow = false, type = 'pharmacy' }: FacilityQuery,
  signal?: AbortSignal,
): Promise<Facility[]> {
  const params = new URLSearchParams({
    lat: String(lat),
    lng: String(lng),
    radius_m: String(radiusM),
    open_now: String(openNow),
    type,
  })

  const response = await fetch(`/api/v1/facilities?${params}`, { signal })
  if (!response.ok) {
    const body = await response.json().catch(() => null)
    throw new Error(body?.error?.message ?? `Could not load nearby ${type}s (HTTP ${response.status}).`)
  }
  return response.json() as Promise<Facility[]>
}

/**
 * Where to centre the map.
 *
 * Falls back to 서울시청 when the browser refuses, or when nobody answers the permission prompt —
 * and says which one happened, because a map centred on a city hall the user has never visited
 * should not pretend to be centred on them.
 */
export const SEOUL_CITY_HALL = { lat: 37.5663, lng: 126.9779 }

export interface ResolvedLocation {
  lat: number
  lng: number
  /** false when we fell back. The UI says so. */
  fromDevice: boolean
}

export function resolveLocation(timeoutMs = 8000): Promise<ResolvedLocation> {
  if (!navigator.geolocation) {
    return Promise.resolve({ ...SEOUL_CITY_HALL, fromDevice: false })
  }
  return new Promise((resolve) => {
    navigator.geolocation.getCurrentPosition(
      (position) =>
        resolve({
          lat: position.coords.latitude,
          lng: position.coords.longitude,
          fromDevice: true,
        }),
      // Denied, unavailable, or timed out. All three mean the same thing to us.
      () => resolve({ ...SEOUL_CITY_HALL, fromDevice: false }),
      { timeout: timeoutMs, maximumAge: 60_000 },
    )
  })
}
