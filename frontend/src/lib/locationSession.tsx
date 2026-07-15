import { createContext, useContext, useMemo, useState, type ReactNode } from 'react'
import { SEOUL_CITY_HALL, type ResolvedLocation } from './facilities'
import { loadPreferences } from './storage'

interface LocationSessionValue {
  location: ResolvedLocation | null
  rememberLocation: (location: ResolvedLocation) => void
}

const LocationSessionContext = createContext<LocationSessionValue>({
  location: null,
  rememberLocation: () => {},
})

/** Keeps an explicitly requested location in memory for this app visit only. */
export function LocationSessionProvider({ children }: { children: ReactNode }) {
  const [location, setLocation] = useState<ResolvedLocation | null>(null)
  const value = useMemo(
    () => ({ location, rememberLocation: setLocation }),
    [location],
  )

  return (
    <LocationSessionContext.Provider value={value}>{children}</LocationSessionContext.Provider>
  )
}

export function useLocationSession(): LocationSessionValue {
  return useContext(LocationSessionContext)
}

/** Returns a useful map centre without touching the browser permission API. */
export function locationWithoutPrompt(): ResolvedLocation {
  const manualLocation = loadPreferences().manualLocation
  return manualLocation
    ? {
        lat: manualLocation.lat,
        lng: manualLocation.lng,
        source: 'manual',
        label: manualLocation.label,
      }
    : { ...SEOUL_CITY_HALL, source: 'fallback' }
}

/**
 * Requests the device coordinate without reading preferences, including allergy-bearing ones.
 * This is used by onboarding, where the seen flag is the only storage the flow may touch.
 */
export function requestDeviceLocation(timeoutMs = 8000): Promise<ResolvedLocation> {
  const fallback = (): ResolvedLocation => ({ ...SEOUL_CITY_HALL, source: 'fallback' })
  if (!navigator.geolocation) return Promise.resolve(fallback())

  return new Promise((resolve) => {
    navigator.geolocation.getCurrentPosition(
      (position) =>
        resolve({
          lat: position.coords.latitude,
          lng: position.coords.longitude,
          source: 'device',
        }),
      () => resolve(fallback()),
      { timeout: timeoutMs, maximumAge: 60_000 },
    )
  })
}
