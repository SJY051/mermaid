import { useEffect, useState } from 'react'
import { FacilityMap } from './FacilityMap'
import { fetchFacilities, resolveLocation, type ResolvedLocation } from '../lib/facilities'
import type { Facility, FacilityType } from '../lib/types'

export interface NearbyFacilitiesProps {
  /** Straight from the assistant's `OPEN_FACILITY_MAP` payload. */
  types: string[]
  radiusM: number
  openNow: boolean
}

/**
 * Turns the assistant's `OPEN_FACILITY_MAP` request into a map with real pins (UI-02, DEV-206/207).
 *
 * The assistant asks for facilities as *data* — it never calls a tool — so everything here starts
 * from a payload it wrote and a coordinate the browser gives us. See spec §2-1.
 *
 * Location is asked for, not assumed. A refusal is not an error; it is a smaller answer, and the
 * map says which one the reader is looking at.
 */
export function NearbyFacilities({ types, radiusM, openNow }: NearbyFacilitiesProps) {
  const [location, setLocation] = useState<ResolvedLocation | null>(null)
  const [facilities, setFacilities] = useState<Facility[]>([])
  const [error, setError] = useState<string | null>(null)

  // `types` is a fresh array on every render; depending on it directly would refetch forever.
  const type = (types[0] ?? 'pharmacy') as FacilityType

  useEffect(() => {
    let cancelled = false
    const controller = new AbortController()

    resolveLocation()
      .then((resolved) => {
        if (cancelled) return
        setLocation(resolved)
        return fetchFacilities(
          { lat: resolved.lat, lng: resolved.lng, radiusM, openNow, type },
          controller.signal,
        )
      })
      .then((found) => {
        if (!cancelled && found) setFacilities(found)
      })
      .catch((e: Error) => {
        if (!cancelled && e.name !== 'AbortError') setError(e.message)
      })

    return () => {
      cancelled = true
      controller.abort()
    }
  }, [type, radiusM, openNow])

  if (!location) {
    return <p className="text-sm text-secondary">Finding your location…</p>
  }

  const plural = type === 'pharmacy' ? 'pharmacies' : 'hospitals'
  const caption = `${plural} within ${radiusM}m${openNow ? ', open now' : ''}`

  return (
    <div className="space-y-2">
      <FacilityMap
        center={{ lat: location.lat, lng: location.lng }}
        facilities={facilities}
        caption={caption}
        notice={
          location.fromDevice
            ? undefined
            : 'Centred on Seoul City Hall — we could not read your location, so these are not near you.'
        }
      />

      {error && (
        <p role="alert" className="text-sm text-secondary">
          {error}
        </p>
      )}

      {!error && facilities.length === 0 && (
        <p className="text-sm text-secondary">
          No {plural} found within {radiusM}m
          {openNow ? ' that we know to be open right now' : ''}.
        </p>
      )}
    </div>
  )
}
