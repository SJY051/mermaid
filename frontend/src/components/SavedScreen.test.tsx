import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { FavoritesProvider } from '../lib/favorites'
import { SavedScreen } from './SavedScreen'

const profile = {
  favorites: [
    { id: 7, facilityId: 'facility:nmc:1', facilityType: 'pharmacy', alias: 'Hotel pharmacy', memo: 'Open late' },
  ],
}

function mockFetch(responses: Array<Partial<Response> & { json?: () => Promise<unknown> }>) {
  const fetchMock = vi.fn()
  for (const response of responses) {
    fetchMock.mockResolvedValueOnce({ ok: true, status: 200, json: async () => ({}), ...response })
  }
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function seedSavedFacility(
  dataMode: 'live' | 'fixture' = 'live',
  isOpenNow: boolean | null = null,
) {
  localStorage.setItem('mermaid.deviceId.v1', JSON.stringify({ schemaVersion: '1.0', data: 'test-device' }))
  localStorage.setItem('mermaid.savedFacilities.v1', JSON.stringify({
    schemaVersion: '1.0',
    data: [{
      id: '7', facilityId: 'facility:nmc:1', alias: '', note: '',
      createdAt: '2026-07-14T00:00:00Z', updatedAt: '2026-07-14T00:00:00Z',
      snapshot: {
        nameKo: 'Hotel pharmacy', type: 'pharmacy', addressKo: '1 Test Street',
        operation: {
          isOpenNow,
          status: isOpenNow === true ? 'open' : isOpenNow === false ? 'closed' : 'unknown',
          statusConfidence: isOpenNow === null ? 'unknown' : 'official_schedule',
          verifiedAt: isOpenNow === null ? null : '2026-07-14T00:00:00Z',
          notice: '',
        },
        source: { id: 'nmc:1', provider: 'nmc', recordId: '1', retrievedAt: '2026-07-14T00:00:00Z', dataMode, title: 'National Medical Center' },
      },
    }],
  }))
}

function renderSaved() {
  seedSavedFacility()
  return render(
    <FavoritesProvider>
      <SavedScreen active />
    </FavoritesProvider>,
  )
}

afterEach(() => {
  localStorage.clear()
  vi.unstubAllGlobals()
})

describe('SavedScreen', () => {
  it('loads profile favorites and lets the user edit an alias and note', async () => {
    const fetchMock = mockFetch([
      { json: async () => profile },
      { json: async () => ({ ...profile.favorites[0], alias: 'Late-night pharmacy', memo: 'Call first' }) },
    ])
    renderSaved()

    expect(await screen.findByText('Hotel pharmacy')).toBeInTheDocument()
    expect(screen.getByText('National Medical Center · 2026-07-14')).toBeInTheDocument()
    expect(screen.getByText(/Hours not checked since 2026-07-14/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Edit' }))
    fireEvent.change(screen.getByRole('textbox', { name: 'Name' }), { target: { value: 'Late-night pharmacy' } })
    fireEvent.change(screen.getByRole('textbox', { name: 'Note' }), { target: { value: 'Call first' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }))

    await waitFor(() => expect(screen.getByText('Late-night pharmacy')).toBeInTheDocument())
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/profiles/test-device')
    expect(fetchMock.mock.calls[1][0]).toBe('/api/v1/profiles/test-device/favorites/7')
    expect(fetchMock.mock.calls[1][1]).toMatchObject({
      method: 'PATCH',
      body: JSON.stringify({ alias: 'Late-night pharmacy', memo: 'Call first' }),
      headers: { 'Content-Type': 'application/json' },
    })
  })

  it('removes a saved place through the profile API', async () => {
    const noJsonFor204 = vi.fn(() => { throw new Error('204 must not be parsed') })
    const fetchMock = mockFetch([{ json: async () => profile }, { status: 204, json: noJsonFor204 }])
    const user = userEvent.setup()
    renderSaved()

    expect(await screen.findByText('Hotel pharmacy')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Remove' }))

    await waitFor(() => expect(screen.queryByText('Hotel pharmacy')).not.toBeInTheDocument())
    expect(fetchMock.mock.calls[1][0]).toBe('/api/v1/profiles/test-device/favorites/7')
    expect(fetchMock.mock.calls[1][1]).toMatchObject({ method: 'DELETE', headers: { 'Content-Type': 'application/json' } })
    expect(noJsonFor204).not.toHaveBeenCalled()
  })

  it('keeps fixture provenance visible after refreshing the profile', async () => {
    const fetchMock = mockFetch([{ json: async () => profile }])
    seedSavedFacility('fixture')
    render(
      <FavoritesProvider><SavedScreen active /></FavoritesProvider>,
    )

    expect(await screen.findByText('Sample data')).toBeInTheDocument()
    expect(screen.getByText('National Medical Center · 2026-07-14')).toBeInTheDocument()
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/profiles/test-device')
  })

  // This replaces a test that asserted the opposite — that a saved isOpenNow=true renders as
  // "Open now". It did render that, and that was the defect: the snapshot was true when the place
  // was saved and has not been checked since, because opening this tab reloads the alias and the
  // note from the profile and never the facility's hours. A pharmacy saved on a Tuesday afternoon
  // said "Open now" at 11pm on Friday. §2-3 refuses to draw an unknown state as "Closed"; a stale
  // state must not be drawn as a current one at all.
  it.each([[true], [false], [null]] as const)(
    'never presents a saved isOpenNow=%s as a current open state (P1)',
    async (isOpenNow) => {
      mockFetch([{ json: async () => profile }])
      seedSavedFacility('live', isOpenNow)
      render(<FavoritesProvider><SavedScreen active /></FavoritesProvider>)

      expect(await screen.findByText(/Hours not checked since 2026-07-14/)).toBeInTheDocument()
      expect(screen.queryByText('Open now')).not.toBeInTheDocument()
      expect(screen.queryByText('Closed')).not.toBeInTheDocument()
      // And it says where to find out, rather than leaving a dead end.
      expect(screen.getByText(/open it on the Map/i)).toBeInTheDocument()
    },
  )
})
