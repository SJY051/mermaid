import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MapScreen } from './MapScreen'
import { NearbyFacilities } from './NearbyFacilities'
import { LocationSessionProvider } from '../lib/locationSession'

vi.mock('./FacilityMap', () => ({
  FacilityMap: ({
    caption,
    center,
    notice,
    manualLocation,
  }: {
    caption?: string
    center: { lat: number; lng: number }
    notice?: string
    manualLocation?: { onUseSpot: (center: { lat: number; lng: number }) => void }
  }) => {
    const testId = caption?.startsWith('Nearby ') ? 'map-screen' : 'nearby-map'
    return (
      <div data-testid={testId} data-center={`${center.lat},${center.lng}`}>
        {notice}
        {manualLocation && (
          <button
            type="button"
            onClick={() => manualLocation.onUseSpot({ lat: 35.1796, lng: 129.0756 })}
          >
            Use this spot
          </button>
        )}
      </div>
    )
  },
}))

let originalGeolocation: Geolocation | undefined
let getCurrentPosition: ReturnType<typeof vi.fn>

beforeEach(() => {
  localStorage.clear()
  originalGeolocation = navigator.geolocation
  getCurrentPosition = vi.fn((onSuccess: PositionCallback) => {
    onSuccess({
      coords: { latitude: 37.5, longitude: 127 },
      timestamp: Date.now(),
    } as GeolocationPosition)
  })
  Object.defineProperty(navigator, 'geolocation', {
    configurable: true,
    value: { getCurrentPosition },
  })
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => ({ ok: true, status: 200, json: async () => [] })),
  )
})

afterEach(() => {
  Object.defineProperty(navigator, 'geolocation', {
    configurable: true,
    value: originalGeolocation,
  })
  vi.unstubAllGlobals()
})

describe('location permission is user-triggered only (SC-002)', () => {
  it('does not call getCurrentPosition when MapScreen mounts', async () => {
    const user = userEvent.setup()
    render(<MapScreen active={true} />)

    await waitFor(() => expect(fetch).toHaveBeenCalled())
    expect(getCurrentPosition).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: 'Use my location' }))
    expect(getCurrentPosition).toHaveBeenCalledOnce()
    expect(screen.getByText('Centred on you')).toBeInTheDocument()
  })

  it('does not call getCurrentPosition when NearbyFacilities mounts', async () => {
    const user = userEvent.setup()
    render(<NearbyFacilities types={['pharmacy']} radiusM={1000} openNow={false} />)

    await waitFor(() => expect(fetch).toHaveBeenCalled())
    expect(getCurrentPosition).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: 'Use my location' }))
    expect(getCurrentPosition).toHaveBeenCalledOnce()
    expect(screen.queryByRole('button', { name: 'Use my location' })).not.toBeInTheDocument()
  })

  it('re-reads a manual chat-map centre when the hidden Map tab opens', async () => {
    const user = userEvent.setup()
    const view = render(
      <LocationSessionProvider>
        <MapScreen active={false} />
        <NearbyFacilities types={['pharmacy']} radiusM={1000} openNow={false} />
      </LocationSessionProvider>,
    )

    const nearbyMap = await screen.findByTestId('nearby-map')
    await user.click(within(nearbyMap).getByRole('button', { name: 'Use this spot' }))
    view.rerender(
      <LocationSessionProvider>
        <MapScreen active={true} />
        <NearbyFacilities types={['pharmacy']} radiusM={1000} openNow={false} />
      </LocationSessionProvider>,
    )

    await waitFor(() => {
      expect(screen.getByTestId('map-screen')).toHaveAttribute('data-center', '35.1796,129.0756')
    })
    expect(getCurrentPosition).not.toHaveBeenCalled()
  })
})
