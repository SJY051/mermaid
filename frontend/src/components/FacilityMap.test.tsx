import { render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { FacilityMap } from './FacilityMap'
import { AUTH_FAILURE_MESSAGE } from '../hooks/useNaverMap'
import type { Facility } from '../lib/types'

/**
 * A fake `naver.maps`, sufficient for the parts we call.
 *
 * The real SDK cannot run here, and that is fine: none of what this file checks is Naver's
 * behaviour. It checks ours — that a rejected key becomes visible, that `null` opening hours are
 * not drawn as "Closed", that markers are torn down, and that a government-supplied name is
 * escaped before it becomes HTML.
 */
interface MarkerStub {
  setMap: ReturnType<typeof vi.fn>
  onClick?: () => void
}

function installNaverStub() {
  const markers: MarkerStub[] = []
  const infoWindow = { setContent: vi.fn(), open: vi.fn(), close: vi.fn() }

  const naver = {
    maps: {
      Map: class {},
      LatLng: class {},
      Point: class {},
      InfoWindow: class {
        setContent = infoWindow.setContent
        open = infoWindow.open
        close = infoWindow.close
      },
      Marker: class {
        setMap = vi.fn()
        constructor() {
          markers.push(this as unknown as MarkerStub)
        }
      },
      Event: {
        addListener: (marker: MarkerStub, event: string, handler: () => void) => {
          if (event === 'click') marker.onClick = handler
        },
      },
    },
  }

  vi.stubGlobal('naver', naver)
  // `loadNaverMapsScript` short-circuits when the tag is already present and the namespace is up.
  const script = document.createElement('script')
  script.id = 'naver-maps-sdk'
  document.head.appendChild(script)

  return { markers, infoWindow }
}

const facility = (over: Partial<Facility> = {}): Facility => ({
  id: 'facility:nmc:1',
  type: 'pharmacy',
  nameKo: '가나약국',
  nameEn: null,
  addressKo: null,
  addressEn: null,
  phone: null,
  latitude: 37.5663,
  longitude: 126.9779,
  distanceMeters: 140.4,
  operation: {
    isOpenNow: true,
    status: 'open',
    statusConfidence: 'official_schedule',
    verifiedAt: null,
    notice: '',
  },
  source: {
    id: 'src1',
    provider: 'nmc',
    recordId: null,
    retrievedAt: '2026-07-10T12:00:00Z',
    dataMode: 'live',
    title: '국립중앙의료원',
  },
  ...over,
})

const centre = { lat: 37.5663, lng: 126.9779 }

beforeEach(() => {
  vi.stubEnv('VITE_NAVER_MAP_CLIENT_ID', 'test-key-id')
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.unstubAllEnvs()
  document.getElementById('naver-maps-sdk')?.remove()
  delete (window as { naver?: unknown }).naver
})

describe('the map never shows a blank box and calls it a map', () => {
  it('tells the user when the key is missing', async () => {
    vi.stubEnv('VITE_NAVER_MAP_CLIENT_ID', '')
    render(<FacilityMap center={centre} />)

    const error = await screen.findByTestId('map-error')
    expect(error).toHaveTextContent(/VITE_NAVER_MAP_CLIENT_ID is not set/)
    expect(screen.queryByTestId('map-loading')).not.toBeInTheDocument()
  })

  /**
   * The one a server-side check cannot make. With a deliberately wrong key the script still
   * answers 200, `onload` still fires, `naver.maps` is still defined, and the map still
   * constructs. The SDK only complains afterwards, through a global callback.
   */
  it('surfaces an auth failure that arrives after the map was built', async () => {
    installNaverStub()
    render(<FacilityMap center={centre} />)

    await waitFor(() => expect(screen.queryByTestId('map-loading')).not.toBeInTheDocument())
    expect(screen.queryByTestId('map-error')).not.toBeInTheDocument()

    // Exactly what the SDK does when Naver rejects the key.
    window.navermap_authFailure!()

    const error = await screen.findByTestId('map-error')
    expect(error).toHaveTextContent(AUTH_FAILURE_MESSAGE)
  })

  it('shows a loading state before the SDK is up, not an empty frame', () => {
    // No stub installed: the script never resolves, so we stay in the loading state.
    render(<FacilityMap center={centre} />)
    expect(screen.getByTestId('map-loading')).toBeInTheDocument()
  })
})

describe('unknown opening hours are never rendered as "Closed" (spec §2-13)', () => {
  it('labels isOpenNow: null as "Hours unknown"', async () => {
    installNaverStub()
    const unknown = facility({
      operation: { isOpenNow: null, status: 'unknown', statusConfidence: 'unknown', verifiedAt: null, notice: '' },
    })
    render(<FacilityMap center={centre} facilities={[unknown]} />)

    const list = await screen.findByTestId('facility-list')
    expect(list).toHaveTextContent('Hours unknown')
    expect(list).not.toHaveTextContent('Closed')
  })

  it('still says "Closed" when the backend actually knows it is closed', async () => {
    installNaverStub()
    const closed = facility({
      operation: { isOpenNow: false, status: 'closed', statusConfidence: 'official_schedule', verifiedAt: null, notice: '' },
    })
    render(<FacilityMap center={centre} facilities={[closed]} />)

    expect(await screen.findByTestId('facility-list')).toHaveTextContent('Closed')
  })

  it('rounds the distance the backend computed, in metres', async () => {
    installNaverStub()
    render(<FacilityMap center={centre} facilities={[facility({ distanceMeters: 140.4 })]} />)

    expect(await screen.findByTestId('facility-list')).toHaveTextContent('140m')
  })
})

describe('markers are torn down, because Naver markers are not React children', () => {
  it('removes the old pins before drawing new ones', async () => {
    const { markers } = installNaverStub()
    const { rerender } = render(<FacilityMap center={centre} facilities={[facility()]} />)

    await waitFor(() => expect(markers).toHaveLength(1))
    const first = markers[0]

    rerender(<FacilityMap center={centre} facilities={[facility({ id: 'facility:nmc:2' })]} />)

    await waitFor(() => expect(markers).toHaveLength(2))
    expect(first.setMap).toHaveBeenCalledWith(null)
  })

  it('clears every pin on unmount', async () => {
    const { markers } = installNaverStub()
    const { unmount } = render(<FacilityMap center={centre} facilities={[facility()]} />)

    await waitFor(() => expect(markers).toHaveLength(1))
    unmount()

    expect(markers[0].setMap).toHaveBeenCalledWith(null)
  })
})

describe('a facility name from a government API is data, not markup', () => {
  it('escapes the name and phone before they become an InfoWindow', async () => {
    const { markers, infoWindow } = installNaverStub()
    const hostile = facility({
      nameKo: '<img src=x onerror="alert(1)">약국',
      phone: '02-000-0000"><script>alert(1)</script>',
    })
    render(<FacilityMap center={centre} facilities={[hostile]} />)

    await waitFor(() => expect(markers).toHaveLength(1))
    markers[0].onClick!()

    const html = infoWindow.setContent.mock.calls[0][0] as string
    expect(html).not.toContain('<img')
    expect(html).not.toContain('<script>')
    expect(html).toContain('&lt;img')
  })
})
