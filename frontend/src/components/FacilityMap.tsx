import { useEffect, useId, useRef, useState, type FormEvent } from 'react'
import { ChevronRight, Cross, Crosshair, Pill, Search } from 'lucide-react'
import { useNaverMap } from '../hooks/useNaverMap'
import type { Facility, GeocodeResult } from '../lib/types'
import { DetailDrawer } from './DetailDrawer'

export interface FacilityMapProps {
  center: { lat: number; lng: number }
  zoom?: number
  facilities?: Facility[]
  /** Related facilities rendered outside the map also carry fixture provenance. */
  additionalFixtureData?: boolean
  /** Rendered above the map. The assistant's own words about why it opened. */
  caption?: string
  /** Shown when the centre is not the device position. */
  notice?: string
  /** Available only when the device did not provide the centre. */
  manualLocation?: {
    canClear: boolean
    onUseSpot: (center: { lat: number; lng: number }) => void
    onSearchAddress: (query: string) => Promise<GeocodeResult[]>
    onUseAddress: (result: GeocodeResult) => void
    currentLabel?: string
    onClear: () => void
  }
}

/** `null` means "we could not tell", and it must never be drawn as "Closed" (spec §2-13). */
function openLabel(facility: Facility): string {
  if (facility.operation.isOpenNow === true) return 'Open now'
  if (facility.operation.isOpenNow === false) return 'Closed'
  return 'Hours unknown'
}

function facilityTypeLabel(facility: Facility): string {
  if (facility.type === 'pharmacy') return 'Pharmacy'
  if (facility.type === 'hospital') return 'Hospital'
  return 'Emergency room'
}

function operationGlyph(facility: Facility): string {
  if (facility.operation.isOpenNow === true) return '✓'
  if (facility.operation.isOpenNow === false) return '×'
  return '?'
}

function operationStatus(facility: Facility): 'open' | 'closed' | 'unknown' {
  if (facility.operation.isOpenNow === true) return 'open'
  if (facility.operation.isOpenNow === false) return 'closed'
  return 'unknown'
}

interface MarkerTokens {
  fill: string
  ring: string
  glyph: string
}

function markerTokens(facility: Facility): MarkerTokens {
  if (facility.operation.isOpenNow === true) {
    return {
      fill: 'var(--color-green-subtle)',
      ring: 'var(--color-green-ring)',
      glyph: 'var(--color-green-vivid)',
    }
  }
  if (facility.operation.isOpenNow === false) {
    return {
      fill: 'var(--color-muted)',
      ring: 'var(--color-border-strong)',
      glyph: 'var(--color-secondary)',
    }
  }
  return {
    fill: 'var(--color-yellow-subtle)',
    ring: 'var(--color-yellow-ring)',
    glyph: 'var(--color-yellow-vivid)',
  }
}

