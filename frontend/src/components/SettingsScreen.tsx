import { useEffect, useRef, useState, useSyncExternalStore, type CSSProperties } from 'react'
import { Card } from '@astryxdesign/core/Card'
import {
  SegmentedControl,
  SegmentedControlItem,
} from '@astryxdesign/core/SegmentedControl'
import { Switch } from '@astryxdesign/core/Switch'
import { useChatSession } from '../lib/chatSession'
import {
  getAppearancePreference,
  getAllergyMemorySnapshot,
  loadAllergyMemory,
  loadChatSession,
  saveAllergyMemory,
  setAppearancePreference,
  subscribeAppearancePreference,
  subscribeAllergyMemory,
  syncAllergyMemory,
  forgetAllergyMemory,
  type AppearancePreference,
} from '../lib/storage'
import { DestructiveConsentDialog } from './DestructiveConsentDialog'

function isAppearancePreference(value: string): value is AppearancePreference {
  return value === 'light' || value === 'dark' || value === 'device'
}

function sameIngredients(left: readonly string[], right: readonly string[]) {
  return left.length === right.length && left.every((ingredient, index) => ingredient === right[index])
}

function mergeRememberedIngredients(current: readonly string[], remembered: readonly string[]) {
  return [...remembered, ...current.filter((ingredient) => !remembered.includes(ingredient))]
}

function allergyMemoryFromSnapshot(snapshot: string | null): string[] {
  return snapshot === null ? [] : JSON.parse(snapshot) as string[]
}

/**
 * Owns the two choices that may outlive the current tab: appearance and allergy memory.
 */
