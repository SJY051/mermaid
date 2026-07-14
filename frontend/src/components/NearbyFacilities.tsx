import { useEffect, useState } from 'react'
import { FacilityMap } from './FacilityMap'
import {
  fetchFacilities,
  locationNotice,
  resolveLocation,
  SEOUL_CITY_HALL,
  type ResolvedLocation,
} from '../lib/facilities'
import { setManualLocation } from '../lib/storage'
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

    resolveLocation()
      .then((resolved) => {
        if (!cancelled) setLocation(resolved)
      })
      .catch((e: Error) => {
        if (!cancelled && e.name !== 'AbortError') setError(e.message)
      })

    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    if (!location) return
    let cancelled = false
    const controller = new AbortController()

    setFacilities([])
    setError(null)
    fetchFacilities(
      { lat: location.lat, lng: location.lng, radiusM, openNow, type },
      controller.signal,
    )
      .then((found) => {
        if (!cancelled) setFacilities(found)
      })
      .catch((e: Error) => {
        if (!cancelled && e.name !== 'AbortError') setError(e.message)
      })

    return () => {
      cancelled = true
      controller.abort()
    }
  }, [location, type, radiusM, openNow])

  if (!location) {
    return <p className="text-sm text-secondary">Finding your location…</p>
  }

  const plural = type === 'pharmacy' ? 'pharmacies' : 'hospitals'
  const caption = `${plural} within ${radiusM}m${openNow ? ', open now' : ''}`

  function useManualLocation(center: { lat: number; lng: number }) {
    setManualLocation({ ...center, label: 'Chosen map spot' })
    setLocation({ ...center, source: 'manual' })
  }

  function clearManualLocation() {
    setManualLocation(null)
    setLocation({ ...SEOUL_CITY_HALL, source: 'fallback' })
  }

  return (
    <div className="space-y-2">
      <FacilityMap
        center={{ lat: location.lat, lng: location.lng }}
        facilities={facilities}
        caption={caption}
        notice={locationNotice(location)}
        manualLocation={
          location.source === 'device'
            ? undefined
            : {
                canClear: location.source === 'manual',
                onUseSpot: useManualLocation,
                onClear: clearManualLocation,
              }
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