// Path data from Lucide Icons v1.23.0 (Pill, Cross, HeartPulse), ISC.
// https://lucide.dev/icons/pill · https://lucide.dev/icons/cross · https://lucide.dev/icons/heart-pulse
function markerKindIcon(facility: Facility, colour: string, pillFillId: string): string {
  if (facility.type === 'pharmacy') {
    return (
      `<svg data-kind-icon="pharmacy" xmlns="http://www.w3.org/2000/svg" width="16" height="16" ` +
      `viewBox="0 0 24 24" fill="none" stroke="${colour}" stroke-width="2" ` +
      `stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">` +
      `<defs><linearGradient id="${pillFillId}" x1="0%" y1="100%" x2="100%" y2="0%">` +
      `<stop offset="50%" stop-color="${colour}" stop-opacity=".4"/>` +
      `<stop offset="50%" stop-color="${colour}" stop-opacity="0"/>` +
      `</linearGradient></defs>` +
      `<path d="m10.5 20.5 10-10a4.95 4.95 0 1 0-7-7l-10 10a4.95 4.95 0 1 0 7 7Z" ` +
      `fill="url(#${pillFillId})"/>` +
      `<path d="m8.5 8.5 7 7"/></svg>`
    )
  }
  if (facility.type === 'hospital') {
    return (
      `<svg data-kind-icon="hospital" xmlns="http://www.w3.org/2000/svg" width="15" height="15" ` +
      `viewBox="0 0 24 24" fill="none" stroke="${colour}" stroke-width="2" ` +
      `stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">` +
      `<path d="M4 9a2 2 0 0 0-2 2v2a2 2 0 0 0 2 2h4a1 1 0 0 1 1 1v4a2 2 0 0 0 2 2h2` +
      `a2 2 0 0 0 2-2v-4a1 1 0 0 1 1-1h4a2 2 0 0 0 2-2v-2a2 2 0 0 0-2-2h-4a1 1 0 0 1-1-1V4` +
      `a2 2 0 0 0-2-2h-2a2 2 0 0 0-2 2v4a1 1 0 0 1-1 1z"/></svg>`
    )
  }
  return (
    `<svg data-kind-icon="emergency_room" xmlns="http://www.w3.org/2000/svg" width="16" height="16" ` +
    `viewBox="0 0 24 24" fill="none" stroke="${colour}" stroke-width="2" ` +
    `stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" ` +
    `style="transform:rotate(-45deg)">` +
    `<path d="M2 9.5a5.5 5.5 0 0 1 9.591-3.676.56.56 0 0 0 .818 0A5.49 5.49 0 0 1 22 9.5` +
    `c0 2.29-1.5 4-3 5.5l-5.492 5.313a2 2 0 0 1-3 .019L5 15c-1.5-1.5-3-3.2-3-5.5"/>` +
    `<path d="M3.22 13H9.5l.5-1 2 4.5 2-7 1.5 3.5h5.27"/></svg>`
  )
}

function markerButtonContent(facility: Facility, index: number, idPrefix: string): string {
  const status = operationStatus(facility)
  const statusLabel = openLabel(facility)
  const kindLabel = facilityTypeLabel(facility)
  const glyph = operationGlyph(facility)
  const tokens = markerTokens(facility)
  const pillFillId = `${idPrefix}-marker-${index}-pill-half`
  const borderRadius =
    facility.type === 'pharmacy' ? '50%' : facility.type === 'hospital' ? '8px' : '6px'
  const rotation = facility.type === 'emergency_room' ? 'transform:rotate(45deg);' : ''
  const nameId = `${idPrefix}-marker-${index}-name`
  const detailId = `${idPrefix}-marker-${index}-detail`
  const visuallyHidden =
    'position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;' +
    'clip:rect(0,0,0,0);white-space:nowrap;border:0'

  // Naver accepts marker content only as an HTML string. The real button keeps native
  // Enter/Space activation; the map container delegates its click to the selected facility.
  return (
    `<button type="button" data-facility-index="${index}" data-facility-kind="${facility.type}" ` +
    `data-facility-status="${status}" aria-labelledby="${nameId} ${detailId}" ` +
    `style="position:relative;width:44px;height:44px;display:grid;place-items:center;border:0;` +
    `background:transparent;padding:7px;cursor:pointer;outline-offset:3px">` +
    `<span aria-hidden="true" style="position:relative;width:30px;height:30px;display:grid;` +
    `place-items:center;border-radius:${borderRadius};background:${tokens.fill};` +
    `border:2px solid ${tokens.ring};box-shadow:0 2px 6px rgba(0,0,0,.32);${rotation}">` +
    `${markerKindIcon(facility, tokens.glyph, pillFillId)}` +
    `</span>` +
    `<span aria-hidden="true" data-status-glyph="${status}" style="position:absolute;top:1px;` +
    `right:1px;width:16px;height:16px;display:grid;place-items:center;border-radius:50%;` +
    `background:var(--color-surface);border:1.5px solid var(--color-border-strong);` +
    `color:var(--color-primary);font:700 12px/1 system-ui">` +
    `${glyph}</span>` +
    `<span id="${nameId}" lang="ko" style="${visuallyHidden}">${escapeHtml(facility.nameKo)}</span>` +
    `<span id="${detailId}" style="${visuallyHidden}">${kindLabel}, ${statusLabel}, ` +
    `${Math.round(facility.distanceMeters)} metres from the map centre. Open details.</span>` +
    `</button>`
  )
}

