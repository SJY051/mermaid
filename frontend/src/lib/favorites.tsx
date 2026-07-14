import { createContext, useCallback, useContext, useState } from 'react'
import {
  getDeviceId,
  loadSavedFacilities,
  saveSavedFacilities,
  type SavedFacility,
} from './storage'
import type { Facility, FacilityType } from './types'

interface FavoriteResponse {
  id: number
  facilityId: string
  facilityType: FacilityType
  alias: string | null
  memo: string | null
}

interface ProfileResponse {
  favorites: FavoriteResponse[]
}

interface FavoritesContextValue {
  favorites: SavedFacility[]
  savingFacilityId: string | null
  saveFacility: (facility: Facility) => Promise<void>
  updateFavorite: (favorite: SavedFacility, alias: string, note: string) => Promise<void>
  removeFavorite: (favorite: SavedFacility) => Promise<void>
  refreshFavorites: () => Promise<void>
}

const FavoritesContext = createContext<FavoritesContextValue | null>(null)

function profileUrl(path = ''): string {
  return `/api/v1/profiles/${encodeURIComponent(getDeviceId())}${path}`
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(profileUrl(path), {
    headers: { 'Content-Type': 'application/json', ...init?.headers },
    ...init,
  })

  if (!response.ok) {
    const body = await response.json().catch(() => null)
    throw new Error(body?.error?.message ?? `Could not update saved places (HTTP ${response.status}).`)
  }

  return response.status === 204 ? (undefined as T) : (response.json() as Promise<T>)
}

function snapshotFrom(facility: Facility): SavedFacility['snapshot'] {
  return {
    nameKo: facility.nameKo,
    type: facility.type,
    addressKo: facility.addressEn ?? facility.addressKo,
  }
}

function savedFrom(response: FavoriteResponse, snapshot?: SavedFacility['snapshot']): SavedFacility {
  const now = new Date().toISOString()
  return {
    id: String(response.id),
    facilityId: response.facilityId,
    snapshot: snapshot ?? {
      // The profile API intentionally stores no government facility details. Keep a local
      // snapshot when saving, and be honest when an older server-only favorite has none.
      nameKo: response.facilityId,
      type: response.facilityType,
      addressKo: null,
    },
    alias: response.alias ?? '',
    note: response.memo ?? '',
    createdAt: now,
    updatedAt: now,
  }
}

export function FavoritesProvider({ children }: { children: React.ReactNode }) {
  const [favorites, setFavorites] = useState(loadSavedFacilities)
  const [savingFacilityId, setSavingFacilityId] = useState<string | null>(null)

  const commit = useCallback((next: SavedFacility[]) => {
    setFavorites(next)
    saveSavedFacilities(next)
  }, [])

  const refreshFavorites = useCallback(async () => {
    const profile = await request<ProfileResponse>('')
    setFavorites((current) => {
      const currentByFacilityId = new Map(current.map((favorite) => [favorite.facilityId, favorite]))
      const next = profile.favorites.map((favorite) =>
        savedFrom(favorite, currentByFacilityId.get(favorite.facilityId)?.snapshot),
      )
      saveSavedFacilities(next)
      return next
    })
  }, [])

  const saveFacility = useCallback(
    async (facility: Facility) => {
      if (favorites.some((favorite) => favorite.facilityId === facility.id)) return

      setSavingFacilityId(facility.id)
      try {
        const created = await request<FavoriteResponse>('/favorites', {
          method: 'POST',
          body: JSON.stringify({
            facilityId: facility.id,
            facilityType: facility.type,
            alias: null,
            memo: null,
          }),
        })
        commit([...favorites, savedFrom(created, snapshotFrom(facility))])
      } finally {
        setSavingFacilityId(null)
      }
    },
    [commit, favorites],
  )

  const updateFavorite = useCallback(
    async (favorite: SavedFacility, alias: string, note: string) => {
      const updated = await request<FavoriteResponse>(`/favorites/${encodeURIComponent(favorite.id)}`, {
        method: 'PATCH',
        body: JSON.stringify({ alias: alias || null, memo: note || null }),
      })
      const next = favorites.map((item) =>
        item.id === favorite.id
          ? { ...savedFrom(updated, item.snapshot), createdAt: item.createdAt, updatedAt: new Date().toISOString() }
          : item,
      )
      commit(next)
    },
    [commit, favorites],
  )

  const removeFavorite = useCallback(
    async (favorite: SavedFacility) => {
      await request<void>(`/favorites/${encodeURIComponent(favorite.id)}`, { method: 'DELETE' })
      commit(favorites.filter((item) => item.id !== favorite.id))
    },
    [commit, favorites],
  )

  return (
    <FavoritesContext.Provider
      value={{ favorites, savingFacilityId, saveFacility, updateFavorite, removeFavorite, refreshFavorites }}
    >
      {children}
    </FavoritesContext.Provider>
  )
}

export function useFavorites(): FavoritesContextValue {
  const context = useContext(FavoritesContext)
  if (!context) throw new Error('useFavorites must be used inside FavoritesProvider')
  return context
}
