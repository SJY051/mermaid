import { beforeEach, describe, expect, it } from 'vitest'
import {
  applyAppearancePreference,
  clearChatSession,
  forgetAllergyMemory,
  getAppearancePreference,
  getDeviceId,
  loadAllergyMemory,
  loadChatSession,
  loadPreferences,
  loadSavedFacilities,
  saveAllergyMemory,
  savePreferences,
  saveChatSession,
  saveSavedFacilities,
  setAppearancePreference,
  setManualLocation,
  subscribeAllergyMemory,
  subscribeAppearancePreference,
  syncAllergyMemory,
  type ChatSession,
} from './storage'

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  document.documentElement.style.removeProperty('color-scheme')
})

const CHAT_KEY = 'mermaid.chatSession.v2'
const CHAT_KEY_V1 = 'mermaid.chatSession.v1'
const PREFS_KEY = 'mermaid.preferences.v1'
const ALLERGY_MEMORY_KEY = 'mermaid.allergyMemory.v1'
const SAVED_FACILITIES_STORAGE = 'mermaid.savedFacilities.v1'
const DEVICE_ID_KEY = 'mermaid.deviceId.v1'

const savedFacility = {
  id: 'f1',
  facilityId: 'facility:nmc:1',
  snapshot: {
    nameKo: '가나약국',
    type: 'pharmacy' as const,
    addressKo: null,
    operation: { isOpenNow: null, status: 'unknown' as const, statusConfidence: 'unknown' as const, verifiedAt: null, notice: '' },
    source: { id: 'nmc:1', provider: 'nmc', recordId: '1', retrievedAt: '2026-07-14T12:00:00Z', dataMode: 'live' as const, title: 'National Medical Center' },
  },
  alias: '',
  note: '',
  createdAt: '2026-07-10T12:00:00Z',
  updatedAt: '2026-07-10T12:00:00Z',
}

const session: ChatSession = {
  sessionId: 's1',
  messages: [{ id: 'm1', role: 'user', content: 'I have a rash on my chest', createdAt: '2026-07-10T12:00:00Z' }],
  allergies: ['ibuprofen'],
  unverifiedAllergens: ['yellow dye'],
  unverifiableAllergy: false,
  pendingQuestion: 'my throat is swelling',
  allergiesConfirmedAt: '',
}

/**
 * The requirements document claimed LocalStorage made the chat "secure by design" (spec §2-16).
 * It does not: it outlives the tab and is readable by anyone holding the device. A medical
 * transcript belongs in `sessionStorage`, and this test is the thing that keeps it there.
 */
describe('a consultation transcript never touches localStorage', () => {
  it('writes the chat to sessionStorage only', () => {
    saveChatSession(session)

    expect(sessionStorage.getItem(CHAT_KEY)).toBeTruthy()
    expect(localStorage.getItem(CHAT_KEY)).toBeNull()
  })

  it('leaves nothing about the symptom anywhere in localStorage', () => {
    saveChatSession(session)

    const everythingPersisted = Object.keys(localStorage)
      .map((k) => localStorage.getItem(k) ?? '')
      .join('\n')
    expect(everythingPersisted).not.toContain('rash')
    expect(everythingPersisted).not.toContain('ibuprofen')
    expect(everythingPersisted).not.toContain('yellow dye')
    // An unanswered question is still a symptom description. It is kept so a reload does not drop
    // it out of the next request's history — kept in this tab, and nowhere that outlives it.
    expect(everythingPersisted).not.toContain('swelling')
  })

  it('round-trips and clears', () => {
    saveChatSession(session)
    expect(loadChatSession().messages[0].content).toContain('rash')

    clearChatSession()
    expect(loadChatSession().messages).toEqual([])
  })
})

/**
 * A stored blob is untrusted input. It may have been written by an older build, a newer one, or
 * a browser extension. None of those may crash the app for a sick person holding a phone.
 */
describe('corrupt or stale storage resets instead of throwing', () => {
  it('drops a blob written by a different schema version', () => {
    sessionStorage.setItem(CHAT_KEY, JSON.stringify({ schemaVersion: '0.9', data: session }))

    expect(loadChatSession().messages).toEqual([])
    expect(sessionStorage.getItem(CHAT_KEY)).toBeNull()
  })

  it('drops a blob that is not JSON at all', () => {
    localStorage.setItem(PREFS_KEY, '{ this is not json')

    expect(loadPreferences().defaultRadiusM).toBe(1000)
    expect(localStorage.getItem(PREFS_KEY)).toBeNull()
  })

  it('returns the fallback when nothing was ever stored', () => {
    expect(loadSavedFacilities()).toEqual([])
    expect(loadChatSession().sessionId).toBe('')
  })

  it.each([null, [null]])('drops same-version corrupt saved facilities with data %p', (data) => {
    localStorage.setItem(SAVED_FACILITIES_STORAGE, JSON.stringify({ schemaVersion: '1.0', data }))

    expect(loadSavedFacilities()).toEqual([])
    expect(localStorage.getItem(SAVED_FACILITIES_STORAGE)).toBeNull()
  })
})

