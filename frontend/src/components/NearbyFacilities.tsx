import { useEffect, useState } from 'react'
import { FacilityMap } from './FacilityMap'
import {
  EMPTY_FACILITY_RESULT_NOTICE,
  fetchFacilities,
  fetchGeocode,
  EMERGENCY_ROOM_HOURS_NOTICE,
  locationNotice,
  resolveLocation,
  SEOUL_CITY_HALL,
  type ResolvedLocation,
} from '../lib/facilities'
import { splitByOpenStatus } from '../lib/facilitySplit'
import { locationWithoutPrompt, useLocationSession } from '../lib/locationSession'
import { setManualLocation } from '../lib/storage'
import type {
  Facility,
  FacilityOperationPreference,
  FacilityType,
  GeocodeResult,
} from '../lib/types'

interface NearbyFacilitiesBaseProps {
  /** Straight from the validated `OPEN_FACILITY_MAP` response payload. */
  types: string[]
  radiusM: number
}

export type NearbyFacilitiesProps = NearbyFacilitiesBaseProps &
  (
    | { operationPreference: FacilityOperationPreference; openNow?: never }
    | { operationPreference?: never; openNow: boolean }
  )

/**
 * Turns the response's `OPEN_FACILITY_MAP` request into a map with real pins (UI-02, DEV-206/207).
 *
 * The response asks for facilities as *data* — it never calls a tool. New actions are server-owned;
 * a legacy model boolean is promoted to the explicit contract at this boundary.
 *
 * Location is asked for, not assumed. A refusal is not an error; it is a smaller answer, and the
 * map says which one the reader is looking at.
 */
export function NearbyFacilities(props: NearbyFacilitiesProps) {
  const { types, radiusM } = props
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
  const emergencyRoomMode = type === 'emergency_room'
  const hasExplicitOperationPreference =
    'operationPreference' in props && props.operationPreference !== undefined
  const requestedOperationPreference =
    hasExplicitOperationPreference
      ? props.operationPreference
      : props.openNow
        ? 'confirmed_open_only'
        : 'any'
  // NMC ER records have no hours. A legacy open-only action must not erase every result.
  const operationPreference =
    emergencyRoomMode && !hasExplicitOperationPreference && props.openNow
      ? 'open_or_unknown'
      : requestedOperationPreference

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
      { lat: location.lat, lng: location.lng, radiusM, operationPreference, type },
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
  }, [location, type, radiusM, operationPreference])

  const split = splitByOpenStatus(facilities)
  const visibleFacilities =
    operationPreference === 'open_or_unknown'
      ? [...split.open, ...split.unknown]
      : operationPreference === 'confirmed_open_only'
        ? split.open
        : facilities

  const plural =
    type === 'pharmacy' ? 'pharmacies' : type === 'hospital' ? 'hospitals' : 'emergency rooms'
  const preferenceCaption = emergencyRoomMode
    ? ''
    : operationPreference === 'open_or_unknown'
      ? ', open now or hours unknown'
      : operationPreference === 'confirmed_open_only'
        ? ', open now'
        : ''
  const caption = `${plural} within ${radiusM}m${preferenceCaption}`
  const emptyResultNotice = emergencyRoomMode
    ? EMPTY_FACILITY_RESULT_NOTICE
    : `No ${plural} found within ${radiusM}m${
        operationPreference === 'confirmed_open_only'
          ? ' that we know to be open right now'
          : operationPreference === 'open_or_unknown'
            ? ' that are open now or have unknown hours'
            : ''
      }.`

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

      {emergencyRoomMode && (
        <p className="rounded-lg border border-yellow-ring bg-yellow-subtle p-3 text-sm text-primary">
          {EMERGENCY_ROOM_HOURS_NOTICE}
        </p>
      )}

      <FacilityMap
        center={{ lat: location.lat, lng: location.lng }}
        facilities={visibleFacilities}
        facilityType={type}
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

      {!loadingFacilities && !error && visibleFacilities.length === 0 && (
        <p className="text-sm text-secondary">{emptyResultNotice}</p>
      )}
    </div>
  )
}
