import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { NearbyFacilities } from './NearbyFacilities'
import type { Facility } from '../lib/types'

/**
 * `NearbyFacilities` turns the assistant's OPEN_FACILITY_MAP payload into a located, fetched,
 * rendered map. What must never regress: a refused location says so out loud, a failed fetch is
 * an alert rather than silence, and an empty result is a sentence — not a blank map that looks
 * like the app broke.
 *
 * `FacilityMap` needs the Naver SDK, which is `FacilityMap.test.tsx`'s business; here it is a
 * stub that echoes its props.
 */
const { resolveLocationMock, fetchFacilitiesMock } = vi.hoisted(() => ({
  resolveLocationMock: vi.fn(),
  fetchFacilitiesMock: vi.fn(),
}))
vi.mock('../lib/facilities', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../lib/facilities')>()
  return { ...actual, resolveLocation: resolveLocationMock, fetchFacilities: fetchFacilitiesMock }
})
vi.mock('./FacilityMap', () => ({
  FacilityMap: (props: { facilities?: Facility[]; notice?: string; caption?: string }) => (
    <div data-testid="map-stub">
      <span data-testid="stub-count">{props.facilities?.length ?? 0}</span>
      {props.notice && <span data-testid="stub-notice">{props.notice}</span>}
      {props.caption && <span data-testid="stub-caption">{props.caption}</span>}
    </div>
  ),
}))

const pharmacy = {
  id: 'facility:nmc:1',
  type: 'pharmacy',
  nameKo: '가나약국',
  latitude: 37.5,
  longitude: 127.0,
  distanceMeters: 120,
  operation: { isOpenNow: true, status: 'open', statusConfidence: 'inferred', verifiedAt: null, notice: '' },
} as Facility

const props = { types: ['pharmacy'], radiusM: 1000, openNow: true }

afterEach(() => {
  resolveLocationMock.mockReset()
  fetchFacilitiesMock.mockReset()
})

describe('NearbyFacilities', () => {
  it('says it is finding the location before anything else', () => {
    resolveLocationMock.mockReturnValue(new Promise(() => {})) // never resolves
    render(<NearbyFacilities {...props} />)

    expect(screen.getByText(/finding your location/i)).toBeInTheDocument()
  })

  it('passes the fetched facilities to the map, with the request echoed as a caption', async () => {
    resolveLocationMock.mockResolvedValue({ lat: 37.5, lng: 127.0, fromDevice: true })
    fetchFacilitiesMock.mockResolvedValue([pharmacy])
    render(<NearbyFacilities {...props} />)

    expect(await screen.findByTestId('stub-count')).toHaveTextContent('1')
    expect(screen.getByTestId('stub-caption')).toHaveTextContent('pharmacies within 1000m, open now')
    expect(screen.queryByTestId('stub-notice')).not.toBeInTheDocument()
  })

  it('says out loud when the centre is a fallback, not the user (§ honesty about location)', async () => {
    resolveLocationMock.mockResolvedValue({ lat: 37.5663, lng: 126.9779, fromDevice: false })
    fetchFacilitiesMock.mockResolvedValue([])
    render(<NearbyFacilities {...props} />)

    expect(await screen.findByTestId('stub-notice')).toHaveTextContent(/could not read your location/i)
  })

  it('renders a fetch failure as an alert, not silence', async () => {
    resolveLocationMock.mockResolvedValue({ lat: 37.5, lng: 127.0, fromDevice: true })
    fetchFacilitiesMock.mockRejectedValue(new Error('Hospital search is not available yet.'))
    render(<NearbyFacilities {...props} />)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Hospital search is not available yet.')
  })

  it('says in words that nothing was found, instead of showing a bare map', async () => {
    resolveLocationMock.mockResolvedValue({ lat: 37.5, lng: 127.0, fromDevice: true })
    fetchFacilitiesMock.mockResolvedValue([])
    render(<NearbyFacilities {...props} />)

    expect(
      await screen.findByText(/no pharmacies found within 1000m/i),
    ).toBeInTheDocument()
  })
})