/** The key itself is the opt-in record: absent is OFF, including on a new device (spec §2-5). */
describe('allergy memory has its own consent-scoped key', () => {
  it('is off by default and does not create a key while reading', () => {
    expect(loadAllergyMemory()).toBeNull()
    expect(localStorage.getItem(ALLERGY_MEMORY_KEY)).toBeNull()
  })

  it('stays off when existing preferences contain UI fields only', () => {
    const uiPreferences = JSON.stringify({
      schemaVersion: '1.0',
      data: {
        defaultRadiusM: 500,
        manualLocation: { lat: 37.5, lng: 127, label: 'Chosen place' },
        appearance: 'dark',
      },
    })
    localStorage.setItem(PREFS_KEY, uiPreferences)

    expect(loadAllergyMemory()).toBeNull()
    expect(localStorage.getItem(ALLERGY_MEMORY_KEY)).toBeNull()
    expect(localStorage.getItem(PREFS_KEY)).toBe(uiPreferences)
  })

  it('stores reviewed ingredient keys without any session-only typed names', () => {
    saveAllergyMemory(['ibuprofen', 'aspirin'])
    savePreferences({
      defaultRadiusM: 500,
      manualLocation: null,
      // Old callers may still pass these fields. The UI-preferences writer must drop them rather
      // than putting medical data back into the shared preferences blob.
      rememberAllergies: true,
      allergies: ['legacy-ibuprofen'],
      unverifiedAllergens: ['yellow dye'],
    })

    expect(loadAllergyMemory()).toEqual(['ibuprofen', 'aspirin'])
    expect(localStorage.getItem(PREFS_KEY)).not.toContain('rememberAllergies')
    expect(localStorage.getItem(PREFS_KEY)).not.toContain('allergies')
    expect(localStorage.getItem(PREFS_KEY)).not.toContain('yellow dye')
    expect(localStorage.getItem(ALLERGY_MEMORY_KEY)).not.toContain('yellow dye')
  })

  it('forgetting removes only allergy memory and preserves every other storage area', () => {
    savePreferences({
      defaultRadiusM: 500,
      manualLocation: { lat: 37.5, lng: 127, label: 'Chosen place' },
      appearance: 'dark',
    })
    saveSavedFacilities([savedFacility])
    getDeviceId()
    saveChatSession(session)
    saveAllergyMemory(['ibuprofen'])
    const unchangedStorage = new Map([
      [PREFS_KEY, localStorage.getItem(PREFS_KEY)],
      [SAVED_FACILITIES_STORAGE, localStorage.getItem(SAVED_FACILITIES_STORAGE)],
      [DEVICE_ID_KEY, localStorage.getItem(DEVICE_ID_KEY)],
      [CHAT_KEY, sessionStorage.getItem(CHAT_KEY)],
    ])

    forgetAllergyMemory()

    expect(localStorage.getItem(ALLERGY_MEMORY_KEY)).toBeNull()
    expect(localStorage.getItem(PREFS_KEY)).toBe(unchangedStorage.get(PREFS_KEY))
    expect(localStorage.getItem(SAVED_FACILITIES_STORAGE)).toBe(
      unchangedStorage.get(SAVED_FACILITIES_STORAGE),
    )
    expect(localStorage.getItem(DEVICE_ID_KEY)).toBe(unchangedStorage.get(DEVICE_ID_KEY))
    expect(sessionStorage.getItem(CHAT_KEY)).toBe(unchangedStorage.get(CHAT_KEY))
    expect(loadPreferences()).toEqual({
      defaultRadiusM: 500,
      manualLocation: { lat: 37.5, lng: 127, label: 'Chosen place' },
      appearance: 'dark',
    })
  })

  it('does not let background synchronization recreate an opted-out key', () => {
    saveAllergyMemory(['ibuprofen'])
    forgetAllergyMemory()

    syncAllergyMemory(['aspirin'])

    expect(loadAllergyMemory()).toBeNull()
    expect(localStorage.getItem(ALLERGY_MEMORY_KEY)).toBeNull()
  })

  it('notifies allergy-memory subscribers for same-tab changes and cross-tab clear', () => {
    let calls = 0
    const unsubscribe = subscribeAllergyMemory(() => { calls += 1 })

    saveAllergyMemory(['ibuprofen'])
    expect(calls).toBe(1)

    window.dispatchEvent(new StorageEvent('storage', { key: null, storageArea: localStorage }))
    expect(calls).toBe(2)

    unsubscribe()
    forgetAllergyMemory()
    expect(calls).toBe(2)
  })
})

