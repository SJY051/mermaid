import { StrictMode } from 'react'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
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

function installNaverStub({
  autoInit = true,
  scriptLoaded = true,
  markerThrows = false,
}: { autoInit?: boolean; scriptLoaded?: boolean; markerThrows?: boolean } = {}) {
  const markers: MarkerStub[] = []
  const infoWindow = { setContent: vi.fn(), open: vi.fn(), close: vi.fn() }
  const readyHandlers: Array<() => void> = []
  let mapsCreated = 0

  const naver = {
    maps: {
      Map: class {
        constructor() {
          mapsCreated += 1
        }
      },
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
          if (markerThrows) throw new Error('Naver marker internals are unavailable')
          markers.push(this as unknown as MarkerStub)
        }
      },
      Event: {
        once: (_target: unknown, event: string, handler: () => void) => {
          if (event === 'tilesloaded') {
            readyHandlers.push(handler)
            if (autoInit) queueMicrotask(handler)
          }
          return { eventName: event, listener: handler, listenerId: '', target: _target }
        },
        removeListener: vi.fn(),
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
  if (scriptLoaded) script.dataset.loaded = 'true'
  document.head.appendChild(script)

  return {
    markers,
    infoWindow,
    readyHandlers,
    script,
    mapsCreated: () => mapsCreated,
    triggerTilesLoaded: () => readyHandlers.splice(0).forEach((handler) => handler()),
  }
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
  // Unmount FIRST, while the naver stub still exists. React can leave a passive mount effect
  // (the one that builds markers and the InfoWindow) scheduled but unflushed when a test's last
  // assertion only needed the DOM; whatever flushes it next — including the unmount itself —
  // must find `naver` still defined. CI run 29110631929 failed exactly there: the stub was
  // removed before the deferred flush, and FacilityMap.tsx:54 threw `naver is not defined`
  // on a machine slow enough for the effect to lag. cleanup() is idempotent, so the global
  // one in src/test/setup.ts running again afterwards is harmless.
  cleanup()
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

  it('waits for the existing script tag to finish loading before constructing a map', async () => {
    const { script, mapsCreated } = installNaverStub({ scriptLoaded: false })
    render(
      <StrictMode>
        <FacilityMap center={centre} />
      </StrictMode>,
    )

    expect(mapsCreated()).toBe(0)
    await Promise.resolve()
    expect(mapsCreated()).toBe(0)

    script.dataset.loaded = 'true'
    script.dispatchEvent(new Event('load'))

    await waitFor(() => expect(mapsCreated()).toBe(1))
  })

  it('does not attach markers until the Naver map loads real tiles', async () => {
    const { markers, readyHandlers, triggerTilesLoaded } = installNaverStub({ autoInit: false })
    render(<FacilityMap center={centre} facilities={[facility()]} />)

    await waitFor(() => expect(readyHandlers).toHaveLength(1))
    expect(markers).toHaveLength(0)
    expect(screen.getByTestId('map-loading')).toBeInTheDocument()

    triggerTilesLoaded()

    await waitFor(() => expect(markers).toHaveLength(1))
  })

  it('never attaches markers when authentication fails before tiles load', async () => {
    const { markers, readyHandlers, triggerTilesLoaded } = installNaverStub({ autoInit: false })
    render(<FacilityMap center={centre} facilities={[facility()]} />)

    await waitFor(() => expect(readyHandlers).toHaveLength(1))
    window.navermap_authFailure!()
    triggerTilesLoaded()

    expect(await screen.findByTestId('map-error')).toHaveTextContent(AUTH_FAILURE_MESSAGE)
    expect(markers).toHaveLength(0)
  })

  it('keeps the facility list usable when the Naver marker SDK fails', async () => {
    installNaverStub({ markerThrows: true })
    render(<FacilityMap center={centre} facilities={[facility()]} />)

    expect(await screen.findByTestId('map-error')).toHaveTextContent(
      'Use the facility list below instead.',
    )
    expect(screen.getByRole('button', { name: /가나약국/ })).toBeInTheDocument()
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

describe('fixture provenance is visible wherever the shared map is used (spec §2-9)', () => {
  it('labels fixture facilities, including related results rendered outside the map', () => {
    installNaverStub()
    const live = facility()
    const sample = facility({ id: 'facility:nmc:sample' })
    sample.source.dataMode = 'fixture'

    const { rerender } = render(<FacilityMap center={centre} facilities={[live]} />)
    expect(screen.queryByTestId('map-fixture-notice')).not.toBeInTheDocument()

    rerender(<FacilityMap center={centre} facilities={[live, sample]} />)
    expect(screen.getByTestId('map-fixture-notice')).toHaveTextContent(
      'Sample data — availability may not reflect current conditions.',
    )

    rerender(<FacilityMap center={centre} facilities={[live]} additionalFixtureData={true} />)
    expect(screen.getByTestId('map-fixture-notice')).toHaveTextContent(
      'Sample data — availability may not reflect current conditions.',
    )
  })
})

describe('facility details (UI-03, DEV-207)', () => {
  it('opens the detail drawer when a facility in the accessible list is selected', async () => {
    const user = userEvent.setup()
    installNaverStub()
    render(<FacilityMap center={centre} facilities={[facility()]} />)

    await user.click(await screen.findByRole('button', { name: /가나약국/ }))

    expect(screen.getByRole('dialog', { name: '가나약국' })).toBeInTheDocument()
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
