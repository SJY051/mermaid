import { createContext, useCallback, useContext, useRef, useState } from 'react'
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
    operation: facility.operation,
    source: facility.source,
  }
}

function savedFrom(response: FavoriteResponse, snapshot: SavedFacility['snapshot']): SavedFacility {
  const now = new Date().toISOString()
  return {
    id: String(response.id),
    facilityId: response.facilityId,
    snapshot,
    alias: response.alias ?? '',
    note: response.memo ?? '',
    createdAt: now,
    updatedAt: now,
  }
}

export function FavoritesProvider({ children }: { children: React.ReactNode }) {
  const favoritesRef = useRef<SavedFacility[]>([])
  const refreshVersionRef = useRef(0)
  const [favorites, setFavorites] = useState(() => {
    const initial = loadSavedFacilities()
    favoritesRef.current = initial
    return initial
  })
  const [savingFacilityId, setSavingFacilityId] = useState<string | null>(null)

  const commit = useCallback((update: (current: SavedFacility[]) => SavedFacility[]) => {
    const next = update(favoritesRef.current)
    favoritesRef.current = next
    setFavorites(next)
    saveSavedFacilities(next)
    return next
  }, [])

  const refreshFavorites = useCallback(async () => {
    const refreshVersion = refreshVersionRef.current
    const profile = await request<ProfileResponse>('')
    if (refreshVersion !== refreshVersionRef.current) return
    commit((current) => {
      const currentByFacilityId = new Map(current.map((favorite) => [favorite.facilityId, favorite]))
      return profile.favorites.flatMap((favorite) => {
        const snapshot = currentByFacilityId.get(favorite.facilityId)?.snapshot
        return snapshot ? [savedFrom(favorite, snapshot)] : []
      })
    })
  }, [commit])

  const saveFacility = useCallback(
    async (facility: Facility) => {
      if (favoritesRef.current.some((favorite) => favorite.facilityId === facility.id)) return

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
        refreshVersionRef.current += 1
        commit((current) => {
          if (current.some((favorite) => favorite.facilityId === created.facilityId)) return current
          return [...current, savedFrom(created, snapshotFrom(facility))]
        })
      } finally {
        setSavingFacilityId(null)
      }
    },
    [commit],
  )

  const updateFavorite = useCallback(
    async (favorite: SavedFacility, alias: string, note: string) => {
      const updated = await request<FavoriteResponse>(`/favorites/${encodeURIComponent(favorite.id)}`, {
        method: 'PATCH',
        body: JSON.stringify({ alias: alias || null, memo: note || null }),
      })
      refreshVersionRef.current += 1
      commit((current) => current.map((item) =>
        item.id === favorite.id
          ? { ...savedFrom(updated, item.snapshot), createdAt: item.createdAt, updatedAt: new Date().toISOString() }
          : item,
      ))
    },
    [commit],
  )

  const removeFavorite = useCallback(
    async (favorite: SavedFacility) => {
      await request<void>(`/favorites/${encodeURIComponent(favorite.id)}`, { method: 'DELETE' })
      refreshVersionRef.current += 1
      commit((current) => current.filter((item) => item.id !== favorite.id))
    },
    [commit],
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
