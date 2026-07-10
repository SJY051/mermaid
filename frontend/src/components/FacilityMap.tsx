import { useNaverMap } from '../hooks/useNaverMap'

export interface FacilityMapProps {
  center: { lat: number; lng: number }
  zoom?: number
  /** Rendered above the map. The assistant's own words about why it opened. */
  caption?: string
}

/**
 * The map (UI-02, DEV-206).
 *
 * <p>It renders three states, and never the fourth one a naive version has: a blank grey box that
 * looks like a map still loading. When the key is rejected the SDK says so through a global
 * callback, `useNaverMap` turns that into an `error`, and this component prints it.
 */
export function FacilityMap({ center, zoom = 15, caption }: FacilityMapProps) {
  const { containerRef, ready, error } = useNaverMap({ center, zoom })

  return (
    <section className="space-y-2" aria-label="Nearby facilities map">
      {caption && <p className="text-sm text-secondary">{caption}</p>}

      <div className="relative h-80 w-full overflow-hidden rounded-lg border border-primary">
        <div ref={containerRef} data-testid="naver-map" className="h-full w-full" />

        {!ready && !error && (
          <div
            data-testid="map-loading"
            className="absolute inset-0 grid place-items-center bg-secondary text-sm text-secondary"
          >
            Loading map…
          </div>
        )}

        {error && (
          <div
            data-testid="map-error"
            role="alert"
            className="absolute inset-0 grid place-items-center bg-secondary p-6 text-center text-sm text-primary"
          >
            <div>
              <p className="font-medium">The map could not be loaded.</p>
              <p className="mt-2 text-secondary">{error.message}</p>
            </div>
          </div>
        )}
      </div>
    </section>
  )
}
