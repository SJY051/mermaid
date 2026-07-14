import { beforeEach, describe, expect, it } from 'vitest'
import {
  clearChatSession,
  getDeviceId,
  loadChatSession,
  loadPreferences,
  loadSavedFacilities,
  savePreferences,
  setManualLocation,
  saveChatSession,
  saveSavedFacilities,
  type ChatSession,
  type Preferences,
} from './storage'

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
})

const CHAT_KEY = 'mermaid.chatSession.v1'
const PREFS_KEY = 'mermaid.preferences.v1'

const session: ChatSession = {
  sessionId: 's1',
  messages: [{ id: 'm1', role: 'user', content: 'I have a rash on my chest', createdAt: '2026-07-10T12:00:00Z' }],
  allergies: ['ibuprofen'],
  unverifiedAllergens: ['yellow dye'],
  unverifiableAllergy: false,
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
})

/** Allergy memory is opt-in, and OFF by default (spec §2-5). Both directions are enforced. */
describe('allergies are remembered only when the user said so', () => {
  const withAllergies: Preferences = {
    rememberAllergies: false,
    allergies: ['ibuprofen', 'aspirin'],
    defaultRadiusM: 500,
    manualLocation: null,
  }

  it('refuses to persist allergies when the toggle is off', () => {
    savePreferences(withAllergies)

    expect(localStorage.getItem(PREFS_KEY)).not.toContain('ibuprofen')
    expect(loadPreferences().allergies).toEqual([])
    // The rest of the preferences still save.
    expect(loadPreferences().defaultRadiusM).toBe(500)
  })

  it('persists them once the user opts in', () => {
    savePreferences({ ...withAllergies, rememberAllergies: true })
    expect(loadPreferences().allergies).toEqual(['ibuprofen', 'aspirin'])
  })

  it('hides allergies already on disk if the toggle was since turned off', () => {
    // Written by an older build, or by hand. The read path must not trust it.
    localStorage.setItem(
      PREFS_KEY,
      JSON.stringify({ schemaVersion: '1.0', data: { ...withAllergies, rememberAllergies: false } }),
    )
    expect(loadPreferences().allergies).toEqual([])
  })

  it('defaults to not remembering', () => {
    expect(loadPreferences().rememberAllergies).toBe(false)
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
    saveSavedFacilities([
      {
        id: 'f1',
        facilityId: 'facility:nmc:1',
        snapshot: { nameKo: '가나약국', type: 'pharmacy', addressKo: null },
        alias: '집 앞',
        note: '',
        createdAt: '2026-07-10T12:00:00Z',
        updatedAt: '2026-07-10T12:00:00Z',
      },
    ])
    expect(getDeviceId()).toBe(id)
    expect(loadSavedFacilities()).toHaveLength(1)
  })
})
