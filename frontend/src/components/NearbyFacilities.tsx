import { useEffect, useState } from 'react'
import { FacilityMap } from './FacilityMap'
import {
  fetchFacilities,
  fetchGeocode,
  locationNotice,
  resolveLocation,
  SEOUL_CITY_HALL,
  type ResolvedLocation,
} from '../lib/facilities'
import { locationWithoutPrompt, useLocationSession } from '../lib/locationSession'
import { setManualLocation } from '../lib/storage'
import type { Facility, FacilityType, GeocodeResult } from '../lib/types'

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
  const { location: sessionLocation, rememberLocation } = useLocationSession()
  const [location, setLocation] = useState<ResolvedLocation>(
    () => sessionLocation ?? locationWithoutPrompt(),
  )
  const [locating, setLocating] = useState(false)
  const [facilities, setFacilities] = useState<Facility[]>([])
  const [loadingFacilities, setLoadingFacilities] = useState(true)
  const [error, setError] = useState<string | null>(null)

  // `types` is a fresh array on every render; depending on it directly would refetch forever.
  const type = (types[0] ?? 'pharmacy') as FacilityType

  useEffect(() => {
    if (sessionLocation) setLocation(sessionLocation)
  }, [sessionLocation])

  useEffect(() => {
    if (!location) return
    let cancelled = false
    const controller = new AbortController()

    setLoadingFacilities(true)
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
      .finally(() => {
        if (!cancelled) setLoadingFacilities(false)
      })

    return () => {
      cancelled = true
      controller.abort()
    }
  }, [location, type, radiusM, openNow])

  const plural = type === 'pharmacy' ? 'pharmacies' : 'hospitals'
  const caption = `${plural} within ${radiusM}m${openNow ? ', open now' : ''}`

  async function handleUseDeviceLocation() {
    setLocating(true)
    setError(null)
    try {
      const resolved = await resolveLocation()
      if (resolved.source === 'device') rememberLocation(resolved)
      setLocation(resolved)
    } catch (e: unknown) {
      if (e instanceof Error && e.name !== 'AbortError') setError(e.message)
    } finally {
      setLocating(false)
    }
  }

  function useManualLocation(center: { lat: number; lng: number }, label = 'Chosen map spot') {
    setManualLocation({ ...center, label })
    setLocation({ ...center, source: 'manual', label })
  }

  function useAddress(result: GeocodeResult) {
    const label = result.roadAddress || result.jibunAddress || result.englishAddress
    useManualLocation({ lat: result.latitude, lng: result.longitude }, label)
  }

  function clearManualLocation() {
    setManualLocation(null)
    setLocation({ ...SEOUL_CITY_HALL, source: 'fallback' })
  }

  return (
    <div className="space-y-2">
      {location.source !== 'device' && (
        <button
          type="button"
          disabled={locating}
          className="min-h-11 rounded border border-primary bg-surface px-3 text-sm font-medium text-primary disabled:opacity-50"
          onClick={() => void handleUseDeviceLocation()}
        >
          {locating ? 'Finding your location…' : 'Use my location'}
        </button>
      )}

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
                onSearchAddress: fetchGeocode,
                onUseAddress: useAddress,
                currentLabel: location.source === 'manual' ? location.label : undefined,
                onClear: clearManualLocation,
              }
        }
      />

      {error && (
        <p role="alert" className="text-sm text-secondary">
          {error}
        </p>
      )}

      {/* The fallback centre exists before this request settles. Without a separate gate, the
          empty array would claim "No facilities found" throughout the cold request. */}
      {loadingFacilities && <p className="text-sm text-secondary">Loading facilities…</p>}

      {!loadingFacilities && !error && facilities.length === 0 && (
        <p className="text-sm text-secondary">
          No {plural} found within {radiusM}m
          {openNow ? ' that we know to be open right now' : ''}.
        </p>
      )}
    </div>
  )
}