function escapeHtml(value: string): string {
  return value.replace(
    /[&<>"']/g,
    (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!,
  )
}

/**
 * The map (UI-02, DEV-206).
 *
 * It renders three states, and never the fourth one a naive version has: a blank grey box that
 * looks like a map still loading. When the key is rejected the SDK says so through a global
 * callback, `useNaverMap` turns that into an `error`, and this component prints it.
 */
export function FacilityMap({
  center,
  zoom = 15,
  facilities = [],
  additionalFixtureData = false,
  caption,
  notice,
  manualLocation,
}: FacilityMapProps) {
  const { containerRef, map, ready, error } = useNaverMap({ center, zoom })
  const markerIdPrefix = useId().replaceAll(':', '')
  const markersRef = useRef<naver.maps.Marker[]>([])
  const [selectedFacility, setSelectedFacility] = useState<Facility | null>(null)
  const [markerError, setMarkerError] = useState<Error | null>(null)
  const [choosingLocation, setChoosingLocation] = useState(false)
  const [addressQuery, setAddressQuery] = useState('')
  const [addressResults, setAddressResults] = useState<GeocodeResult[]>([])
  const [addressSearchState, setAddressSearchState] = useState<
    'idle' | 'loading' | 'results' | 'empty' | 'error'
  >('idle')

  const visibleError = error ?? markerError
  const hasFixtureData =
    additionalFixtureData || facilities.some((facility) => facility.source.dataMode === 'fixture')

  useEffect(() => {
    const container = containerRef.current
    if (!container) return

    const openMarkerDetails = (event: Event) => {
      if (!(event.target instanceof Element)) return
      const button = event.target.closest<HTMLButtonElement>('button[data-facility-index]')
      if (!button || !container.contains(button)) return

      const facility = facilities[Number(button.dataset.facilityIndex)]
      if (facility) setSelectedFacility(facility)
    }

    container.addEventListener('click', openMarkerDetails)
    return () => container.removeEventListener('click', openMarkerDetails)
  }, [containerRef, facilities])

  useEffect(() => {
    if (!map || !ready) return
    setMarkerError(null)

    // Naver markers are not React children. Nothing removes them for us, and a second render
    // would silently stack a new pin on every old one.
    for (const marker of markersRef.current) {
      try {
        marker.setMap(null)
      } catch {
        // A rejected Naver key can invalidate an overlay before React removes it.
      }
    }
    markersRef.current = []

    try {
      for (const [index, facility] of facilities.entries()) {
        const marker = new naver.maps.Marker({
          map,
          position: new naver.maps.LatLng(facility.latitude, facility.longitude),
          title: facility.nameKo,
          icon: {
            content: markerButtonContent(facility, index, markerIdPrefix),
            anchor: new naver.maps.Point(22, 22),
          },
        })

        markersRef.current.push(marker)
      }
    } catch {
      setMarkerError(
        new Error('The facility pins could not be loaded. Use the facility list below instead.'),
      )
    }

    return () => {
      for (const marker of markersRef.current) {
        try {
          marker.setMap(null)
        } catch {
          // Keep external SDK cleanup failures from replacing the whole app with a blank screen.
        }
      }
      markersRef.current = []
    }
  }, [map, ready, facilities, markerIdPrefix])

  const displayedFacilityTypes = new Set(facilities.map((facility) => facility.type))

  async function searchAddress(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!manualLocation || !addressQuery.trim()) return

    setAddressSearchState('loading')
    setAddressResults([])
    try {
      const results = await manualLocation.onSearchAddress(addressQuery.trim())
      setAddressResults(results)
      setAddressSearchState(results.length > 0 ? 'results' : 'empty')
    } catch {
      setAddressSearchState('error')
    }
  }

  function addressLabel(result: GeocodeResult): string {
    return result.roadAddress || result.jibunAddress || result.englishAddress
  }

  return (
    <section className="space-y-2" aria-label="Nearby facilities map">
      {caption && <p className="text-sm text-secondary">{caption}</p>}
      {notice && <p className="text-xs text-secondary">{notice}</p>}
      {hasFixtureData && (
        <p data-testid="map-fixture-notice" className="text-sm text-primary">
          Sample data — availability may not reflect current conditions.
        </p>
      )}

      {manualLocation && (
        <section aria-label="Set your location" className="space-y-2 rounded border border-primary p-3">
          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              className="min-h-11 rounded border border-primary bg-surface px-3 text-sm font-medium text-primary"
              onClick={() => setChoosingLocation(true)}
            >
              Set your location
            </button>
            {manualLocation.canClear && (
              <button
                type="button"
                className="min-h-11 rounded border border-primary bg-surface px-3 text-sm font-medium text-primary"
                onClick={() => {
                  setChoosingLocation(false)
                  manualLocation.onClear()
                }}
              >
                Clear
              </button>
            )}
          </div>

          <form className="space-y-2" onSubmit={searchAddress}>
            <label htmlFor={`${markerIdPrefix}-address`} className="block text-sm font-medium text-primary">
              Search an address
            </label>
            <div className="flex gap-2">
              <input
                id={`${markerIdPrefix}-address`}
                type="search"
                value={addressQuery}
                onChange={(event) => setAddressQuery(event.target.value)}
                className="min-h-11 min-w-0 flex-1 rounded border border-primary bg-surface px-3 text-sm text-primary"
                autoComplete="street-address"
              />
              <button
                type="submit"
                disabled={!addressQuery.trim() || addressSearchState === 'loading'}
                className="inline-flex min-h-11 items-center gap-2 rounded bg-primary px-4 text-sm font-medium text-surface disabled:opacity-50"
              >
                <Search aria-hidden="true" size={16} />
                {addressSearchState === 'loading' ? 'Searching…' : 'Search'}
              </button>
            </div>
          </form>

          {manualLocation.currentLabel && (
            <p className="text-sm text-primary">Chosen centre: {manualLocation.currentLabel}</p>
          )}
          {addressSearchState === 'empty' && (
            <p className="text-sm text-secondary">No address matched. Try a more specific address.</p>
          )}
          {addressSearchState === 'error' && (
            <p role="alert" className="text-sm text-secondary">
              We could not search for that address. Please try again.
            </p>
          )}
          {addressSearchState === 'results' && (
            <ul aria-label="Address search results" className="space-y-1">
              {addressResults.map((result) => {
                const label = addressLabel(result)
                return (
                  <li key={`${result.latitude}:${result.longitude}:${label}`}>
                    <button
                      type="button"
                      className="min-h-11 w-full rounded border border-primary px-3 py-2 text-left text-sm text-primary"
                      onClick={() => {
                        manualLocation.onUseAddress(result)
                        setAddressQuery(label)
                        setAddressResults([])
                        setAddressSearchState('idle')
                        setChoosingLocation(false)
                      }}
                    >
                      <span className="block">{label}</span>
                      {result.englishAddress && result.englishAddress !== label && (
                        <span className="block text-xs text-secondary">{result.englishAddress}</span>
                      )}
                    </button>
                  </li>
                )
              })}
            </ul>
          )}
        </section>
      )}

      <div
        role="group"
        aria-label="Map marker legend"
        className="flex flex-wrap gap-x-4 gap-y-1 rounded-full border border-primary bg-surface px-3 py-2 text-xs font-medium text-primary"
      >
        {displayedFacilityTypes.has('pharmacy') && (
          <span className="inline-flex items-center gap-1.5">
            <span
              data-legend-kind="pharmacy"
              aria-hidden="true"
              className="grid h-4 w-4 place-items-center rounded-full border border-primary"
            >
              <Pill size={12} />
            </span>
            Pharmacy
          </span>
        )}
        {displayedFacilityTypes.has('hospital') && (
          <span className="inline-flex items-center gap-1.5">
            <span
              data-legend-kind="hospital"
              aria-hidden="true"
              className="grid h-4 w-4 place-items-center rounded-[4px] border border-primary"
            >
              <Cross size={12} />
            </span>
            Hospital
          </span>
        )}
        <span className="inline-flex items-center gap-1.5">
          <span aria-hidden="true" className="grid h-4 w-4 place-items-center rounded-full border border-green-ring bg-green-subtle text-[11px] font-bold text-green-vivid">✓</span>
          Open now
        </span>
        <span className="inline-flex items-center gap-1.5">
          <span aria-hidden="true" className="grid h-4 w-4 place-items-center rounded-full border border-yellow-ring bg-yellow-subtle text-[11px] font-bold text-yellow-vivid">?</span>
          Hours unknown
        </span>
        <span className="inline-flex items-center gap-1.5">
          <span aria-hidden="true" className="grid h-4 w-4 place-items-center rounded-full border border-strong bg-muted text-[11px] font-bold text-primary">×</span>
          Closed
        </span>
      </div>

      <div className="relative h-80 w-full overflow-hidden rounded-lg border border-primary">
        <div ref={containerRef} data-testid="naver-map" className="h-full w-full" />

        {choosingLocation && !visibleError && (
          <>
            <div
              aria-label="Chosen map centre"
              role="img"
              className="pointer-events-none absolute left-1/2 top-1/2 grid h-10 w-10 -translate-x-1/2 -translate-y-1/2 place-items-center rounded-full border-2 border-primary bg-surface/90 text-2xl font-light text-primary shadow"
            >
              <Crosshair aria-hidden="true" size={22} />
            </div>
            <button
              type="button"
              disabled={!ready || !map}
              className="absolute bottom-3 left-1/2 min-h-11 -translate-x-1/2 rounded bg-primary px-4 text-sm font-medium text-surface shadow disabled:opacity-50"
              onClick={() => {
                if (!map) return
                const chosen = map.getCenter() as naver.maps.LatLng
                manualLocation?.onUseSpot({ lat: chosen.lat(), lng: chosen.lng() })
                setChoosingLocation(false)
              }}
            >
              Use this spot
            </button>
          </>
        )}

        {!ready && !visibleError && (
          <div
            data-testid="map-loading"
            className="absolute inset-0 grid place-items-center bg-surface text-sm text-primary"
          >
            Loading map…
          </div>
        )}

        {visibleError && (
          <div
            data-testid="map-error"
            role="alert"
            className="absolute inset-0 grid place-items-center bg-surface p-6 text-center text-sm text-primary"
          >
            <div>
              <p className="font-medium">The map could not be loaded.</p>
              <p className="mt-2 text-primary">{visibleError.message}</p>
            </div>
          </div>
        )}
      </div>

      {facilities.length > 0 && (
        <ul data-testid="facility-list" className="space-y-1 text-sm">
          {facilities.map((facility) => (
            <li key={facility.id}>
              <button
                type="button"
                className="flex min-h-11 w-full items-center justify-between gap-3 rounded-lg px-2 text-left text-primary hover:bg-muted focus-visible:outline-2 focus-visible:outline-offset-2"
                onClick={() => setSelectedFacility(facility)}
              >
                <span className="max-w-[35%] shrink-0 truncate text-primary" lang="ko">
                  {facility.nameKo}
                </span>
                <span className="flex min-w-0 flex-1 items-center justify-end gap-1 text-right">
                  <span aria-hidden="true" className="shrink-0 font-bold">
                    {operationGlyph(facility)}
                  </span>
                  <span className="min-w-0">
                    {facilityTypeLabel(facility)} · {openLabel(facility)} ·{' '}
                    {Math.round(facility.distanceMeters)}m from map centre
                  </span>
                  <ChevronRight aria-hidden="true" className="shrink-0" size={16} />
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}

      {selectedFacility && (
        <DetailDrawer
          facility={selectedFacility}
          onClose={() => setSelectedFacility(null)}
        />
      )}
    </section>
  )
}
