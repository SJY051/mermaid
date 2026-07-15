import { useEffect, useRef, useState } from 'react'
import { Button } from '@astryxdesign/core/Button'
import { requestDeviceLocation, useLocationSession } from '../lib/locationSession'
import { DisclaimerStrip } from './DisclaimerStrip'

export const ONBOARDING_STORAGE_KEY = 'mermaid.onboarding.v1'
const ONBOARDING_SCHEMA_VERSION = '1.0'

export function hasSeenOnboarding(): boolean {
  try {
    const raw = localStorage.getItem(ONBOARDING_STORAGE_KEY)
    if (!raw) return false
    const stored = JSON.parse(raw) as unknown
    if (stored === true) return true

    // Worker A's contrast runner predates onboarding and seeds the repository's standard envelope.
    // Keep it readable while new visits store the boolean required by spec 008.
    return (
      typeof stored === 'object' &&
      stored !== null &&
      'schemaVersion' in stored &&
      stored.schemaVersion === ONBOARDING_SCHEMA_VERSION &&
      'data' in stored &&
      stored.data === true
    )
  } catch {
    return false
  }
}

function markOnboardingSeen(): void {
  try {
    localStorage.setItem(ONBOARDING_STORAGE_KEY, JSON.stringify(true))
  } catch {
    // Storage can be unavailable in private browsing. The app still has to remain usable.
  }
}

const SCREENS = [
  {
    heading: 'What this is',
    body: 'Medicine information verified against Korean government data, explained in English. It never diagnoses.',
  },
  {
    heading: 'General information, not medical advice.',
    body: 'This conversation is not saved and dies with the tab.',
  },
  {
    heading: 'Why we would like your location',
    body: 'To show pharmacies and hospitals that are open near you, right now. We do not store where you are.',
  },
] as const

export function Onboarding({ onComplete }: { onComplete: () => void }) {
  const [screenIndex, setScreenIndex] = useState(0)
  const { rememberLocation } = useLocationSession()
  const headingRef = useRef<HTMLHeadingElement>(null)
  const screen = SCREENS[screenIndex]

  useEffect(() => {
    headingRef.current?.focus()
  }, [screenIndex])

  function complete() {
    markOnboardingSeen()
    onComplete()
  }

  async function handleUseDeviceLocation() {
    const location = await requestDeviceLocation()
    if (location.source === 'device') rememberLocation(location)
    complete()
  }

  return (
    <div lang="en" className="flex h-full justify-center bg-muted">
      <div
        data-testid="app-shell"
        className="flex h-full w-full max-w-3xl flex-col border-primary bg-surface md:border-x"
      >
        <main
          aria-label={`Onboarding screen ${screenIndex + 1} of ${SCREENS.length}`}
          className="min-h-0 flex-1 overflow-y-auto"
        >
          <div className="flex min-h-full flex-col px-6 py-5">
            <div className="flex justify-end">
              <Button label="Skip" variant="ghost" onClick={complete} />
            </div>

            <section className="flex flex-1 items-center py-8">
              <div className="mx-auto w-full max-w-md space-y-4 text-center">
                <h1
                  ref={headingRef}
                  tabIndex={-1}
                  className="text-2xl font-semibold leading-tight text-primary focus:outline-none"
                >
                  {screen.heading}
                </h1>
                <p className="text-base leading-relaxed text-secondary">{screen.body}</p>
                {screenIndex === 2 && (
                  <p className="text-sm leading-relaxed text-primary">
                    Location is optional; the app works without it.
                  </p>
                )}
              </div>
            </section>

            <div className="mx-auto w-full max-w-md pb-2">
              {screenIndex < SCREENS.length - 1 ? (
                <Button
                  className="min-h-11 w-full"
                  label="Next"
                  variant="primary"
                  onClick={() => setScreenIndex((current) => current + 1)}
                />
              ) : (
                <Button
                  className="min-h-11 w-full"
                  label="Use my location"
                  variant="primary"
                  clickAction={handleUseDeviceLocation}
                />
              )}
            </div>
          </div>
        </main>
        <DisclaimerStrip />
      </div>
    </div>
  )
}
