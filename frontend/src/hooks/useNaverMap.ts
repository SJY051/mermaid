import { useEffect, useRef, useState } from 'react'

/**
 * Loads the Naver Maps JS SDK and creates a map in the given element.
 *
 * We attach the raw SDK rather than a React wrapper on purpose. The community package
 * `react-naver-maps@0.2.x` is the only one that peer-matches React 19, but it is a minimal
 * rewrite with a one-page README. Naver's own docs (navermaps.github.io) are extensive and
 * written against the raw `naver.maps` API — so when a teammate gets stuck, the raw API is
 * the one they can actually search their way out of.
 *
 * Two things every blog post gets wrong, because they predate the NCP migration:
 *   - the query parameter is `ncpKeyId`, not `ncpClientId` (which now fails auth outright)
 *   - the host is `oapi.map.naver.com`
 *
 * `language=en` switches every map label to English. That single parameter is most of why
 * we chose Naver over Kakao for an English-speaking audience.
 */

const SCRIPT_ID = 'naver-maps-sdk'

function loadNaverMapsScript(keyId: string): Promise<void> {
  if (document.getElementById(SCRIPT_ID)) {
    return window.naver?.maps
      ? Promise.resolve()
      : new Promise((resolve) => {
          document.getElementById(SCRIPT_ID)!.addEventListener('load', () => resolve())
        })
  }

  return new Promise((resolve, reject) => {
    const script = document.createElement('script')
    script.id = SCRIPT_ID
    script.async = true
    script.src = `https://oapi.map.naver.com/openapi/v3/maps.js?ncpKeyId=${keyId}&language=en`
    script.onload = () => resolve()
    script.onerror = () =>
      reject(
        new Error(
          'Naver Maps failed to load. Check VITE_NAVER_MAP_KEY_ID, and that http://localhost ' +
            'is registered in the NCP application’s Web service URL allowlist.',
        ),
      )
    document.head.appendChild(script)
  })
}

export interface UseNaverMapOptions {
  center: { lat: number; lng: number }
  zoom?: number
}

export function useNaverMap({ center, zoom = 15 }: UseNaverMapOptions) {
  const containerRef = useRef<HTMLDivElement>(null)
  const mapRef = useRef<naver.maps.Map | null>(null)
  const [ready, setReady] = useState(false)
  const [error, setError] = useState<Error | null>(null)

  useEffect(() => {
    const keyId = import.meta.env.VITE_NAVER_MAP_KEY_ID as string | undefined
    if (!keyId) {
      setError(new Error('VITE_NAVER_MAP_KEY_ID is not set. Copy .env.example to .env.'))
      return
    }

    let cancelled = false

    loadNaverMapsScript(keyId)
      .then(() => {
        if (cancelled || !containerRef.current || mapRef.current) return
        mapRef.current = new naver.maps.Map(containerRef.current, {
          center: new naver.maps.LatLng(center.lat, center.lng),
          zoom,
        })
        setReady(true)
      })
      .catch((e: Error) => {
        if (!cancelled) setError(e)
      })

    return () => {
      cancelled = true
    }
    // Re-creating the map on every centre change would throw away the user's panning.
    // Move the camera with `map.setCenter()` instead.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return { containerRef, map: mapRef.current, ready, error }
}

/**
 * TODO(team): render facility pins.
 *
 * Custom HTML markers are native — pass `icon: { content: '<div>…</div>', anchor }` to
 * `new naver.maps.Marker(...)`. For clustering there is no npm package; copy
 * `MarkerClustering.js` out of github.com/navermaps/marker-tools.js. With a 500m radius you
 * will have tens of markers, so clustering can wait.
 */
