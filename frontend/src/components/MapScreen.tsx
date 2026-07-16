import { useEffect, useState } from 'react'
import { Phone } from 'lucide-react'
import {
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
import type { Facility, FacilityType, GeocodeResult } from '../lib/types'
import { FacilityMap } from './FacilityMap'

const RADIUS_M = 1000
const HOSPITAL_UNAVAILABLE = 'We cannot look up hospitals yet — pharmacy search works today.'
type TypeFilter = 'all' | FacilityType

const TYPE_FILTERS: Array<{ id: TypeFilter; label: string; accessibleLabel?: string }> = [
  { id: 'all', label: 'All' },
  { id: 'pharmacy', label: 'Pharmacies' },
  { id: 'hospital', label: 'Hospitals' },
  { id: 'emergency_room', label: 'ER', accessibleLabel: 'Emergency rooms' },
]

function errorDetails(error: unknown): {
  status?: unknown
  code?: unknown
  error?: { code?: unknown; message?: unknown }
} {
  return typeof error === 'object' && error !== null
    ? (error as {
        status?: unknown
        code?: unknown
        error?: { code?: unknown; message?: unknown }
      })
    : {}
}

function isHospitalNotImplemented(error: unknown): boolean {
  const details = errorDetails(error)
  const message = error instanceof Error ? error.message : details.error?.message

  return (
    details.status === 501 ||
    details.status === '501' ||
    details.code === 'NOT_IMPLEMENTED' ||
    details.error?.code === 'NOT_IMPLEMENTED' ||
    (typeof message === 'string' &&
      /\b501\b|NOT_IMPLEMENTED|hospital search is not (?:available|implemented)|(?:feature|hospital search) is not built/i.test(
        message,
      ))
  )
}

function errorMessage(error: unknown): string {
  if (error instanceof Error && error.message) return error.message

  const nestedMessage = errorDetails(error).error?.message
  return typeof nestedMessage === 'string' && nestedMessage
    ? nestedMessage
    : 'Could not load nearby facilities.'
}

function containsKorean(value: string): boolean {
  return /[ㄱ-ㆎ가-힣]/.test(value)
}

export interface MapScreenProps {
  /**
   * Whether the Map tab is the active tab. The shell keeps every screen mounted (so Chat state
   * survives tab switches), so facility fetching is gated until the user opens this tab and then
   * stays loaded. Device location is requested only from the explicit button below.
   */
  active: boolean
}

export function MapScreen({ active }: MapScreenProps) {
  const { location: sessionLocation, rememberLocation } = useLocationSession()
  const [everActive, setEverActive] = useState(active)
  const [location, setLocation] = useState<ResolvedLocation>(
    () => sessionLocation ?? locationWithoutPrompt(),
  )
  const [locating, setLocating] = useState(false)
  const [locationError, setLocationError] = useState<string | null>(null)
  const [typeFilter, setTypeFilter] = useState<TypeFilter>('all')
  const [openNowOnly, setOpenNowOnly] = useState(false)
  const [facilities, setFacilities] = useState<Facility[]>([])
  const [loadingFacilities, setLoadingFacilities] = useState(true)
  const [fetchError, setFetchError] = useState<string | null>(null)
  const [hospitalUnavailable, setHospitalUnavailable] = useState(false)

  useEffect(() => {
    if (!active) return
    setEverActive(true)
    const nextLocation = sessionLocation ?? locationWithoutPrompt()
    setLocation((current) =>
      current.lat === nextLocation.lat &&
      current.lng === nextLocation.lng &&
      current.source === nextLocation.source &&
      current.label === nextLocation.label
        ? current
        : nextLocation,
    )
  }, [active, sessionLocation])

  useEffect(() => {
    if (!everActive) return

    let cancelled = false
    const controller = new AbortController()
    const load = (type: FacilityType) =>
      fetchFacilities(
        {
          lat: location.lat,
          lng: location.lng,
          radiusM: RADIUS_M,
          openNow: false,
          type,
        },
        controller.signal,
      )

    setLoadingFacilities(true)
    setFacilities([])
    setFetchError(null)
    setHospitalUnavailable(false)

    async function loadSelectedFacilities() {
      if (typeFilter === 'all') {
        const [pharmacyResult, hospitalResult] = await Promise.allSettled([
          load('pharmacy'),
          load('hospital'),
        ])
        if (cancelled) return

        const found = [
          ...(pharmacyResult.status === 'fulfilled' ? pharmacyResult.value : []),
          ...(hospitalResult.status === 'fulfilled' ? hospitalResult.value : []),
        ]
        let nextError: string | null = null

        if (pharmacyResult.status === 'rejected') {
          nextError = errorMessage(pharmacyResult.reason)
        }
        if (hospitalResult.status === 'rejected') {
          if (isHospitalNotImplemented(hospitalResult.reason)) {
            setHospitalUnavailable(true)
          } else {
            nextError ??= errorMessage(hospitalResult.reason)
          }
        }

        setFacilities(found)
        setFetchError(nextError)
        setLoadingFacilities(false)
        return
      }

      try {
        const found = await load(typeFilter)
        if (!cancelled) setFacilities(found)
      } catch (error: unknown) {
        if (cancelled) return

        if (typeFilter === 'hospital' && isHospitalNotImplemented(error)) {
          setHospitalUnavailable(true)
        } else {
          setFetchError(errorMessage(error))
        }
      } finally {
        if (!cancelled) setLoadingFacilities(false)
      }
    }

    void loadSelectedFacilities()

    return () => {
      cancelled = true
      controller.abort()
    }
  }, [everActive, location, typeFilter])

  async function handleUseDeviceLocation() {
    setLocating(true)
    setLocationError(null)
    try {
      const resolved = await resolveLocation()
      if (resolved.source === 'device') rememberLocation(resolved)
      setLocation(resolved)
    } catch (error: unknown) {
      setLocationError(errorMessage(error))
    } finally {
      setLocating(false)
    }
  }

  function selectType(nextType: TypeFilter) {
    if (nextType === typeFilter) return

    // Clear the old type synchronously so the selected segment always matches the visible pins.
    setFacilities([])
    setFetchError(null)
    setHospitalUnavailable(false)
    setLoadingFacilities(true)
    if (nextType === 'emergency_room') setOpenNowOnly(false)
    setTypeFilter(nextType)
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

  const split = splitByOpenStatus(facilities)
  const emergencyRoomMode = typeFilter === 'emergency_room'
  const mapFacilities = openNowOnly
    ? split.open
    : [...split.open, ...split.unknown, ...split.closed]
  // Open-now hides closed pins, but the visible result summary still counts every facility.
  // Pass the full screen's provenance so filtering never makes fixture availability look live (§2-9).
  const hasFixtureDataOnScreen = facilities.some((facility) => facility.source.dataMode === 'fixture')
  const resultKind =
    typeFilter === 'all'
      ? hospitalUnavailable
        ? 'pharmacies'
        : 'facilities'
      : typeFilter === 'pharmacy'
        ? 'pharmacies'
        : typeFilter === 'hospital'
          ? 'hospitals'
          : 'emergency rooms'
  const resultSummaryKind =
    emergencyRoomMode && facilities.length === 1 ? 'emergency room' : resultKind
  const showResultSummary =
    !loadingFacilities &&
    !(typeFilter === 'hospital' && hospitalUnavailable) &&
    (!fetchError || facilities.length > 0)
  const showEmptyState =
    !loadingFacilities &&
    !fetchError &&
    facilities.length === 0 &&
    !(typeFilter === 'hospital' && hospitalUnavailable)

  return (
    <div className="flex flex-col gap-3 px-3 pb-3">
      <header className="-mx-3 flex min-h-11 items-center gap-2 border-b border-primary px-3.5">
        <h1 className="text-sm font-semibold text-primary">Map</h1>
        {location.source === 'device' && (
          <span className="rounded-full border border-primary px-2 py-0.5 text-[10px] text-secondary">
            Centred on you
          </span>
        )}
      </header>

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

      {locationError && (
        <p role="alert" className="text-sm text-secondary">
          {locationError}
        </p>
      )}

      <div
        role="group"
        aria-label="Facility type"
        className="grid grid-cols-4 overflow-hidden rounded-full border border-primary bg-surface"
      >
        {TYPE_FILTERS.map((filter) => (
          <button
            key={filter.id}
            type="button"
            aria-label={filter.accessibleLabel}
            aria-pressed={typeFilter === filter.id}
            className={`min-h-11 px-2 text-xs font-medium ${
              filter.id === 'emergency_room' ? '' : 'border-r border-primary'
            } ${
              typeFilter === filter.id
                ? 'bg-primary text-surface'
                : 'bg-surface text-primary'
            }`}
            onClick={() => selectType(filter.id)}
          >
            {filter.label}
          </button>
        ))}
      </div>

      <button
        type="button"
        role="switch"
        aria-checked={openNowOnly}
        aria-describedby={emergencyRoomMode ? 'emergency-room-hours-notice' : undefined}
        disabled={emergencyRoomMode}
        className="flex min-h-11 items-center justify-between rounded-full border border-primary bg-surface px-3 text-xs font-medium text-primary disabled:cursor-not-allowed disabled:opacity-50"
        onClick={() => setOpenNowOnly((current) => !current)}
      >
        <span>Open now</span>
        <span aria-hidden="true">{openNowOnly ? 'On' : 'Off'}</span>
      </button>

      {emergencyRoomMode && (
        <p
          id="emergency-room-hours-notice"
          className="rounded-lg border border-yellow-ring bg-yellow-subtle p-3 text-sm text-primary"
        >
          {EMERGENCY_ROOM_HOURS_NOTICE}
        </p>
      )}

      <FacilityMap
        center={{ lat: location.lat, lng: location.lng }}
        facilities={mapFacilities}
        facilityType={typeFilter === 'all' ? undefined : typeFilter}
        additionalFixtureData={hasFixtureDataOnScreen}
        caption={`Nearby ${resultKind} within ${RADIUS_M}m`}
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

      {loadingFacilities && (
        <p className="text-sm text-secondary">
          {emergencyRoomMode ? 'Loading emergency rooms…' : 'Loading facilities…'}
        </p>
      )}

      {showResultSummary && (
        <p className="text-sm text-primary">
          {facilities.length} {resultSummaryKind} · {split.open.length} Open now ·{' '}
          {split.unknown.length}{' '}
          Hours unknown
        </p>
      )}

      {hospitalUnavailable && <p className="text-sm text-primary">{HOSPITAL_UNAVAILABLE}</p>}

      {fetchError && (
        <p role="alert" className="text-sm text-secondary">
          {fetchError}
        </p>
      )}

      {showEmptyState && (
        <p className="text-sm text-secondary">
          No {resultKind} found within {RADIUS_M}m.
        </p>
      )}

      {openNowOnly && split.unknown.length > 0 && (
        <section aria-labelledby="unknown-hours-heading" className="space-y-2">
          <h2 id="unknown-hours-heading" className="text-base font-semibold text-primary">
            Hours unknown — call to confirm ({split.unknown.length})
          </h2>
          <ul className="space-y-2">
            {split.unknown.map((facility) => (
              <li
                key={facility.id}
                tabIndex={0}
                className="flex min-h-11 items-center justify-between gap-3 rounded border border-primary p-3"
              >
                <span className="min-w-0">
                  <span
                    lang={containsKorean(facility.nameKo) ? 'ko' : undefined}
                    className="block truncate font-medium text-primary"
                  >
                    {facility.nameKo}
                  </span>
                  <span className="block text-sm text-primary">Hours unknown</span>
                </span>
                {facility.phone ? (
                  <a
                    className="inline-flex shrink-0 items-center gap-1 text-sm text-primary underline"
                    href={`tel:${facility.phone}`}
                  >
                    <Phone aria-hidden="true" size={14} />
                    {facility.phone}
                  </a>
                ) : (
                  <span className="shrink-0 text-sm text-secondary">Phone unavailable</span>
                )}
              </li>
            ))}
          </ul>
          {/* Unknown hours leave the map when filtered, but never disappear: null is not closed (§2-3). */}
        </section>
      )}
    </div>
  )
}
