import { useEffect, useRef, useState } from 'react'
import { useNaverMap } from '../hooks/useNaverMap'
import type { Facility } from '../lib/types'
import { DetailDrawer } from './DetailDrawer'

export interface FacilityMapProps {
  center: { lat: number; lng: number }
  zoom?: number
  facilities?: Facility[]
  /** Rendered above the map. The assistant's own words about why it opened. */
  caption?: string
  /** Shown when the centre is a fallback rather than the user's position. */
  notice?: string
}

/** `null` means "we could not tell", and it must never be drawn as "Closed" (spec §2-13). */
function openLabel(facility: Facility): string {
  if (facility.operation.isOpenNow === true) return 'Open now'
  if (facility.operation.isOpenNow === false) return 'Closed'
  return 'Hours unknown'
}

function markerColour(facility: Facility): string {
  if (facility.operation.isOpenNow === true) return '#2db400'
  if (facility.operation.isOpenNow === false) return '#8a8f98'
  return '#e0a800'
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
export function FacilityMap({ center, zoom = 15, facilities = [], caption, notice }: FacilityMapProps) {
  const { containerRef, map, ready, error } = useNaverMap({ center, zoom })
  const markersRef = useRef<naver.maps.Marker[]>([])
  const [selectedFacility, setSelectedFacility] = useState<Facility | null>(null)
  const [markerError, setMarkerError] = useState<Error | null>(null)

  const visibleError = error ?? markerError
  const hasFixtureData = facilities.some((facility) => facility.source.dataMode === 'fixture')

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

    let info: naver.maps.InfoWindow | null = null

    try {
      info = new naver.maps.InfoWindow({ content: '' })

      for (const facility of facilities) {
        const marker = new naver.maps.Marker({
          map,
          position: new naver.maps.LatLng(facility.latitude, facility.longitude),
          title: facility.nameKo,
          icon: {
            content:
              `<div style="width:14px;height:14px;border-radius:50%;border:2px solid #fff;` +
              `background:${markerColour(facility)};box-shadow:0 1px 3px rgba(0,0,0,.4)"></div>`,
            anchor: new naver.maps.Point(7, 7),
          },
        })

        naver.maps.Event.addListener(marker, 'click', () => {
          // The info window sits on Naver's own white panel, outside our theme. Without an explicit
          // colour it inherits the page's light-on-dark text and is unreadable.
          info!.setContent(
            `<div style="padding:8px 10px;font:13px/1.5 system-ui;max-width:220px;color:#1a1a1a">` +
              `<strong>${escapeHtml(facility.nameKo)}</strong><br/>` +
              `${escapeHtml(openLabel(facility))} · ${Math.round(facility.distanceMeters)}m<br/>` +
              // The name and phone come from a government API and reach this string as HTML.
              (facility.phone
                ? `<a style="color:#0b6bcb" href="tel:${escapeHtml(facility.phone)}">${escapeHtml(facility.phone)}</a>`
                : '') +
              `</div>`,
          )
          info!.open(map, marker)
        })

        markersRef.current.push(marker)
      }
    } catch {
      setMarkerError(
        new Error('The facility pins could not be loaded. Use the facility list below instead.'),
      )
    }

    return () => {
      try {
        info?.close()
      } catch {
        // A rejected Naver key can invalidate an InfoWindow before React removes it.
      }
      for (const marker of markersRef.current) {
        try {
          marker.setMap(null)
        } catch {
          // Keep external SDK cleanup failures from replacing the whole app with a blank screen.
        }
      }
      markersRef.current = []
    }
  }, [map, ready, facilities])

  return (
    <section className="space-y-2" aria-label="Nearby facilities map">
      {caption && <p className="text-sm text-secondary">{caption}</p>}
      {notice && <p className="text-xs text-secondary">{notice}</p>}
      {hasFixtureData && (
        <p data-testid="map-fixture-notice" className="text-sm text-primary">
          Sample data — availability may not reflect current conditions.
        </p>
      )}

      <div className="relative h-80 w-full overflow-hidden rounded-lg border border-primary">
        <div ref={containerRef} data-testid="naver-map" className="h-full w-full" />

        {!ready && !visibleError && (
          <div
            data-testid="map-loading"
            className="absolute inset-0 grid place-items-center bg-secondary text-sm text-secondary"
          >
            Loading map…
          </div>
        )}

        {visibleError && (
          <div
            data-testid="map-error"
            role="alert"
            className="absolute inset-0 grid place-items-center bg-secondary p-6 text-center text-sm text-primary"
          >
            <div>
              <p className="font-medium">The map could not be loaded.</p>
              <p className="mt-2 text-secondary">{visibleError.message}</p>
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
                className="flex min-h-11 w-full items-center justify-between gap-3 rounded-lg px-2 text-left text-secondary hover:bg-secondary focus-visible:outline-2 focus-visible:outline-offset-2"
                onClick={() => setSelectedFacility(facility)}
              >
                <span className="truncate text-primary" lang="ko">
                  {facility.nameKo}
                </span>
                <span className="shrink-0">
                  {openLabel(facility)} · {Math.round(facility.distanceMeters)}m
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
