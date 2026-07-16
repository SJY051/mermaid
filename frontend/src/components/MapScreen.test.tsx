import { useState } from 'react'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { MapScreen } from './MapScreen'
import type { Facility, GeocodeResult } from '../lib/types'
import { loadPreferences, setManualLocation } from '../lib/storage'

/**
 * `FacilityMap` needs the Naver SDK, which is `FacilityMap.test.tsx`'s business; here it is a
 * stub that echoes the facilities, status text, caption, and location notice it receives.
 */
const { resolveLocationMock, fetchFacilitiesMock, fetchGeocodeMock } = vi.hoisted(() => ({
  resolveLocationMock: vi.fn(),
  fetchFacilitiesMock: vi.fn(),
  fetchGeocodeMock: vi.fn(),
}))
vi.mock('../lib/facilities', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../lib/facilities')>()
  return {
    ...actual,
    resolveLocation: resolveLocationMock,
    fetchFacilities: fetchFacilitiesMock,
    fetchGeocode: fetchGeocodeMock,
  }
})
vi.mock('./FacilityMap', () => ({
  FacilityMap: (props: {
    facilities?: Facility[]
    additionalFixtureData?: boolean
    notice?: string
    caption?: string
    manualLocation?: {
      canClear: boolean
      onUseSpot: (center: { lat: number; lng: number }) => void
      onSearchAddress: (query: string) => Promise<GeocodeResult[]>
      onUseAddress: (result: GeocodeResult) => void
      currentLabel?: string
      onClear: () => void
    }
  }) => {
    const [query, setQuery] = useState('')
    const [results, setResults] = useState<GeocodeResult[]>([])
    return (
      <div
        data-testid="map-stub"
        data-facility-ids={(props.facilities ?? []).map((facility) => facility.id).join(',')}
      >
        {props.caption && <span data-testid="map-caption">{props.caption}</span>}
        {props.notice && <span data-testid="map-notice">{props.notice}</span>}
        {props.additionalFixtureData && (
          <span data-testid="map-fixture-notice">
            Sample data — availability may not reflect current conditions.
          </span>
        )}
        {props.manualLocation && (
          <>
            <button type="button">Set your location</button>
            <button type="button" onClick={() => props.manualLocation!.onUseSpot({ lat: 35.1796, lng: 129.0756 })}>
              Use this spot
            </button>
            <label>
              Search an address
              <input value={query} onChange={(event) => setQuery(event.target.value)} />
            </label>
            <button
              type="button"
              onClick={async () => setResults(await props.manualLocation!.onSearchAddress(query))}
            >
              Search
            </button>
            {results.map((result) => (
              <button
                key={`${result.latitude}:${result.longitude}`}
                type="button"
                onClick={() => props.manualLocation!.onUseAddress(result)}
              >
                {result.roadAddress}
              </button>
            ))}
            {props.manualLocation.currentLabel && (
              <span>Chosen centre: {props.manualLocation.currentLabel}</span>
            )}
            {props.manualLocation.canClear && (
              <button type="button" onClick={props.manualLocation.onClear}>Clear</button>
            )}
          </>
        )}
        {(props.facilities ?? []).map((facility) => (
          <span key={facility.id} data-testid={`map-facility-${facility.id}`}>
            {facility.nameKo}:{' '}
            {facility.operation.isOpenNow === true
              ? 'Open now'
              : facility.operation.isOpenNow === false
                ? 'Closed'
                : 'Hours unknown'}
          </span>
        ))}
      </div>
    )
  },
}))

function facility(
  id: string,
  nameKo: string,
  isOpenNow: boolean | null,
  phone: string | null = null,
): Facility {
  return {
    id,
    type: 'pharmacy',
    nameKo,
    nameEn: null,
    addressKo: null,
    addressEn: null,
    phone,
    latitude: 37.5,
    longitude: 127,
    distanceMeters: 100,
    operation: {
      isOpenNow,
      status: isOpenNow === true ? 'open' : isOpenNow === false ? 'closed' : 'unknown',
      statusConfidence: 'official_schedule',
      verifiedAt: null,
      notice: '',
    },
    source: {
      id: `source:${id}`,
      provider: 'test',
      recordId: id,
      retrievedAt: '2026-07-13T00:00:00Z',
      dataMode: 'fixture',
      title: 'Test fixture',
    },
  }
}

function notImplementedError(): Error {
  return Object.assign(new Error('Hospital search is not available yet.'), {
    status: 501,
    code: 'NOT_IMPLEMENTED',
  })
}

