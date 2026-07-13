import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { DetailDrawer } from './DetailDrawer'
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
  it('shows the selected facility name, type, address, distance, and source', () => {
    render(<DetailDrawer facility={facility()} onClose={() => {}} />)

    expect(screen.getByRole('heading', { name: '가나약국' })).toBeInTheDocument()
    expect(screen.getByText('Pharmacy')).toBeInTheDocument()
    expect(screen.getByText('서울특별시 중구 세종대로 110')).toBeInTheDocument()
    expect(screen.getByText('140 m away')).toBeInTheDocument()
    expect(screen.getByTestId('facility-source')).toHaveTextContent('국립중앙의료원 · 2026-07-10')
  })

  it('offers a telephone link when the facility has a phone number', () => {
    render(<DetailDrawer facility={facility()} onClose={() => {}} />)

    expect(screen.getByRole('link', { name: 'Call 02-123-4567' })).toHaveAttribute(
      'href',
      'tel:02-123-4567',
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
    render(<DetailDrawer facility={unknown} onClose={() => {}} />)

    expect(screen.getByText('Hours unknown')).toBeInTheDocument()
    expect(screen.queryByText('Closed')).not.toBeInTheDocument()
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
    render(<DetailDrawer facility={closed} onClose={() => {}} />)

    expect(screen.getByText('Closed')).toBeInTheDocument()
    expect(screen.queryByText('Hours unknown')).not.toBeInTheDocument()
  })

  it('closes through an accessible close button', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    render(<DetailDrawer facility={facility()} onClose={onClose} />)

    await user.click(screen.getByRole('button', { name: 'Close facility details' }))

    expect(onClose).toHaveBeenCalledOnce()
  })

  it('exposes the bottom sheet as an accessible dialog', () => {
    render(<DetailDrawer facility={facility()} onClose={() => {}} />)

    expect(screen.getByRole('dialog', { name: '가나약국' })).toHaveAttribute('aria-modal', 'true')
    expect(screen.getByRole('button', { name: 'Close facility details' })).toHaveFocus()
  })

  it('closes with Escape as well as the visible close button', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    render(<DetailDrawer facility={facility()} onClose={onClose} />)

    await user.keyboard('{Escape}')

    expect(onClose).toHaveBeenCalledOnce()
  })
})
