import { useEffect, useState } from 'react'
import { Phone } from 'lucide-react'
import {
  fetchFacilities,
  fetchGeocode,
  locationNotice,
  resolveLocation,
  SEOUL_CITY_HALL,
  type ResolvedLocation,
} from '../lib/facilities'
import { splitByOpenStatus } from '../lib/facilitySplit'
import { setManualLocation } from '../lib/storage'
import type { Facility, FacilityType, GeocodeResult } from '../lib/types'
import { FacilityMap } from './FacilityMap'

const RADIUS_M = 1000
const HOSPITAL_UNAVAILABLE = 'We cannot look up hospitals yet — pharmacy search works today.'
type TypeFilter = 'all' | 'pharmacy' | 'hospital'

const TYPE_FILTERS: Array<{ id: TypeFilter; label: string }> = [
  { id: 'all', label: 'All' },
  { id: 'pharmacy', label: 'Pharmacies' },
  { id: 'hospital', label: 'Hospitals' },
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
   * survives tab switches), which means this effect would otherwise run on the initial Chat-only
   * load — prompting for location and spending the 1,000/day pharmacy quota before the user ever
   * asks for nearby care. We gate on activation, and stay loaded once opened.
   */
  active: boolean
}

export function MapScreen({ active }: MapScreenProps) {
  const [everActive, setEverActive] = useState(active)
  const [location, setLocation] = useState<ResolvedLocation | null>(null)
  const [locationError, setLocationError] = useState<string | null>(null)
  const [typeFilter, setTypeFilter] = useState<TypeFilter>('all')
  const [openNowOnly, setOpenNowOnly] = useState(false)
  const [facilities, setFacilities] = useState<Facility[]>([])
  const [loadingFacilities, setLoadingFacilities] = useState(true)
  const [fetchError, setFetchError] = useState<string | null>(null)
  const [hospitalUnavailable, setHospitalUnavailable] = useState(false)

  useEffect(() => {
    if (active) setEverActive(true)
  }, [active])

  useEffect(() => {
    if (!everActive) return
    let cancelled = false

    resolveLocation()
      .then((resolved) => {
        if (!cancelled) setLocation(resolved)
      })
      .catch((error: unknown) => {
        if (!cancelled) setLocationError(errorMessage(error))
      })

    return () => {
      cancelled = true
    }
  }, [everActive])

  useEffect(() => {
    if (!location) return

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
  }, [location, typeFilter])

  function selectType(nextType: TypeFilter) {
    if (nextType === typeFilter) return

    // Clear the old type synchronously so the selected segment always matches the visible pins.
    setFacilities([])
    setFetchError(null)
    setHospitalUnavailable(false)
    setLoadingFacilities(true)
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

  if (!location) {
    return (
      <div className="flex flex-col gap-3 px-3 pb-3">
        <header className="-mx-3 flex min-h-11 items-center border-b border-primary px-3.5">
          <h1 className="text-sm font-semibold text-primary">Map</h1>
        </header>
        {locationError ? (
          <p role="alert" className="text-sm text-secondary">
            {locationError}
          </p>
        ) : (
          <p className="text-sm text-secondary">Finding your location…</p>
        )}
      </div>
    )
  }

  const split = splitByOpenStatus(facilities)
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
        : 'hospitals'
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

      <div
        role="group"
        aria-label="Facility type"
        className="grid grid-cols-4 overflow-hidden rounded-full border border-primary bg-surface"
      >
        {TYPE_FILTERS.map((filter) => (
          <button
            key={filter.id}
            type="button"
            aria-pressed={typeFilter === filter.id}
            className={`min-h-11 border-r border-primary px-2 text-xs font-medium ${
              typeFilter === filter.id
                ? 'bg-primary text-surface'
                : 'bg-surface text-primary'
            }`}
            onClick={() => selectType(filter.id)}
          >
            {filter.label}
          </button>
        ))}
        <button
          type="button"
          disabled
          aria-describedby="er-results-unavailable"
          className="min-h-11 bg-surface px-2 text-xs font-medium text-secondary disabled:cursor-not-allowed"
        >
          ER
        </button>
        <span id="er-results-unavailable" className="sr-only">
          Emergency-room results are not available yet.
        </span>
      </div>

      <button
        type="button"
        role="switch"
        aria-checked={openNowOnly}
        className="flex min-h-11 items-center justify-between rounded-full border border-primary bg-surface px-3 text-xs font-medium text-primary"
        onClick={() => setOpenNowOnly((current) => !current)}
      >
        <span>Open now</span>
        <span aria-hidden="true">{openNowOnly ? 'On' : 'Off'}</span>
      </button>

      <FacilityMap
        center={{ lat: location.lat, lng: location.lng }}
        facilities={mapFacilities}
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

      {loadingFacilities && <p className="text-sm text-secondary">Loading facilities…</p>}

      {showResultSummary && (
        <p className="text-sm text-primary">
          {facilities.length} {resultKind} · {split.open.length} Open now · {split.unknown.length}{' '}
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