describe('legacy allergy preferences migrate once and are scrubbed', () => {
  it('moves a valid reviewed list without migrating legacy typed names', () => {
    localStorage.setItem(
      PREFS_KEY,
      JSON.stringify({
        schemaVersion: '1.0',
        data: {
          rememberAllergies: true,
          allergies: ['ibuprofen', 'aspirin'],
          unverifiedAllergens: ['yellow dye'],
          defaultRadiusM: 500,
          manualLocation: { lat: 37.5, lng: 127, label: 'Chosen place' },
          appearance: 'dark',
        },
      }),
    )

    expect(loadAllergyMemory()).toEqual(['ibuprofen', 'aspirin'])
    expect(localStorage.getItem(ALLERGY_MEMORY_KEY)).not.toContain('yellow dye')
    expect(loadPreferences()).toEqual({
      defaultRadiusM: 500,
      manualLocation: { lat: 37.5, lng: 127, label: 'Chosen place' },
      appearance: 'dark',
    })
    expect(localStorage.getItem(PREFS_KEY)).not.toContain('allergies')
    expect(localStorage.getItem(PREFS_KEY)).not.toContain('yellow dye')
  })

  it('fails closed instead of partially migrating a corrupt legacy reviewed list', () => {
    localStorage.setItem(
      PREFS_KEY,
      JSON.stringify({
        schemaVersion: '1.0',
        data: {
          rememberAllergies: true,
          allergies: ['ibuprofen', 42, 'aspirin'],
          unverifiedAllergens: ['yellow dye'],
          defaultRadiusM: 500,
          manualLocation: null,
          appearance: 'dark',
        },
      }),
    )

    expect(loadAllergyMemory()).toEqual([])
    expect(loadPreferences()).toEqual({
      defaultRadiusM: 500,
      manualLocation: null,
      appearance: 'dark',
    })
    expect(localStorage.getItem(PREFS_KEY)).not.toContain('rememberAllergies')
    expect(localStorage.getItem(PREFS_KEY)).not.toContain('allergies')
    expect(localStorage.getItem(PREFS_KEY)).not.toContain('yellow dye')
  })

  it('does not overwrite a valid new-key list with stale legacy data', () => {
    saveAllergyMemory(['newer-key'])
    localStorage.setItem(
      PREFS_KEY,
      JSON.stringify({
        schemaVersion: '1.0',
        data: {
          rememberAllergies: true,
          allergies: ['stale-key'],
          defaultRadiusM: 1000,
          manualLocation: null,
        },
      }),
    )

    expect(loadAllergyMemory()).toEqual(['newer-key'])
    expect(localStorage.getItem(PREFS_KEY)).not.toContain('stale-key')
  })

  it('treats a valid new key as the current consent record over stale legacy off', () => {
    saveAllergyMemory(['newer-key'])
    localStorage.setItem(
      PREFS_KEY,
      JSON.stringify({
        schemaVersion: '1.0',
        data: {
          rememberAllergies: false,
          allergies: ['stale-key'],
          defaultRadiusM: 1000,
          manualLocation: null,
        },
      }),
    )

    expect(loadAllergyMemory()).toEqual(['newer-key'])
    expect(localStorage.getItem(PREFS_KEY)).not.toContain('stale-key')
  })

  it('migrates before an unrelated preference write can overwrite the legacy blob', () => {
    localStorage.setItem(
      PREFS_KEY,
      JSON.stringify({
        schemaVersion: '1.0',
        data: {
          rememberAllergies: true,
          allergies: ['ibuprofen'],
          defaultRadiusM: 1000,
          manualLocation: null,
        },
      }),
    )

    savePreferences({ defaultRadiusM: 500, manualLocation: null, appearance: 'dark' })

    expect(loadAllergyMemory()).toEqual(['ibuprofen'])
    expect(loadPreferences().appearance).toBe('dark')
  })

  it('drops legacy allergy fields when the legacy opt-in was off', () => {
    localStorage.setItem(
      PREFS_KEY,
      JSON.stringify({
        schemaVersion: '1.0',
        data: {
          rememberAllergies: false,
          allergies: ['ibuprofen'],
          unverifiedAllergens: ['yellow dye'],
          defaultRadiusM: 1000,
          manualLocation: null,
        },
      }),
    )

    expect(loadAllergyMemory()).toBeNull()
    expect(localStorage.getItem(ALLERGY_MEMORY_KEY)).toBeNull()
    expect(localStorage.getItem(PREFS_KEY)).not.toContain('ibuprofen')
    expect(localStorage.getItem(PREFS_KEY)).not.toContain('yellow dye')
  })

  it('drops an invalid null payload from the dedicated key', () => {
    localStorage.setItem(
      ALLERGY_MEMORY_KEY,
      JSON.stringify({ schemaVersion: '1.0', data: null }),
    )

    expect(loadAllergyMemory()).toBeNull()
    expect(localStorage.getItem(ALLERGY_MEMORY_KEY)).toBeNull()
  })
})

