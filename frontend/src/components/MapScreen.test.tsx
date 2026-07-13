import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { MapScreen } from './MapScreen'
import type { Facility } from '../lib/types'

/**
 * `FacilityMap` needs the Naver SDK, which is `FacilityMap.test.tsx`'s business; here it is a
 * stub that echoes the facilities, status text, caption, and location notice it receives.
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
    <div
      data-testid="map-stub"
      data-facility-ids={(props.facilities ?? []).map((facility) => facility.id).join(',')}
    >
      {props.caption && <span data-testid="map-caption">{props.caption}</span>}
      {props.notice && <span data-testid="map-notice">{props.notice}</span>}
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
  ),
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
  resolveLocationMock.mockReset()
  fetchFacilitiesMock.mockReset()
})

describe('MapScreen', () => {
  it('renders null opening hours as Hours unknown, never Closed', async () => {
    const user = userEvent.setup()
    const unknown = facility('unknown', '미상약국', null, '02-111-2222')
    resolveLocationMock.mockResolvedValue({ lat: 37.5663, lng: 126.9779, fromDevice: false })
    fetchFacilitiesMock.mockImplementation(({ type }: { type: string }) =>
      type === 'hospital' ? Promise.reject(notImplementedError()) : Promise.resolve([unknown]),
    )

    render(<MapScreen active={true} />)

    const unknownPin = await screen.findByTestId('map-facility-unknown')
    expect(unknownPin).toHaveTextContent('Hours unknown')
    expect(unknownPin).not.toHaveTextContent('Closed')
    expect(screen.queryByText('Closed')).not.toBeInTheDocument()
    expect(screen.getByText('1 pharmacies · 0 Open now · 1 Hours unknown')).toBeInTheDocument()
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
    expect(await screen.findByText('We cannot look up hospitals yet — pharmacy search works today.'))
      .toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('splits open-now results client-side so unknown hours remain available to call', async () => {
    const user = userEvent.setup()
    const open = facility('open', '영업약국', true)
    const unknown = facility('unknown', '미상약국', null, '02-333-4444')
    const closed = facility('closed', '닫힘약국', false)
    resolveLocationMock.mockResolvedValue({ lat: 37.5, lng: 127, fromDevice: true })
    fetchFacilitiesMock.mockImplementation(({ type }: { type: string }) =>
      type === 'hospital'
        ? Promise.reject(notImplementedError())
        : Promise.resolve([closed, unknown, open]),
    )

    render(<MapScreen active={true} />)

    const map = await screen.findByTestId('map-stub')
    await waitFor(() => expect(map).toHaveAttribute('data-facility-ids', 'open,unknown,closed'))
    expect(fetchFacilitiesMock).toHaveBeenCalledTimes(2)

    // Mutation guard: passing the switch value through as `openNow` must turn this assertion red.
    for (const [query] of fetchFacilitiesMock.mock.calls) {
      expect(query).toEqual(expect.objectContaining({ openNow: false }))
    }

    await user.click(screen.getByRole('switch', { name: 'Open now' }))

    await waitFor(() => expect(map).toHaveAttribute('data-facility-ids', 'open'))
    expect(screen.queryByTestId('map-facility-closed')).not.toBeInTheDocument()
    expect(screen.queryByText('닫힘약국')).not.toBeInTheDocument()

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
      expect(query).toEqual(expect.objectContaining({ openNow: false }))
    }
  })

  it('does not ask for location or fetch until the tab is active', async () => {
    resolveLocationMock.mockResolvedValue({ lat: 37.5, lng: 127, fromDevice: true })
    fetchFacilitiesMock.mockResolvedValue([])

    // The shell mounts every screen; an inactive Map must not prompt for location or spend the
    // pharmacy quota. Mutation guard: dropping the `active` gate must turn this red.
    const { rerender } = render(<MapScreen active={false} />)
    await Promise.resolve()
    expect(resolveLocationMock).not.toHaveBeenCalled()
    expect(fetchFacilitiesMock).not.toHaveBeenCalled()

    rerender(<MapScreen active={true} />)
    await waitFor(() => expect(fetchFacilitiesMock).toHaveBeenCalled())
  })

})
