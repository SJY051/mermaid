import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
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
  FacilityMap: (props: {
    facilities?: Facility[]
    notice?: string
    caption?: string
    manualLocation?: {
      canClear: boolean
      onUseSpot: (center: { lat: number; lng: number }) => void
      onClear: () => void
    }
  }) => (
    <div data-testid="map-stub">
      <span data-testid="stub-count">{props.facilities?.length ?? 0}</span>
      <span data-testid="stub-ids">{props.facilities?.map((facility) => facility.id).join(',')}</span>
      {props.notice && <span data-testid="stub-notice">{props.notice}</span>}
      {props.caption && <span data-testid="stub-caption">{props.caption}</span>}
      {props.manualLocation && (
        <button
          type="button"
          onClick={() => props.manualLocation!.onUseSpot({ lat: 35.1796, lng: 129.0756 })}
        >
          Use this spot
        </button>
      )}
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
  localStorage.clear()
  resolveLocationMock.mockReset()
  fetchFacilitiesMock.mockReset()
})

describe('NearbyFacilities', () => {
  it('does not ask on mount and starts from the honest fallback centre', async () => {
    fetchFacilitiesMock.mockResolvedValue([])
    render(<NearbyFacilities {...props} />)

    expect(resolveLocationMock).not.toHaveBeenCalled()
    expect(screen.queryByText('Finding your location…')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Use my location' })).toBeInTheDocument()
    expect(await screen.findByTestId('stub-notice')).toHaveTextContent(
      /could not read your location/i,
    )
  })

  it('does not claim that no facilities were found while the request is pending', async () => {
    let settleFetch!: (facilities: Facility[]) => void
    fetchFacilitiesMock.mockReturnValue(
      new Promise<Facility[]>((resolve) => {
        settleFetch = resolve
      }),
    )
    render(<NearbyFacilities {...props} />)

    expect(await screen.findByText('Loading facilities…')).toBeVisible()
    expect(screen.queryByText(/no pharmacies found/i)).not.toBeInTheDocument()

    settleFetch([])
    expect(await screen.findByText(/no pharmacies found within 1000m/i)).toBeVisible()
  })

  it('passes the fetched facilities to the map, with the request echoed as a caption', async () => {
    const user = userEvent.setup()
    resolveLocationMock.mockResolvedValue({ lat: 37.5, lng: 127.0, source: 'device' })
    fetchFacilitiesMock.mockResolvedValue([pharmacy])
    render(<NearbyFacilities {...props} />)

    await user.click(screen.getByRole('button', { name: 'Use my location' }))

    // The map renders as soon as the location resolves, with no facilities yet — the fetch lands a
    // microtask later. Awaiting the ELEMENT therefore races the data: findBy resolves on the empty
    // map and the count is 0 about half the time. Wait for the content, which is what is asserted.
    await waitFor(() => expect(screen.getByTestId('stub-count')).toHaveTextContent('1'))
    expect(screen.getByTestId('stub-caption')).toHaveTextContent('pharmacies within 1000m, open now')
    expect(screen.queryByTestId('stub-notice')).not.toBeInTheDocument()
  })

  it('says out loud when the centre is a fallback, not the user (§ honesty about location)', async () => {
    resolveLocationMock.mockResolvedValue({ lat: 37.5663, lng: 126.9779, source: 'fallback' })
    fetchFacilitiesMock.mockResolvedValue([])
    render(<NearbyFacilities {...props} />)

    expect(await screen.findByTestId('stub-notice')).toHaveTextContent(/could not read your location/i)
  })

  it('renders a fetch failure as an alert, not silence', async () => {
    resolveLocationMock.mockResolvedValue({ lat: 37.5, lng: 127.0, source: 'device' })
    fetchFacilitiesMock.mockRejectedValue(new Error('Hospital search is not available yet.'))
    render(<NearbyFacilities {...props} />)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Hospital search is not available yet.')
  })

  it('says in words that nothing was found, instead of showing a bare map', async () => {
    resolveLocationMock.mockResolvedValue({ lat: 37.5, lng: 127.0, source: 'device' })
    fetchFacilitiesMock.mockResolvedValue([])
    render(<NearbyFacilities {...props} />)

    expect(
      await screen.findByText(/no pharmacies found within 1000m/i),
    ).toBeInTheDocument()
  })

  it('refetches the chat map facilities from a manually chosen centre', async () => {
    const user = userEvent.setup()
    resolveLocationMock.mockResolvedValue({ lat: 37.5663, lng: 126.9779, source: 'fallback' })
    fetchFacilitiesMock.mockResolvedValue([])
    render(<NearbyFacilities {...props} />)

    await user.click(await screen.findByRole('button', { name: 'Use this spot' }))

    await waitFor(() => {
      expect(fetchFacilitiesMock).toHaveBeenCalledWith(
        expect.objectContaining({ lat: 35.1796, lng: 129.0756 }),
        expect.any(AbortSignal),
      )
    })
  })

  it('treats an emergency-room chat action as hours-unknown even when openNow is requested', async () => {
    fetchFacilitiesMock.mockResolvedValue([])

    render(
      <NearbyFacilities types={['emergency_room']} radiusM={1000} openNow={true} />,
    )

    await waitFor(() => {
      expect(fetchFacilitiesMock).toHaveBeenCalledWith(
        expect.objectContaining({
          type: 'emergency_room',
          operationPreference: 'open_or_unknown',
        }),
        expect.any(AbortSignal),
      )
    })
    expect(screen.getByTestId('stub-caption')).toHaveTextContent(
      'emergency rooms within 1000m',
    )
    expect(screen.getByTestId('stub-caption')).not.toHaveTextContent('open now')
    expect(
      await screen.findByText(
        'No facilities matching these filters were found. Try changing the filters or contacting a local health service.',
      ),
    ).toBeInTheDocument()
    expect(screen.queryByText('No emergency rooms found within 1000m.')).not.toBeInTheDocument()
    expect(
      screen.getByText(
        'Opening hours are not available for these official emergency-room records. Call before you go.',
      ),
    ).toBeInTheDocument()
  })

  it('passes open-or-unknown through without collapsing it to a legacy boolean', async () => {
    const closed = {
      ...pharmacy,
      id: 'closed',
      operation: { ...pharmacy.operation, isOpenNow: false, status: 'closed' },
    } as Facility
    const unknown = {
      ...pharmacy,
      id: 'unknown',
      operation: { ...pharmacy.operation, isOpenNow: null, status: 'unknown' },
    } as Facility
    fetchFacilitiesMock.mockResolvedValue([closed, unknown, pharmacy])

    render(
      <NearbyFacilities
        types={['pharmacy']}
        radiusM={1000}
        operationPreference="open_or_unknown"
      />,
    )

    await waitFor(() => {
      expect(fetchFacilitiesMock).toHaveBeenCalledWith(
        expect.objectContaining({
          type: 'pharmacy',
          operationPreference: 'open_or_unknown',
        }),
        expect.any(AbortSignal),
      )
    })
    expect(fetchFacilitiesMock).not.toHaveBeenCalledWith(
      expect.objectContaining({ openNow: expect.any(Boolean) }),
      expect.anything(),
    )
    expect(screen.getByTestId('stub-ids')).toHaveTextContent('facility:nmc:1,unknown')
    expect(screen.getByTestId('stub-ids')).not.toHaveTextContent(closed.id)
    expect(screen.getByTestId('stub-caption')).toHaveTextContent('open now or hours unknown')
  })
})
