import type { Facility, FacilityType, GeocodeResult } from './types'
import { loadPreferences } from './storage'

export interface FacilityQuery {
  lat: number
  lng: number
  radiusM?: number
  openNow?: boolean
  type?: FacilityType
}

export const EMERGENCY_ROOM_HOURS_NOTICE =
  'Opening hours are not available for these official emergency-room records. Call before you go.'

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
  // NMC's emergency-room endpoint is a location directory, not live availability data. Even if a
  // chat action asks for open-only results, sending `true` would turn every unknown-hours record
  // into a misleading empty result.
  const effectiveOpenNow = type === 'emergency_room' ? false : openNow
  const params = new URLSearchParams({
    lat: String(lat),
    lng: String(lng),
    radius_m: String(radiusM),
    open_now: String(effectiveOpenNow),
    type,
  })

  const response = await fetch(`/api/v1/facilities?${params}`, { signal })
  if (!response.ok) {
    const body = await response.json().catch(() => null)
    throw new Error(body?.error?.message ?? `Could not load nearby ${type}s (HTTP ${response.status}).`)
  }
  return response.json() as Promise<Facility[]>
}

/** Searches addresses through our server so the Naver Client Secret never enters the bundle. */
export async function fetchGeocode(query: string, signal?: AbortSignal): Promise<GeocodeResult[]> {
  const params = new URLSearchParams({ query })
  const response = await fetch(`/api/v1/geocode?${params}`, { signal })
  if (!response.ok) {
    const body = await response.json().catch(() => null)
    throw new Error(body?.error?.message ?? `Could not search addresses (HTTP ${response.status}).`)
  }
  return response.json() as Promise<GeocodeResult[]>
}

/**
 * Where to centre the map.
 *
 * Falls back to 서울시청 when the browser refuses, or when nobody answers the permission prompt —
 * and says which one happened, because a map centred on a city hall the user has never visited
 * should not pretend to be centred on them.
 */
export const SEOUL_CITY_HALL = { lat: 37.5663, lng: 126.9779 }

export const FALLBACK_LOCATION_NOTICE =
  'Centred on Seoul City Hall — we could not read your location, so these are not near you.'
export const MANUAL_LOCATION_NOTICE =
  "Centred on the spot you chose — not your device's location. Distances are measured from there."

export type LocationSource = 'device' | 'manual' | 'fallback'

export interface ResolvedLocation {
  lat: number
  lng: number
  source: LocationSource
  /** Present only for a user-chosen manual centre. */
  label?: string
}

export function locationNotice(location: ResolvedLocation): string | undefined {
  if (location.source === 'manual') return MANUAL_LOCATION_NOTICE
  if (location.source === 'fallback') return FALLBACK_LOCATION_NOTICE
  return undefined
}

export function resolveLocation(timeoutMs = 8000): Promise<ResolvedLocation> {
  const manualLocation = loadPreferences().manualLocation
  const withoutDevice = (): ResolvedLocation =>
    manualLocation
      ? {
          lat: manualLocation.lat,
          lng: manualLocation.lng,
          source: 'manual',
          label: manualLocation.label,
        }
      : { ...SEOUL_CITY_HALL, source: 'fallback' }

  if (!navigator.geolocation) {
    return Promise.resolve(withoutDevice())
  }
  return new Promise((resolve) => {
    navigator.geolocation.getCurrentPosition(
      (position) =>
        resolve({
          lat: position.coords.latitude,
          lng: position.coords.longitude,
          source: 'device',
        }),
      // Denied, unavailable, or timed out. All three mean the same thing to us.
      () => resolve(withoutDevice()),
      { timeout: timeoutMs, maximumAge: 60_000 },
    )
  })
}
