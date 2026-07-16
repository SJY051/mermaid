import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchFacilities, fetchGeocode, resolveLocation, SEOUL_CITY_HALL } from './facilities'
import { savePreferences } from './storage'

function mockFetch(response: Partial<Response> & { json?: () => Promise<unknown> }) {
  const spy = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => [], ...response })
  vi.stubGlobal('fetch', spy)
  return spy
}

function requestedUrl(spy: ReturnType<typeof vi.fn>): URL {
  return new URL(spy.mock.calls[0][0] as string, 'http://localhost')
}

afterEach(() => {
  localStorage.clear()
  vi.unstubAllGlobals()
})

describe('fetchFacilities builds the request the backend actually documents', () => {
  it('sends snake_case query parameters, and lat/lng — not latitude/longitude', async () => {
    // The request takes lat/lng; the response answers latitude/longitude. The asymmetry is real.
    const spy = mockFetch({})
    await fetchFacilities({ lat: 37.5, lng: 127.02, radiusM: 500, openNow: true, type: 'pharmacy' })

    const url = requestedUrl(spy)
    expect(url.pathname).toBe('/api/v1/facilities')
    expect(url.searchParams.get('lat')).toBe('37.5')
    expect(url.searchParams.get('lng')).toBe('127.02')
    expect(url.searchParams.get('radius_m')).toBe('500')
    expect(url.searchParams.get('open_now')).toBe('true')
    expect(url.searchParams.get('type')).toBe('pharmacy')
    expect(url.searchParams.get('latitude')).toBeNull()
  })

  it('defaults to open pharmacies within 1km, not to every pharmacy in Korea', async () => {
    const spy = mockFetch({})
    await fetchFacilities({ lat: 37.5, lng: 127.02 })

    const url = requestedUrl(spy)
    expect(url.searchParams.get('radius_m')).toBe('1000')
    expect(url.searchParams.get('type')).toBe('pharmacy')
    expect(url.searchParams.get('open_now')).toBe('false')
  })

  it('forces emergency-room requests to open_now=false even when a caller asks for true', async () => {
    const spy = mockFetch({})
    await fetchFacilities({
      lat: 37.5,
      lng: 127.02,
      radiusM: 1000,
      openNow: true,
      type: 'emergency_room',
    })

    const url = requestedUrl(spy)
    expect(url.searchParams.get('type')).toBe('emergency_room')
    expect(url.searchParams.get('open_now')).toBe('false')
  })

  it('sends a tri-state operation preference without collapsing it to open_now', async () => {
    const spy = mockFetch({})
    await fetchFacilities({
      lat: 37.5,
      lng: 127.02,
      radiusM: 1000,
      operationPreference: 'open_or_unknown',
      type: 'emergency_room',
    })

    const url = requestedUrl(spy)
    expect(url.searchParams.get('operation_preference')).toBe('open_or_unknown')
    expect(url.searchParams.has('open_now')).toBe(false)
  })

  it('passes the abort signal through', async () => {
    const spy = mockFetch({})
    const controller = new AbortController()
    await fetchFacilities({ lat: 1, lng: 2 }, controller.signal)

    expect(spy.mock.calls[0][1]).toMatchObject({ signal: controller.signal })
  })
})

describe('fetchFacilities surfaces the server’s own words when it fails', () => {
  it('reports the 501 for hospitals rather than pretending there are none', async () => {
    // An empty array here would read as "no hospitals near you". The truth is that DEV-203
    // is not built. The backend answers 501 NOT_IMPLEMENTED so the UI cannot get that wrong.
    mockFetch({
      ok: false,
      status: 501,
      json: async () => ({
        error: { code: 'NOT_IMPLEMENTED', message: 'Hospital search is not available yet.', retryable: false },
      }),
    })

    await expect(fetchFacilities({ lat: 37.5, lng: 127.02, type: 'hospital' })).rejects.toThrow(
      'Hospital search is not available yet.',
    )
  })

  it('falls back to a readable message when the error body is not our envelope', async () => {
    mockFetch({
      ok: false,
      status: 502,
      json: async () => {
        throw new SyntaxError('Unexpected token <')
      },
    })

    await expect(fetchFacilities({ lat: 37.5, lng: 127.02 })).rejects.toThrow(
      'Could not load nearby pharmacys (HTTP 502).',
    )
  })
})

describe('fetchGeocode calls only our server', () => {
  it('sends the address as the query parameter and returns our coordinate shape', async () => {
    const match = {
      roadAddress: '서울특별시 중구 세종대로 110',
      jibunAddress: '서울특별시 중구 태평로1가 31',
      englishAddress: '110 Sejong-daero, Jung-gu, Seoul',
      latitude: 37.5666103,
      longitude: 126.9783882,
    }
    const spy = mockFetch({ json: async () => [match] })

    await expect(fetchGeocode(match.roadAddress)).resolves.toEqual([match])

    const url = requestedUrl(spy)
    expect(url.pathname).toBe('/api/v1/geocode')
    expect(url.searchParams.get('query')).toBe(match.roadAddress)
    expect(url.host).toBe('localhost')
  })

  it('rejects a failed search instead of turning it into no matches', async () => {
    mockFetch({
      ok: false,
      status: 503,
      json: async () => ({
        error: { code: 'SOURCE_UNAVAILABLE', message: 'Address search is unavailable.', retryable: true },
      }),
    })

    await expect(fetchGeocode('an address')).rejects.toThrow('Address search is unavailable.')
  })
})

describe('resolveLocation is honest about where the centre came from', () => {
  it('uses the device position even when a manual location is stored', async () => {
    savePreferences({
      rememberAllergies: false,
      allergies: [],
      defaultRadiusM: 1000,
      manualLocation: { lat: 35.1, lng: 129.1, label: 'Old pin' },
    })
    vi.stubGlobal('navigator', {
      geolocation: {
        getCurrentPosition: (onSuccess: PositionCallback) =>
          onSuccess({ coords: { latitude: 35.16, longitude: 129.06 } } as GeolocationPosition),
      },
    })

    await expect(resolveLocation()).resolves.toEqual({ lat: 35.16, lng: 129.06, source: 'device' })
  })

  it('uses a stored manual location when permission is refused', async () => {
    savePreferences({
      rememberAllergies: false,
      allergies: [],
      defaultRadiusM: 1000,
      manualLocation: { lat: 35.1, lng: 129.1, label: 'Chosen map spot' },
    })
    vi.stubGlobal('navigator', {
      geolocation: {
        getCurrentPosition: (_ok: PositionCallback, onError: PositionErrorCallback) =>
          onError({ code: 1, message: 'User denied Geolocation' } as GeolocationPositionError),
      },
    })

    await expect(resolveLocation()).resolves.toEqual({
      lat: 35.1,
      lng: 129.1,
      source: 'manual',
      label: 'Chosen map spot',
    })
  })

  it('falls back when the browser has no geolocation and no manual location', async () => {
    vi.stubGlobal('navigator', {})

    await expect(resolveLocation()).resolves.toEqual({ ...SEOUL_CITY_HALL, source: 'fallback' })
  })

  it('never reports a device source without device coordinates', async () => {
    vi.stubGlobal('navigator', {})
    const resolved = await resolveLocation()

    expect(resolved.source).toBe('fallback')
    expect(resolved.lat).toBeCloseTo(37.5663, 4)
  })
})
