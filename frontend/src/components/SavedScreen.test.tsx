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

function renderSaved() {
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
    fireEvent.click(screen.getByRole('button', { name: 'Edit' }))
    fireEvent.change(screen.getByRole('textbox', { name: 'Name' }), { target: { value: 'Late-night pharmacy' } })
    fireEvent.change(screen.getByRole('textbox', { name: 'Note' }), { target: { value: 'Call first' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }))

    await waitFor(() => expect(screen.getByText('Late-night pharmacy')).toBeInTheDocument())
    expect(fetchMock.mock.calls[1][0]).toContain('/favorites/7')
    expect(fetchMock.mock.calls[1][1]).toMatchObject({
      method: 'PATCH',
      body: JSON.stringify({ alias: 'Late-night pharmacy', memo: 'Call first' }),
    })
  })

  it('removes a saved place through the profile API', async () => {
    const fetchMock = mockFetch([{ json: async () => profile }, { status: 204 }])
    const user = userEvent.setup()
    renderSaved()

    expect(await screen.findByText('Hotel pharmacy')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Remove' }))

    await waitFor(() => expect(screen.queryByText('Hotel pharmacy')).not.toBeInTheDocument())
    expect(fetchMock.mock.calls[1][0]).toContain('/favorites/7')
    expect(fetchMock.mock.calls[1][1]).toMatchObject({ method: 'DELETE' })
  })
})