export function SettingsScreen() {
  const { allergies, unverifiedAllergens, confirmAllergies } = useChatSession()
  const appearance = useSyncExternalStore(
    subscribeAppearancePreference,
    getAppearancePreference,
    (): AppearancePreference => 'device',
  )
  const allergyMemorySnapshot = useSyncExternalStore(
    subscribeAllergyMemory,
    getAllergyMemorySnapshot,
    () => null,
  )
  const rememberAllergies = allergyMemorySnapshot !== null
  const [allergiesToForget, setAllergiesToForget] = useState<string[] | null>(null)
  const memoryStateInitialized = useRef(false)
  const previousAllergies = useRef(allergies)
  const processedAllergyMemorySnapshot = useRef(allergyMemorySnapshot)
  const suppressNextMemorySync = useRef(false)
  const rememberedAllergies = allergyMemoryFromSnapshot(allergyMemorySnapshot)
  const deviceAllergies = rememberAllergies
    ? allergies.filter((ingredient) => rememberedAllergies.includes(ingredient))
    : []
  const sessionOnlyAllergies = rememberAllergies
    ? allergies.filter((ingredient) => !rememberedAllergies.includes(ingredient))
    : allergies

  useEffect(() => {
    const previousSessionAllergies = previousAllergies.current
    const allergiesChanged = !sameIngredients(allergies, previousSessionAllergies)
    previousAllergies.current = allergies

    if (!rememberAllergies) {
      memoryStateInitialized.current = true
      processedAllergyMemorySnapshot.current = null
      suppressNextMemorySync.current = false
      return
    }

    const remembered = loadAllergyMemory() ?? []
    const currentMemorySnapshot = JSON.stringify(remembered)
    const previouslyProcessedMemory = allergyMemoryFromSnapshot(
      processedAllergyMemorySnapshot.current,
    )
    const memoryChanged = currentMemorySnapshot !== processedAllergyMemorySnapshot.current
    processedAllergyMemorySnapshot.current = currentMemorySnapshot
    const isInitialMemoryRead = !memoryStateInitialized.current
    memoryStateInitialized.current = true
    const hasCurrentSession = loadChatSession().sessionId !== ''
    if (!hasCurrentSession && allergies.length === 0 && remembered.length > 0) {
      // Only reviewed ingredient keys cross the tab boundary. Typed names remain in this session
      // and keep their weaker name-match-only promise when they are shown below.
      suppressNextMemorySync.current = true
      confirmAllergies(remembered, unverifiedAllergens, false)
      return
    }

    if (allergiesChanged) {
      if (suppressNextMemorySync.current) {
        suppressNextMemorySync.current = false
        return
      }

      // A storage event may be queued behind this tab's picker confirmation. Apply the local full
      // list to what this tab last processed, while preserving keys another tab added in between.
      // A normal local deletion still wins because an already-known remembered key is not "added".
      const remotelyAdded = remembered.filter(
        (ingredient) => !previouslyProcessedMemory.includes(ingredient),
      )
      const nextRemembered = [
        ...allergies,
        ...remotelyAdded.filter((ingredient) => !allergies.includes(ingredient)),
      ]
      syncAllergyMemory(nextRemembered)
      processedAllergyMemorySnapshot.current = JSON.stringify(nextRemembered)
      if (!sameIngredients(nextRemembered, allergies)) {
        suppressNextMemorySync.current = true
        confirmAllergies(nextRemembered, unverifiedAllergens, false)
      }
      return
    }

    if (isInitialMemoryRead || memoryChanged) {
      // Initial mount and a storage notification both read the current device copy; only a later
      // allergy edit in this mounted tab writes it. Keep the session fail-closed with the union, but
      // leave the device copy authoritative so a stale or reloaded tab cannot resurrect a key that
      // was deliberately removed elsewhere. Typed names remain session-only.
      const merged = mergeRememberedIngredients(allergies, remembered)
      if (!sameIngredients(merged, allergies)) {
        suppressNextMemorySync.current = true
        confirmAllergies(merged, unverifiedAllergens, false)
      }
      return
    }
  }, [allergies, allergyMemorySnapshot, confirmAllergies, rememberAllergies, unverifiedAllergens])

  function changeAppearance(value: string) {
    if (isAppearancePreference(value)) setAppearancePreference(value)
  }

  function changeAllergyMemory(checked: boolean) {
    if (checked) {
      saveAllergyMemory(allergies)
      return
    }

    setAllergiesToForget(loadAllergyMemory() ?? [])
  }

  function confirmForgetAllergies() {
    // The device copy is gone before the controlled switch settles in its off state (SC-003).
    forgetAllergyMemory()
    setAllergiesToForget(null)
  }

  return (
    <div className="flex min-h-full flex-col gap-3 bg-muted p-4">
      <h1 className="text-xl font-semibold text-primary">Settings</h1>

      <Card width="100%">
        <section className="flex flex-col gap-3">
          <h2 className="font-medium text-primary">Appearance</h2>
          <SegmentedControl
            label="Appearance"
            value={appearance}
            onChange={changeAppearance}
            layout="fill"
            // Neutral 0.1.4's secondary label is 4.15:1 on this light background (2026-07-15).
            // Scope the existing primary token to this control so every state clears WCAG AA.
            style={
              {
                '--color-text-secondary': 'var(--color-text-primary)',
              } as CSSProperties
            }
          >
            <SegmentedControlItem value="light" label="Light" />
            <SegmentedControlItem value="dark" label="Dark" />
            <SegmentedControlItem value="device" label="Follow my device" />
          </SegmentedControl>
        </section>
      </Card>

      <Card width="100%">
        <section className="flex flex-col gap-1">
          <div className="flex items-center justify-between gap-4">
            <h2 className="font-medium text-primary">Language</h2>
            <span className="text-sm text-secondary">English</span>
          </div>
          <p className="text-sm text-secondary">
            <span lang="ko">한국어</span> — coming later.
          </p>
        </section>
      </Card>

      <Card width="100%">
        <section className="flex flex-col gap-3">
          <Switch
            label="Remember my allergies"
            description={`${
              rememberAllergies
                ? 'On — your allergy list is kept on this device, so you do not have to tell us again.'
                : 'Off — your allergy list is used for this conversation only, and is forgotten when you close the tab.'
            } Either way, the ingredients you name are sent with each question — that is how we check a medicine against them. They are used to answer, and not stored on our server.`}
            labelPosition="start"
            labelSpacing="spread"
            width="100%"
            value={rememberAllergies}
            onChange={changeAllergyMemory}
            // Neutral 0.1.4's secondary text is 4.15:1 on this surface (2026-07-15).
            style={
              {
                '--color-text-secondary': 'var(--color-text-primary)',
              } as CSSProperties
            }
          />

          {allergies.length > 0 && (
            <p className="text-sm text-primary" aria-label="Selected allergy ingredients">
              {deviceAllergies.join(' · ')}
              {deviceAllergies.length > 0 && sessionOnlyAllergies.length > 0 ? ' · ' : ''}
              {sessionOnlyAllergies.join(' · ')}{' '}
              {sessionOnlyAllergies.length > 0 && (
                <span className="text-secondary">(this session only)</span>
              )}
            </p>
          )}

          {unverifiedAllergens.length > 0 && (
            <div className="flex flex-col gap-1" aria-label="Name-match-only allergies">
              {unverifiedAllergens.map((allergen) => (
                <p key={allergen.toLocaleLowerCase()} className="text-sm text-secondary">
                  <span className="text-primary">{allergen}</span> — name-match warnings only — a
                  pharmacist can fully check this one (this session only)
                </p>
              ))}
            </div>
          )}
        </section>
      </Card>

      <Card width="100%">
        <section className="flex flex-col gap-1">
          <h2 className="font-medium text-primary">About</h2>
          <p className="text-sm leading-relaxed text-secondary">
            Sources: <span lang="ko">식약처</span> · <span lang="ko">심평원</span> ·{' '}
            <span lang="ko">국립중앙의료원</span>. This app informs — it never diagnoses.
          </p>
        </section>
      </Card>

      <DestructiveConsentDialog
        isOpen={allergiesToForget !== null}
        title="Forget your allergy list?"
        itemNames={allergiesToForget ?? []}
        fallbackItemName="your allergy list"
        location="this device"
        recoveryMessage="You can tell us again at any time."
        onCancel={() => setAllergiesToForget(null)}
        onConfirm={confirmForgetAllergies}
      />
    </div>
  )
}
