import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { DetailDrawer } from './DetailDrawer'
import { FavoritesProvider } from '../lib/favorites'
import type { Facility } from '../lib/types'

const facility = (over: Partial<Facility> = {}): Facility => ({
  id: 'facility:nmc:1',
  type: 'pharmacy',
  nameKo: '가나약국',
  nameEn: null,
  addressKo: '서울특별시 중구 세종대로 110',
  addressEn: null,
  phone: '02-123-4567',
  latitude: 37.5663,
  longitude: 126.9779,
  distanceMeters: 140.4,
  operation: {
    isOpenNow: true,
    status: 'open',
    statusConfidence: 'official_schedule',
    verifiedAt: '2026-07-10T12:00:00Z',
    scheduleUpdatedAt: null,
    notice: '',
  },
  source: {
    id: 'src1',
    provider: 'nmc',
    recordId: '1',
    retrievedAt: '2026-07-10T12:00:00Z',
    dataMode: 'live',
    title: '국립중앙의료원',
  },
  ...over,
})

describe('DetailDrawer', () => {
  function renderDrawer(overrides: Partial<Facility> = {}, onClose = () => {}) {
    return render(
      <FavoritesProvider>
        <DetailDrawer facility={facility(overrides)} onClose={onClose} />
      </FavoritesProvider>,
    )
  }

  function finishAnimation(element: HTMLElement) {
    // jsdom has no AnimationEvent, so React registers its animation plugin on the WebKit fallback.
    // Dispatching that fallback reaches the same onAnimationEnd handler as animationend in a browser.
    fireEvent(element, new Event('webkitAnimationEnd', { bubbles: true }))
  }

  it('shows the selected facility name, type, address, distance, and source', () => {
    renderDrawer()

    expect(screen.getByRole('dialog')).toHaveClass('bg-surface')
    expect(screen.getByRole('heading', { name: '가나약국' })).toBeInTheDocument()
    expect(screen.getByText('Pharmacy')).toBeInTheDocument()
    expect(screen.getByText('서울특별시 중구 세종대로 110')).toBeInTheDocument()
    expect(screen.getByText('140 m from map centre')).toBeInTheDocument()
    expect(screen.getByTestId('facility-source')).toHaveTextContent('국립중앙의료원 · 2026-07-10')
    expect(screen.getByTestId('detail-operation-glyph')).toHaveTextContent('✓')
  })

  it('does not present an unavailable distance as zero metres', () => {
    renderDrawer({ distanceMeters: null })

    expect(screen.getByText('Distance unavailable')).toBeInTheDocument()
    expect(screen.queryByText('0 m from map centre')).not.toBeInTheDocument()
  })

  it('offers a telephone link when the facility has a phone number', () => {
    renderDrawer()

    expect(screen.getByRole('link', { name: 'Call 02-123-4567' })).toHaveAttribute(
      'href',
      'tel:02-123-4567',
    )
  })

  it('uses a surface token for an operation notice', () => {
    renderDrawer({ operation: { isOpenNow: true, status: 'open', statusConfidence: 'official_schedule', verifiedAt: '2026-07-10T12:00:00Z', scheduleUpdatedAt: null, notice: 'Call before visiting.' } })

    expect(screen.getByText('Call before visiting.')).toHaveClass('bg-muted')
  })

  it('treats a government-supplied name and phone as text, not markup', () => {
    const hostile = facility({
      nameKo: '<img src=x onerror="alert(1)">약국',
      phone: '02-000-0000"><script>alert(1)</script>',
    })
    const { container } = renderDrawer(hostile)

    expect(container.querySelector('img')).toBeNull()
    expect(container.querySelector('script')).toBeNull()
    expect(screen.getByRole('heading', { name: hostile.nameKo })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: `Call ${hostile.phone}` })).toHaveAttribute(
      'href',
      `tel:${hostile.phone}`,
    )
  })

  it('renders null opening hours as "Hours unknown", never "Closed"', () => {
    const unknown = facility({
      operation: {
        isOpenNow: null,
        status: 'unknown',
        statusConfidence: 'unknown',
        verifiedAt: null,
        scheduleUpdatedAt: null,
        notice: '',
      },
    })
    renderDrawer({ operation: unknown.operation })

    expect(screen.getByText('Hours unknown')).toBeInTheDocument()
    expect(screen.queryByText('Closed')).not.toBeInTheDocument()
    expect(screen.getByTestId('detail-operation-glyph')).toHaveTextContent('?')
  })

  it('renders false opening hours as "Closed", not "Hours unknown"', () => {
    const closed = facility({
      operation: {
        isOpenNow: false,
        status: 'closed',
        statusConfidence: 'official_schedule',
        verifiedAt: '2026-07-10T12:00:00Z',
        scheduleUpdatedAt: null,
        notice: '',
      },
    })
    renderDrawer({ operation: closed.operation })

    expect(screen.getByText('Closed')).toBeInTheDocument()
    expect(screen.queryByText('Hours unknown')).not.toBeInTheDocument()
    expect(screen.getByTestId('detail-operation-glyph')).toHaveTextContent('×')
  })

  it('plays the sheet exit motion before closing through the accessible close button', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    renderDrawer({}, onClose)

    await user.click(screen.getByRole('button', { name: 'Close facility details' }))

    expect(screen.getByRole('dialog')).toHaveClass('appearance-sheet-exit')
    expect(onClose).not.toHaveBeenCalled()

    finishAnimation(screen.getByRole('dialog'))

    expect(onClose).toHaveBeenCalledOnce()
  })

  it('plays the sheet exit motion when the dimmed backdrop is clicked, but not when the card is clicked', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    renderDrawer({}, onClose)

    await user.click(screen.getByRole('dialog'))
    expect(onClose).not.toHaveBeenCalled()
    expect(screen.getByRole('dialog')).not.toHaveClass('appearance-sheet-exit')

    await user.click(screen.getByTestId('detail-drawer-backdrop'))
    expect(screen.getByRole('dialog')).toHaveClass('appearance-sheet-exit')
    expect(onClose).not.toHaveBeenCalled()

    finishAnimation(screen.getByRole('dialog'))

    expect(onClose).toHaveBeenCalledOnce()
  })

  it('ignores child animation completion while the sheet is exiting', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    renderDrawer({}, onClose)

    await user.click(screen.getByRole('button', { name: 'Close facility details' }))
    finishAnimation(screen.getByRole('heading', { name: '가나약국' }))

    expect(onClose).not.toHaveBeenCalled()

    finishAnimation(screen.getByRole('dialog'))

    expect(onClose).toHaveBeenCalledOnce()
  })

  it('stays inside the shell bound instead of spanning the viewport', () => {
    // `fixed` positions against the viewport, so a drawer is precisely how a screen escapes the
    // bound MobileShell puts on everything else (007 FR-001). On a desktop this sheet covered all
    // 1600px while the app behind it was 768 — the phone-width product became a full-width desktop
    // surface the moment someone tapped a pharmacy.
    //
    // jsdom cannot measure, so this asserts the bound EXISTS — the same shape as the shell's own
    // test; the widths are checked in a browser (SC-001). The dim layer is deliberately not bounded:
    // covering the screen is its job.
    renderDrawer()

    // By name, not by substring: `max-w-full` contains `max-w-` and is exactly the regression this
    // guards. The cap must be the SHELL's cap — a drawer wider than the app is the bug.
    expect(screen.getByRole('dialog').className).toMatch(/\bmax-w-3xl\b/)
    expect(screen.getByRole('dialog').className).not.toMatch(/\bmax-w-(full|none)\b/)
    expect(screen.getByTestId('detail-drawer-backdrop').className).toMatch(/justify-center/)
  })

  it('exposes the bottom sheet as an accessible dialog', () => {
    renderDrawer()

    expect(screen.getByRole('dialog', { name: '가나약국' })).toHaveAttribute('aria-modal', 'true')
    expect(screen.getByRole('button', { name: 'Close facility details' })).toHaveFocus()
  })

  it('plays the sheet exit motion before closing with Escape', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    renderDrawer({}, onClose)

    await user.keyboard('{Escape}')

    expect(screen.getByRole('dialog')).toHaveClass('appearance-sheet-exit')
    expect(onClose).not.toHaveBeenCalled()

    finishAnimation(screen.getByRole('dialog'))

    expect(onClose).toHaveBeenCalledOnce()
  })

  it('closes immediately without motion when reduced motion is preferred', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    const matchMedia = vi.fn((query: string) =>
      ({
        matches: query === '(prefers-reduced-motion: reduce)',
        media: query,
        onchange: null,
        addEventListener: () => {},
        removeEventListener: () => {},
        addListener: () => {},
        removeListener: () => {},
        dispatchEvent: () => false,
      }) as MediaQueryList,
    )
    vi.stubGlobal('matchMedia', matchMedia)
    renderDrawer({}, onClose)

    await user.click(screen.getByRole('button', { name: 'Close facility details' }))

    expect(matchMedia).toHaveBeenCalledWith('(prefers-reduced-motion: reduce)')
    expect(onClose).toHaveBeenCalledOnce()
    expect(screen.getByRole('dialog')).not.toHaveClass('appearance-sheet-exit')
  })

  it('saves the selected facility through the anonymous profile API', async () => {
    localStorage.setItem('mermaid.deviceId.v1', JSON.stringify({ schemaVersion: '1.0', data: 'test-device' }))
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 201,
      json: async () => ({
        id: 9,
        facilityId: 'facility:nmc:1',
        facilityType: 'pharmacy',
        alias: null,
        memo: null,
      }),
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderDrawer()

    await user.click(screen.getByRole('button', { name: 'Save place' }))

    expect(await screen.findByRole('button', { name: 'Saved' })).toBeDisabled()
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/profiles/test-device/favorites')
    expect(fetchMock.mock.calls[0][1]).toMatchObject({
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        facilityId: 'facility:nmc:1',
        facilityType: 'pharmacy',
        alias: null,
        memo: null,
      }),
    })
  })
})

afterEach(() => {
  localStorage.clear()
  vi.unstubAllGlobals()
})
