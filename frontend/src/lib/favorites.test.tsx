import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { FavoritesProvider, useFavorites } from './favorites'
import { loadSavedFacilities, saveSavedFacilities } from './storage'
import { SavedScreen } from '../components/SavedScreen'
import type { Facility } from './types'

function facility(id: string): Facility {
  return {
    id, type: 'pharmacy', nameKo: id, nameEn: null, addressKo: 'Seoul', addressEn: null, phone: null,
    latitude: 0, longitude: 0, distanceMeters: 0,
    operation: { isOpenNow: true, status: 'open', statusConfidence: 'official_schedule', verifiedAt: '2026-07-14T00:00:00Z', notice: '' },
    source: { id: `source:${id}`, provider: 'nmc', recordId: id, retrievedAt: '2026-07-14T00:00:00Z', dataMode: 'live', title: 'National Medical Center' },
  }
}

function defer<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}

function SaveHarness() {
  const { favorites, saveFacility } = useFavorites()
  return <>
    <button onClick={() => void saveFacility(facility('facility:nmc:a'))}>Save A</button>
    <button onClick={() => void saveFacility(facility('facility:nmc:b'))}>Save B</button>
    <output>{favorites.map((favorite) => favorite.facilityId).join(',')}</output>
  </>
}

describe('FavoritesProvider request ordering', () => {
  afterEach(() => {
    localStorage.clear()
    vi.unstubAllGlobals()
  })

  it('keeps both saves when POST responses complete in reverse order', async () => {
    const first = defer<Response>()
    const second = defer<Response>()
    const fetchMock = vi.fn((_: string, init: RequestInit) =>
      JSON.parse(init.body as string).facilityId.endsWith(':a') ? first.promise : second.promise,
    )
    vi.stubGlobal('fetch', fetchMock)
    render(<FavoritesProvider><SaveHarness /></FavoritesProvider>)

    fireEvent.click(screen.getByRole('button', { name: 'Save A' }))
    fireEvent.click(screen.getByRole('button', { name: 'Save B' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))

    second.resolve({ ok: true, status: 201, json: async () => ({ id: 2, facilityId: 'facility:nmc:b', facilityType: 'pharmacy', alias: null, memo: null }) } as Response)
    first.resolve({ ok: true, status: 201, json: async () => ({ id: 1, facilityId: 'facility:nmc:a', facilityType: 'pharmacy', alias: null, memo: null }) } as Response)

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('facility:nmc:b,facility:nmc:a'))
    expect(loadSavedFacilities().map((favorite) => favorite.facilityId)).toEqual(['facility:nmc:b', 'facility:nmc:a'])
  })

  it('does not restore a removed place when an older Saved-tab GET finishes last', async () => {
    const current = facility('facility:nmc:1')
    saveSavedFacilities([{
      id: '1', facilityId: current.id, alias: '', note: '',
      createdAt: '2026-07-14T00:00:00Z', updatedAt: '2026-07-14T00:00:00Z',
      snapshot: { nameKo: current.nameKo, type: current.type, addressKo: current.addressKo, operation: current.operation, source: current.source },
    }])
    const staleGet = defer<Response>()
    const jsonMustNotRun = vi.fn(() => { throw new Error('204 must not be parsed') })
    const fetchMock = vi.fn()
      .mockReturnValueOnce(staleGet.promise)
      .mockResolvedValueOnce({ ok: true, status: 204, json: jsonMustNotRun })
    vi.stubGlobal('fetch', fetchMock)
    render(<FavoritesProvider><SavedScreen active /></FavoritesProvider>)

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    fireEvent.click(screen.getByRole('button', { name: 'Remove' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(screen.queryByText('facility:nmc:1')).not.toBeInTheDocument())

    staleGet.resolve({ ok: true, status: 200, json: async () => ({ favorites: [{ id: 1, facilityId: current.id, facilityType: 'pharmacy', alias: null, memo: null }] }) } as Response)
    await waitFor(() => expect(loadSavedFacilities()).toEqual([]))
    expect(screen.queryByText('facility:nmc:1')).not.toBeInTheDocument()
    expect(jsonMustNotRun).not.toHaveBeenCalled()
  })
})