afterEach(() => {
  localStorage.clear()
  resolveLocationMock.mockReset()
  fetchFacilitiesMock.mockReset()
  fetchGeocodeMock.mockReset()
})

describe('MapScreen', () => {
  it('renders the wireframe facility segments in order, with Emergency rooms available', async () => {
    resolveLocationMock.mockResolvedValue({ lat: 37.5, lng: 127, source: 'device' })
    fetchFacilitiesMock.mockResolvedValue([])

    render(<MapScreen active={true} />)

    const filters = await screen.findByRole('group', { name: 'Facility type' })
    const segments = within(filters).getAllByRole('button')
    expect(segments.map((segment) => segment.textContent?.trim())).toEqual([
      'All',
      'Pharmacies',
      'Hospitals',
      'ER',
    ])
    expect(within(filters).getByRole('button', { name: 'Emergency rooms' })).toBeEnabled()
    await waitFor(() => {
      expect(fetchFacilitiesMock.mock.calls.map(([query]) => query.type)).toEqual([
        'pharmacy',
        'hospital',
        'emergency_room',
      ])
    })
  })

  it('keeps other All results visible when the emergency-room request fails', async () => {
    const pharmacy = facility('pharmacy', '가나약국', true)
    resolveLocationMock.mockResolvedValue({ lat: 37.5, lng: 127, source: 'device' })
    fetchFacilitiesMock.mockImplementation(({ type }: { type: string }) => {
      if (type === 'pharmacy') return Promise.resolve([pharmacy])
      if (type === 'emergency_room') {
        return Promise.reject(new Error('Emergency-room lookup failed.'))
      }
      return Promise.resolve([])
    })

    render(<MapScreen active={true} />)

    expect(await screen.findByTestId(`map-facility-${pharmacy.id}`)).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('Emergency-room lookup failed.')
    expect(screen.queryByText(/No facilities found/i)).not.toBeInTheDocument()
    expect(
      screen.queryByText('1 facilities · 1 Open now · 0 Hours unknown'),
    ).not.toBeInTheDocument()
  })

  it('loads emergency-room fixture results with open_now off and disables Open now', async () => {
    const user = userEvent.setup()
    const emergencyRoom = facility(
      'facility:nmc-emergency:A1100006',
      '강북삼성병원',
      null,
      '02-2001-2001',
    )
    emergencyRoom.type = 'emergency_room'
    resolveLocationMock.mockResolvedValue({ lat: 37.5, lng: 127, source: 'device' })
    fetchFacilitiesMock.mockImplementation(({ type }: { type: string }) =>
      Promise.resolve(type === 'emergency_room' ? [emergencyRoom] : []),
    )

    render(<MapScreen active={true} />)

    const openNow = await screen.findByRole('switch', { name: 'Open now' })
    await user.click(openNow)
    expect(openNow).toHaveAttribute('aria-checked', 'true')

    const erButton = screen.getByRole('button', { name: 'Emergency rooms' })
    await waitFor(() => expect(fetchFacilitiesMock).toHaveBeenCalledTimes(3))
    await user.click(erButton)

    expect(erButton).toHaveAttribute('aria-pressed', 'true')
    expect(openNow).toBeDisabled()
    expect(openNow).toHaveAttribute('aria-checked', 'false')
    await waitFor(() => expect(fetchFacilitiesMock).toHaveBeenCalledTimes(4))
    expect(fetchFacilitiesMock.mock.calls[3][0]).toEqual(
      expect.objectContaining({ type: 'emergency_room', operationPreference: 'any' }),
    )
    expect(await screen.findByTestId(`map-facility-${emergencyRoom.id}`)).toHaveTextContent(
      'Hours unknown',
    )
    expect(screen.getByText('1 emergency room · 0 Open now · 1 Hours unknown')).toBeInTheDocument()
    expect(screen.getByTestId('map-fixture-notice')).toHaveTextContent('Sample data')
    expect(
      screen.getByText(
        'Opening hours are not available for these official emergency-room records. Call before you go.',
      ),
    ).toBeInTheDocument()
  })

  it('keeps emergency-room loading distinct and uses safety copy for an empty result', async () => {
    const user = userEvent.setup()
    let settleEmergencyRooms!: (facilities: Facility[]) => void
    resolveLocationMock.mockResolvedValue({ lat: 37.5, lng: 127, source: 'device' })
    let emergencyRoomRequests = 0
    fetchFacilitiesMock.mockImplementation(({ type }: { type: string }) => {
      if (type !== 'emergency_room') return Promise.resolve([])
      emergencyRoomRequests += 1
      return emergencyRoomRequests === 1
        ? Promise.resolve([])
        : new Promise<Facility[]>((resolve) => {
            settleEmergencyRooms = resolve
          })
    })

    render(<MapScreen active={true} />)
    await user.click(await screen.findByRole('button', { name: 'Emergency rooms' }))

    expect(screen.getByText('Loading emergency rooms…')).toBeInTheDocument()
    expect(
      screen.queryByText(
        'No facilities matching these filters were found. Try changing the filters or contacting a local health service.',
      ),
    ).not.toBeInTheDocument()

    settleEmergencyRooms([])
    expect(
      await screen.findByText(
        'No facilities matching these filters were found. Try changing the filters or contacting a local health service.',
      ),
    ).toBeInTheDocument()
    expect(screen.queryByText('No emergency rooms found within 1000m.')).not.toBeInTheDocument()
    expect(screen.queryByText('Loading emergency rooms…')).not.toBeInTheDocument()
  })

  it('renders an emergency-room fetch failure as an alert, not an empty result', async () => {
    const user = userEvent.setup()
    resolveLocationMock.mockResolvedValue({ lat: 37.5, lng: 127, source: 'device' })
    let emergencyRoomRequests = 0
    fetchFacilitiesMock.mockImplementation(({ type }: { type: string }) => {
      if (type !== 'emergency_room') return Promise.resolve([])
      emergencyRoomRequests += 1
      return emergencyRoomRequests === 1
        ? Promise.resolve([])
        : Promise.reject(new Error('Emergency-room lookup failed.'))
    })

    render(<MapScreen active={true} />)
    await user.click(await screen.findByRole('button', { name: 'Emergency rooms' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Emergency-room lookup failed.')
    expect(screen.queryByText(/No emergency rooms found/i)).not.toBeInTheDocument()
  })

  it('omits the map intro copy that has no wireframe counterpart', async () => {
    resolveLocationMock.mockResolvedValue({ lat: 37.5, lng: 127, source: 'device' })
    fetchFacilitiesMock.mockResolvedValue([])

    render(<MapScreen active={true} />)

    await screen.findByTestId('map-stub')
    await waitFor(() => expect(fetchFacilitiesMock).toHaveBeenCalledTimes(3))
    expect(
      screen.queryByText('Nearby pharmacies and hospitals will appear here.'),
    ).not.toBeInTheDocument()
  })

  it('uses safety copy when All is empty after hospital lookup is unavailable', async () => {
    resolveLocationMock.mockResolvedValue({ lat: 37.5, lng: 127, source: 'device' })
    fetchFacilitiesMock.mockImplementation(({ type }: { type: string }) =>
      type === 'hospital' ? Promise.reject(notImplementedError()) : Promise.resolve([]),
    )

    render(<MapScreen active={true} />)

    expect(
      await screen.findByText(
        'No facilities matching these filters were found. Try changing the filters or contacting a local health service.',
      ),
    ).toBeInTheDocument()
    expect(screen.queryByText('No facilities found within 1000m.')).not.toBeInTheDocument()
    expect(
      screen.queryByText('0 facilities · 0 Open now · 0 Hours unknown'),
    ).not.toBeInTheDocument()
  })

  it('renders null opening hours as Hours unknown, never Closed', async () => {
    const user = userEvent.setup()
    const unknown = facility('unknown', '미상약국', null, '02-111-2222')
    resolveLocationMock.mockResolvedValue({ lat: 37.5663, lng: 126.9779, source: 'fallback' })
    fetchFacilitiesMock.mockImplementation(({ type }: { type: string }) =>
      type === 'hospital'
        ? Promise.reject(notImplementedError())
        : Promise.resolve(type === 'pharmacy' ? [unknown] : []),
    )

    render(<MapScreen active={true} />)

    const unknownPin = await screen.findByTestId('map-facility-unknown')
    expect(unknownPin).toHaveTextContent('Hours unknown')
    expect(unknownPin).not.toHaveTextContent('Closed')
    expect(screen.queryByText('Closed')).not.toBeInTheDocument()
    expect(
      screen.queryByText('1 facilities · 0 Open now · 1 Hours unknown'),
    ).not.toBeInTheDocument()
    expect(screen.getByTestId('map-notice')).toHaveTextContent(
      'Centred on Seoul City Hall — we could not read your location, so these are not near you.',
    )

    const hospitalsButton = screen.getByRole('button', { name: 'Hospitals' })
    await user.click(hospitalsButton)

    expect(hospitalsButton).toHaveAttribute('aria-pressed', 'true')
    await waitFor(() => {
      const hospitalCalls = fetchFacilitiesMock.mock.calls.filter(
        ([query]) => query.type === 'hospital',
      )
      expect(hospitalCalls).toHaveLength(2)
    })
    expect(
      await screen.findByText(
        'Hospital search is unavailable right now. Pharmacy and emergency-room search may still work.',
      ),
    ).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('splits open-now results client-side so unknown hours remain available to call', async () => {
    const user = userEvent.setup()
    const open = facility('open', '영업약국', true)
    const unknown = facility('unknown', '미상약국', null, '02-333-4444')
    const closed = facility('closed', '닫힘약국', false)
    open.source.dataMode = 'live'
    unknown.source.dataMode = 'live'
    resolveLocationMock.mockResolvedValue({ lat: 37.5, lng: 127, source: 'device' })
    fetchFacilitiesMock.mockImplementation(({ type }: { type: string }) =>
      type === 'hospital'
        ? Promise.reject(notImplementedError())
        : Promise.resolve(type === 'pharmacy' ? [closed, unknown, open] : []),
    )

    render(<MapScreen active={true} />)

    const map = await screen.findByTestId('map-stub')
    await waitFor(() => expect(map).toHaveAttribute('data-facility-ids', 'open,unknown,closed'))
    expect(fetchFacilitiesMock).toHaveBeenCalledTimes(3)

    // Mutation guard: passing the switch value through to the API must turn this assertion red.
    for (const [query] of fetchFacilitiesMock.mock.calls) {
      expect(query).toEqual(expect.objectContaining({ operationPreference: 'any' }))
      expect(query).not.toHaveProperty('openNow')
    }

    await user.click(screen.getByRole('switch', { name: 'Open now' }))

    await waitFor(() => expect(map).toHaveAttribute('data-facility-ids', 'open'))
    expect(screen.queryByTestId('map-facility-closed')).not.toBeInTheDocument()
    expect(screen.queryByText('닫힘약국')).not.toBeInTheDocument()
    expect(screen.getByTestId('map-fixture-notice')).toHaveTextContent(
      'Sample data — availability may not reflect current conditions.',
    )

    const heading = screen.getByRole('heading', {
      name: 'Hours unknown — call to confirm (1)',
    })
    const unknownList = heading.closest('section')
    expect(unknownList).not.toBeNull()

    const unknownName = within(unknownList!).getByText('미상약국')
    expect(unknownName).toHaveAttribute('lang', 'ko')
    const row = unknownName.closest('li')
    expect(row).not.toBeNull()
    expect(within(row!).getByText('Hours unknown')).toBeInTheDocument()
    expect(within(row!).getByRole('link', { name: '02-333-4444' })).toHaveAttribute(
      'href',
      'tel:02-333-4444',
    )

    for (const [query] of fetchFacilitiesMock.mock.calls) {
      expect(query).toEqual(expect.objectContaining({ operationPreference: 'any' }))
      expect(query).not.toHaveProperty('openNow')
    }
  })

  it('does not ask for location or fetch until the tab is active', async () => {
    resolveLocationMock.mockResolvedValue({ lat: 37.5, lng: 127, source: 'device' })
    fetchFacilitiesMock.mockResolvedValue([])

    // The shell mounts every screen; an inactive Map must not prompt for location or spend the
    // pharmacy quota. Mutation guard: dropping the `active` gate must turn this red.
    const { rerender } = render(<MapScreen active={false} />)
    await Promise.resolve()
    expect(resolveLocationMock).not.toHaveBeenCalled()
    expect(fetchFacilitiesMock).not.toHaveBeenCalled()

    rerender(<MapScreen active={true} />)
    await waitFor(() => expect(fetchFacilitiesMock).toHaveBeenCalled())
    expect(resolveLocationMock).not.toHaveBeenCalled()
  })

  it('refetches from the pin centre after geolocation was denied', async () => {
    const user = userEvent.setup()
    resolveLocationMock.mockResolvedValue({ lat: 37.5663, lng: 126.9779, source: 'fallback' })
    fetchFacilitiesMock.mockImplementation(({ type }: { type: string }) =>
      type === 'hospital' ? Promise.reject(notImplementedError()) : Promise.resolve([]),
    )

    render(<MapScreen active={true} />)
    await screen.findByRole('button', { name: 'Set your location' })
    await user.click(screen.getByRole('button', { name: 'Use this spot' }))

    await waitFor(() => {
      const newCentreCalls = fetchFacilitiesMock.mock.calls.filter(
        ([query]) => query.lat === 35.1796 && query.lng === 129.0756,
      )
      expect(newCentreCalls).toHaveLength(3)
    })
    expect(loadPreferences().manualLocation).toEqual({
      lat: 35.1796,
      lng: 129.0756,
      label: 'Chosen map spot',
    })
  })

  it('searches an address, shows its label, and refetches facilities from its coordinates', async () => {
    const user = userEvent.setup()
    const result = {
      roadAddress: '서울특별시 중구 세종대로 110',
      jibunAddress: '서울특별시 중구 태평로1가 31',
      englishAddress: '110 Sejong-daero, Jung-gu, Seoul',
      latitude: 37.5666103,
      longitude: 126.9783882,
    }
    resolveLocationMock.mockResolvedValue({ lat: 37.5663, lng: 126.9779, source: 'fallback' })
    fetchGeocodeMock.mockResolvedValue([result])
    fetchFacilitiesMock.mockImplementation(({ type }: { type: string }) =>
      type === 'hospital' ? Promise.reject(notImplementedError()) : Promise.resolve([]),
    )

    render(<MapScreen active={true} />)
    await user.type(await screen.findByLabelText('Search an address'), 'Seoul City Hall')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    await user.click(await screen.findByRole('button', { name: result.roadAddress }))

    expect(fetchGeocodeMock).toHaveBeenCalledWith('Seoul City Hall')
    await waitFor(() => {
      const pickedCentreCalls = fetchFacilitiesMock.mock.calls.filter(
        ([query]) => query.lat === result.latitude && query.lng === result.longitude,
      )
      expect(pickedCentreCalls).toHaveLength(3)
    })
    expect(loadPreferences().manualLocation).toEqual({
      lat: result.latitude,
      lng: result.longitude,
      label: result.roadAddress,
    })
    expect(screen.getByText(`Chosen centre: ${result.roadAddress}`)).toBeInTheDocument()
    const notice = screen.getByTestId('map-notice')
    expect(notice).toHaveTextContent('spot you chose')
    expect(notice).not.toHaveTextContent('your location')
  })

  it('renders an honest notice for every location source', async () => {
    const user = userEvent.setup()
    fetchFacilitiesMock.mockImplementation(({ type }: { type: string }) =>
      type === 'hospital' ? Promise.reject(notImplementedError()) : Promise.resolve([]),
    )

    setManualLocation({ lat: 35.1, lng: 129.1, label: 'Chosen map spot' })
    const manual = render(<MapScreen active={true} />)
    const manualNotice = await screen.findByTestId('map-notice')
    expect(manualNotice).toHaveTextContent('spot you chose')
    expect(manualNotice).not.toHaveTextContent('your location')
    expect(screen.queryByText('Centred on you')).not.toBeInTheDocument()
    manual.unmount()

    setManualLocation(null)
    const fallback = render(<MapScreen active={true} />)
    expect(await screen.findByTestId('map-notice')).toHaveTextContent(
      'Centred on Seoul City Hall — we could not read your location, so these are not near you.',
    )
    expect(screen.queryByText('Centred on you')).not.toBeInTheDocument()
    fallback.unmount()

    resolveLocationMock.mockResolvedValue({ lat: 37.5, lng: 127, source: 'device' })
    render(<MapScreen active={true} />)
    await user.click(screen.getByRole('button', { name: 'Use my location' }))
    await screen.findByTestId('map-stub')
    expect(screen.getByText('Centred on you')).toBeInTheDocument()
    expect(screen.queryByTestId('map-notice')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Set your location' })).not.toBeInTheDocument()
  })

  it('clears a manual pin and refetches from the fallback centre', async () => {
    const user = userEvent.setup()
    setManualLocation({ lat: 35.1, lng: 129.1, label: 'Chosen map spot' })
    resolveLocationMock.mockResolvedValue({ lat: 35.1, lng: 129.1, source: 'manual' })
    fetchFacilitiesMock.mockImplementation(({ type }: { type: string }) =>
      type === 'hospital' ? Promise.reject(notImplementedError()) : Promise.resolve([]),
    )

    render(<MapScreen active={true} />)
    await user.click(await screen.findByRole('button', { name: 'Clear' }))

    expect(await screen.findByTestId('map-notice')).toHaveTextContent('Centred on Seoul City Hall')
    await waitFor(() => {
      const fallbackCalls = fetchFacilitiesMock.mock.calls.filter(
        ([query]) => query.lat === 37.5663 && query.lng === 126.9779,
      )
      expect(fallbackCalls).toHaveLength(3)
    })
    expect(loadPreferences().manualLocation).toBeNull()
  })

})
