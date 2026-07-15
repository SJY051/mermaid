import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { LocationSessionProvider, useLocationSession } from '../lib/locationSession'
import { MobileShell } from './MobileShell'
import { ONBOARDING_STORAGE_KEY, Onboarding } from './Onboarding'

const DISCLAIMER = 'General information, not medical advice · Emergency? Call 119'
const PREFERENCES_KEY = 'mermaid.preferences.v1'
const originalGeolocation = navigator.geolocation
const originalLocalStorage = localStorage
const originalSessionStorage = sessionStorage

function installStorage(local: Storage, session: Storage) {
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: local,
  })
  Object.defineProperty(globalThis, 'sessionStorage', {
    configurable: true,
    value: session,
  })
}

function auditedStorage(backing: Storage) {
  const getItem = vi.fn((key: string) => backing.getItem(key))
  const setItem = vi.fn((key: string, value: string) => backing.setItem(key, value))
  const removeItem = vi.fn((key: string) => backing.removeItem(key))
  const clear = vi.fn(() => backing.clear())
  const key = vi.fn((index: number) => backing.key(index))
  const storage = {
    get length() {
      return backing.length
    },
    getItem,
    setItem,
    removeItem,
    clear,
    key,
  } satisfies Storage

  return { storage, getItem, setItem, removeItem, clear, key }
}

vi.stubGlobal(
  'ResizeObserver',
  class {
    observe() {}
    unobserve() {}
    disconnect() {}
  },
)

function grantLocation() {
  const getCurrentPosition = vi.fn((onSuccess: PositionCallback) => {
    onSuccess({
      coords: { latitude: 37.5, longitude: 127 },
      timestamp: Date.now(),
    } as GeolocationPosition)
  })
  Object.defineProperty(navigator, 'geolocation', {
    configurable: true,
    value: { getCurrentPosition },
  })
  return getCurrentPosition
}

function denyLocation() {
  const getCurrentPosition = vi.fn(
    (_onSuccess: PositionCallback, onError: PositionErrorCallback | null) => {
      onError?.({
        code: 1,
        message: 'Permission denied',
        PERMISSION_DENIED: 1,
        POSITION_UNAVAILABLE: 2,
        TIMEOUT: 3,
      } as GeolocationPositionError)
    },
  )
  Object.defineProperty(navigator, 'geolocation', {
    configurable: true,
    value: { getCurrentPosition },
  })
  return getCurrentPosition
}

function LocationProbe() {
  const { location } = useLocationSession()
  return <output data-testid="location-source">{location?.source ?? 'none'}</output>
}

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
})

afterEach(() => {
  installStorage(originalLocalStorage, originalSessionStorage)
  vi.restoreAllMocks()
  Object.defineProperty(navigator, 'geolocation', {
    configurable: true,
    value: originalGeolocation,
  })
})