describe('appearance is a persistent subscribed UI preference', () => {
  it('defaults to the device, applies each value to the root, and persists it', () => {
    expect(getAppearancePreference()).toBe('device')

    applyAppearancePreference('device')
    expect(document.documentElement.style.colorScheme).toBe('light dark')

    setAppearancePreference('dark')
    expect(getAppearancePreference()).toBe('dark')
    expect(document.documentElement.style.colorScheme).toBe('dark')

    setAppearancePreference('light')
    expect(getAppearancePreference()).toBe('light')
    expect(document.documentElement.style.colorScheme).toBe('light')
  })

  it('notifies same-tab subscribers and stops after unsubscribe', () => {
    let calls = 0
    const unsubscribe = subscribeAppearancePreference(() => { calls += 1 })

    setAppearancePreference('dark')
    expect(calls).toBe(1)

    unsubscribe()
    setAppearancePreference('light')
    expect(calls).toBe(1)
  })

  it('notifies appearance subscribers when another tab clears local storage', () => {
    let calls = 0
    const unsubscribe = subscribeAppearancePreference(() => { calls += 1 })

    window.dispatchEvent(new StorageEvent('storage', { key: null, storageArea: localStorage }))
    expect(calls).toBe(1)

    unsubscribe()
  })

  it('is preserved when allergy memory is saved and forgotten', () => {
    setAppearancePreference('dark')
    saveAllergyMemory(['ibuprofen'])
    forgetAllergyMemory()

    expect(getAppearancePreference()).toBe('dark')
  })
})

describe('manual location is a persistent user preference', () => {
  it('survives a reload and clearing removes it', () => {
    const manualLocation = { lat: 35.1796, lng: 129.0756, label: 'Chosen map spot' }

    setManualLocation(manualLocation)
    expect(loadPreferences().manualLocation).toEqual(manualLocation)
    expect(loadPreferences().manualLocation).toEqual(manualLocation)

    setManualLocation(null)
    expect(loadPreferences().manualLocation).toBeNull()
    expect(localStorage.getItem(PREFS_KEY)).not.toContain('35.1796')
  })
})

describe('deviceId is anonymous and stable', () => {
  it('is generated once and reused', () => {
    const first = getDeviceId()
    expect(getDeviceId()).toBe(first)
    expect(first).toMatch(/^[0-9a-f-]{36}$/i)
  })

  it('survives a saved-facilities write', () => {
    const id = getDeviceId()
    saveSavedFacilities([savedFacility])
    expect(getDeviceId()).toBe(id)
    expect(loadSavedFacilities()).toHaveLength(1)
  })

  it('refuses a conversation stored before the grounding invariants, and deletes it (P0)', () => {
    // A turn saved before invariants 7 and 8 holds a card whose directions, warnings and prescription
    // status the MODEL wrote. The card that renders it now labels the directions as the ministry's own
    // Korean text — "we do not translate doses". Restore one and English prose the model invented is
    // presented as the government's words, under the verified footer. A reload is enough to do it.
    //
    // There is no migration: the facts were never in the blob. The old key must simply be unreadable,
    // and gone — it holds a medical conversation, and a key nobody reads is still a key someone can read.
    sessionStorage.setItem(
      CHAT_KEY_V1,
      JSON.stringify({
        schemaVersion: '1.0',
        data: { sessionId: 's', messages: [{ id: 'm1', role: 'user', content: 'headache', createdAt: 'x' }] },
      }),
    )

    const restored = loadChatSession()

    expect(restored.messages).toEqual([])
    expect(sessionStorage.getItem(CHAT_KEY_V1)).toBeNull()
  })
})
