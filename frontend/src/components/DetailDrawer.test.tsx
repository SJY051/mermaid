import { render, screen } from '@testing-library/react'
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

  it('shows the selected facility name, type, address, distance, and source', () => {
    renderDrawer()

    expect(screen.getByRole('dialog')).toHaveClass('bg-surface')
    expect(screen.getByRole('heading', { name: '가나약국' })).toBeInTheDocument()
    expect(screen.getByText('Pharmacy')).toBeInTheDocument()
    expect(screen.getByText('서울특별시 중구 세종대로 110')).toBeInTheDocument()
    expect(screen.getByText('140 m away')).toBeInTheDocument()
    expect(screen.getByTestId('facility-source')).toHaveTextContent('국립중앙의료원 · 2026-07-10')
    expect(screen.getByTestId('detail-operation-glyph')).toHaveTextContent('✓')
  })

  it('offers a telephone link when the facility has a phone number', () => {
    renderDrawer()

    expect(screen.getByRole('link', { name: 'Call 02-123-4567' })).toHaveAttribute(
      'href',
      'tel:02-123-4567',
    )
  })

  it('uses a surface token for an operation notice', () => {
    render(
      <DetailDrawer
        facility={facility({
          operation: {
            isOpenNow: true,
            status: 'open',
            statusConfidence: 'official_schedule',
            verifiedAt: '2026-07-10T12:00:00Z',
            notice: 'Call before visiting.',
          },
        })}
        onClose={() => {}}
      />,
    )

    expect(screen.getByText('Call before visiting.')).toHaveClass('bg-muted')
  })

  it('treats a government-supplied name and phone as text, not markup', () => {
    const hostile = facility({
      nameKo: '<img src=x onerror="alert(1)">약국',
      phone: '02-000-0000"><script>alert(1)</script>',
    })
    const { container } = render(<DetailDrawer facility={hostile} onClose={() => {}} />)

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
        notice: '',
      },
    })
    renderDrawer({ operation: closed.operation })

    expect(screen.getByText('Closed')).toBeInTheDocument()
    expect(screen.queryByText('Hours unknown')).not.toBeInTheDocument()
    expect(screen.getByTestId('detail-operation-glyph')).toHaveTextContent('×')
  })

  it('closes through an accessible close button', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    renderDrawer({}, onClose)

    await user.click(screen.getByRole('button', { name: 'Close facility details' }))

    expect(onClose).toHaveBeenCalledOnce()
  })

  it('closes when the dimmed map backdrop is clicked, but not when the card is clicked', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    render(<DetailDrawer facility={facility()} onClose={onClose} />)

    await user.click(screen.getByRole('dialog'))
    expect(onClose).not.toHaveBeenCalled()

    await user.click(screen.getByTestId('detail-drawer-backdrop'))
    expect(onClose).toHaveBeenCalledOnce()
  })

  it('exposes the bottom sheet as an accessible dialog', () => {
    renderDrawer()

    expect(screen.getByRole('dialog', { name: '가나약국' })).toHaveAttribute('aria-modal', 'true')
    expect(screen.getByRole('button', { name: 'Close facility details' })).toHaveFocus()
  })

  it('closes with Escape as well as the visible close button', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    renderDrawer({}, onClose)

    await user.keyboard('{Escape}')

    expect(onClose).toHaveBeenCalledOnce()
  })

  it('saves the selected facility through the anonymous profile API', async () => {
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
    expect(fetchMock.mock.calls[0][0]).toContain('/favorites')
    expect(fetchMock.mock.calls[0][1]).toMatchObject({
      method: 'POST',
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