describe('onboarding success criteria', () => {
  it('shows screen 1 on the first visit and goes straight to Chat on the second (SC-001)', async () => {
    const user = userEvent.setup()
    const firstVisit = render(<MobileShell />)

    expect(screen.getByRole('heading', { name: 'What this is' })).toBeVisible()
    expect(screen.queryByRole('textbox', { name: 'Describe your symptoms' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Skip' }))
    expect(screen.getByRole('textbox', { name: 'Describe your symptoms' })).toBeVisible()
    expect(JSON.parse(localStorage.getItem(ONBOARDING_STORAGE_KEY)!)).toBe(true)

    firstVisit.unmount()
    render(<MobileShell />)
    expect(screen.getByRole('textbox', { name: 'Describe your symptoms' })).toBeVisible()
    expect(screen.queryByRole('heading', { name: 'What this is' })).not.toBeInTheDocument()
  })

  it('keeps the canonical disclaimer strip visible on all three screens (SC-003)', async () => {
    const user = userEvent.setup()
    render(<MobileShell />)

    for (const heading of [
      'What this is',
      'General information, not medical advice.',
      'Why we would like your location',
    ]) {
      expect(screen.getByRole('heading', { name: heading })).toBeVisible()
      expect(screen.getAllByText(DISCLAIMER)).toHaveLength(1)
      expect(screen.getByText(DISCLAIMER)).toBeVisible()
      if (heading !== 'Why we would like your location') {
        await user.click(screen.getByRole('button', { name: 'Next' }))
      }
    }
  })

  it('uses the prescribed copy and says location is optional', async () => {
    const user = userEvent.setup()
    render(<MobileShell />)

    expect(
      screen.getByText(
        'Medicine information verified against Korean government data, explained in English. It never diagnoses.',
      ),
    ).toBeVisible()
    await user.click(screen.getByRole('button', { name: 'Next' }))
    const disclaimerHeading = screen.getByRole('heading', {
      name: 'General information, not medical advice.',
    })
    expect(disclaimerHeading).toHaveFocus()
    expect(screen.getByText('This conversation is not saved and dies with the tab.')).toBeVisible()
    await user.click(screen.getByRole('button', { name: 'Next' }))
    expect(
      screen.getByRole('heading', { name: 'Why we would like your location' }),
    ).toHaveFocus()
    expect(
      screen.getByText(
        'To show pharmacies and hospitals that are open near you, right now. We do not store where you are.',
      ),
    ).toBeVisible()
    expect(screen.getByText('Location is optional; the app works without it.')).toBeVisible()
  })

  it.each([1, 2, 3])('reaches Chat with one Skip tap from screen %s (SC-005)', async (targetScreen) => {
    const getCurrentPosition = grantLocation()
    const user = userEvent.setup()
    render(<MobileShell />)

    for (let currentScreen = 1; currentScreen < targetScreen; currentScreen += 1) {
      await user.click(screen.getByRole('button', { name: 'Next' }))
    }
    await user.click(screen.getByRole('button', { name: 'Skip' }))

    expect(screen.getByRole('textbox', { name: 'Describe your symptoms' })).toBeVisible()
    expect(screen.getByRole('button', { name: 'Chat' })).toHaveAttribute('aria-current', 'page')
    expect(getCurrentPosition).not.toHaveBeenCalled()
  })

  it('reads or writes no allergy storage across the complete flow (SC-004)', async () => {
    const allergyPreferences = JSON.stringify({
      schemaVersion: '1.0',
      data: {
        rememberAllergies: true,
        allergies: ['ibuprofen'],
        defaultRadiusM: 1000,
        manualLocation: null,
      },
    })
    localStorage.setItem(PREFERENCES_KEY, allergyPreferences)
    const local = auditedStorage(originalLocalStorage)
    const session = auditedStorage(originalSessionStorage)
    installStorage(local.storage, session.storage)
    const getCurrentPosition = grantLocation()
    const onComplete = vi.fn()
    const user = userEvent.setup()
    render(
      <LocationSessionProvider>
        <Onboarding onComplete={onComplete} />
      </LocationSessionProvider>,
    )

    await user.click(screen.getByRole('button', { name: 'Next' }))
    await user.click(screen.getByRole('button', { name: 'Next' }))
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(screen.queryByText(/allerg/i)).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Use my location' }))

    await waitFor(() => expect(onComplete).toHaveBeenCalledOnce())
    expect(getCurrentPosition).toHaveBeenCalledOnce()
    expect(local.getItem).not.toHaveBeenCalled()
    expect(local.setItem).toHaveBeenCalledOnce()
    expect(local.setItem).toHaveBeenCalledWith(
      ONBOARDING_STORAGE_KEY,
      JSON.stringify(true),
    )
    expect(local.removeItem).not.toHaveBeenCalled()
    expect(local.clear).not.toHaveBeenCalled()
    expect(local.key).not.toHaveBeenCalled()
    expect(session.getItem).not.toHaveBeenCalled()
    expect(session.setItem).not.toHaveBeenCalled()
    expect(session.removeItem).not.toHaveBeenCalled()
    expect(session.clear).not.toHaveBeenCalled()
    expect(session.key).not.toHaveBeenCalled()
    expect(Object.keys(originalLocalStorage).sort()).toEqual(
      [ONBOARDING_STORAGE_KEY, PREFERENCES_KEY].sort(),
    )
  })

  it('continues without caching a fallback when location permission is denied', async () => {
    const getCurrentPosition = denyLocation()
    const onComplete = vi.fn()
    const user = userEvent.setup()
    render(
      <LocationSessionProvider>
        <Onboarding onComplete={onComplete} />
        <LocationProbe />
      </LocationSessionProvider>,
    )

    await user.click(screen.getByRole('button', { name: 'Next' }))
    await user.click(screen.getByRole('button', { name: 'Next' }))
    await user.click(screen.getByRole('button', { name: 'Use my location' }))

    await waitFor(() => expect(onComplete).toHaveBeenCalledOnce())
    expect(getCurrentPosition).toHaveBeenCalledOnce()
    expect(screen.getByTestId('location-source')).toHaveTextContent('none')
  })
})
