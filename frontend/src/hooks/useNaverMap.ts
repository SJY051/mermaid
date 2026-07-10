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
 *
 * ## You cannot check a Naver key with curl
 *
 * Measured in a real browser, 2026-07-10, with a deliberately wrong key:
 *
 *   - `maps.js` answers 200 with the same 333KB of JavaScript
 *   - `script.onload` fires
 *   - `window.naver.maps` is defined
 *   - `new naver.maps.Map(...)` constructs without throwing
 *   - and only THEN does the SDK call `window.navermap_authFailure`
 *
 * Every check a server-side script can make passes. The callback is the only signal, which is
 * why it is registered before the script is appended, and why a component that ignores it shows
 * the user a blank grey box and calls it a map.
 */

const SCRIPT_ID = 'naver-maps-sdk'

export const AUTH_FAILURE_MESSAGE =
  'Naver Maps rejected the key. Check VITE_NAVER_MAP_CLIENT_ID, and that http://localhost:5173 ' +
  'is registered in the NCP application’s Web service URL allowlist.'

/**
 * Everyone waiting to hear that authentication failed.
 *
 * The SDK calls one global, once, whoever is listening. A module-level set keeps `window` with a
 * single owner, so a second component mounting a map cannot overwrite the first one's handler.
 */
const authFailureListeners = new Set<(e: Error) => void>()

if (typeof window !== 'undefined') {
  window.navermap_authFailure = () => {
    const error = new Error(AUTH_FAILURE_MESSAGE)
    authFailureListeners.forEach((notify) => notify(error))
  }
}

function loadNaverMapsScript(keyId: string): Promise<void> {
  const existing = document.getElementById(SCRIPT_ID)
  if (existing) {
    return window.naver?.maps
      ? Promise.resolve()
      : new Promise((resolve) => existing.addEventListener('load', () => resolve()))
  }

  return new Promise((resolve, reject) => {
    const script = document.createElement('script')
    script.id = SCRIPT_ID
    script.async = true
    script.src = `https://oapi.map.naver.com/openapi/v3/maps.js?ncpKeyId=${keyId}&language=en`
    script.onload = () => {
      // `window.naver.maps` exists even for a key Naver rejects — measured. This guards only the
      // case where the script body failed to execute; it is NOT the authentication check.
      if (window.naver?.maps) resolve()
      else reject(new Error('Naver Maps loaded but defined no `naver.maps` namespace.'))
    }
    script.onerror = () => reject(new Error('Naver Maps script could not be fetched. Are you offline?'))
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
    const keyId = import.meta.env.VITE_NAVER_MAP_CLIENT_ID as string | undefined
    if (!keyId) {
      setError(new Error('VITE_NAVER_MAP_CLIENT_ID is not set. Copy .env.example to .env.'))
      return
    }

    let cancelled = false

    // Register before loading. The SDK authenticates as it runs, and a key it rejects would
    // otherwise fail silently — `ready` true, tiles never painted, no error anywhere.
    const onAuthFailure = (e: Error) => {
      if (!cancelled) {
        setReady(false)
        setError(e)
      }
    }
    authFailureListeners.add(onAuthFailure)

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
      authFailureListeners.delete(onAuthFailure)
    }
    // Re-creating the map on every centre change would throw away the user's panning.
    // Move the camera with `map.setCenter()` instead.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return { containerRef, map: mapRef.current, ready, error }
}

/**
 * Pins are `FacilityMap`'s job, not this hook's — it owns only the SDK and the map instance.
 *
 * If the pin count ever grows: there is no npm package for Naver marker clustering, so copy
 * `MarkerClustering.js` out of github.com/navermaps/marker-tools.js. A 500m radius yields tens of
 * markers, so it can wait.
 */
