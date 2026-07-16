/**
 * Browser storage, split by sensitivity (spec §2-16, §4).
 *
 * The original plan said LocalStorage made the chat "secure by design". It does not:
 * LocalStorage survives the tab, is readable by anyone with the device, and is exposed
 * to XSS. Medical consultation transcripts therefore live in `sessionStorage` and die
 * with the tab. Saved places and UI preferences may persist; reviewed allergy ingredient keys do
 * so only behind the explicit, separately deletable consent record below.
 *
 * Every stored value carries a `schemaVersion`. A corrupted or future-version blob must
 * never crash the app; we reset that one key and carry on.
 */

const SCHEMA_VERSION = '1.0'

const DEVICE_ID_KEY = 'mermaid.deviceId.v1'
// v2, and the bump IS the fix. A turn stored before the grounding invariants (7 and 8) carries a
// card whose directions, warnings and prescription status the MODEL wrote — and the card that renders
// it now labels `directionsSummary` as "official dosing from the Ministry of Food and Drug Safety, in
// Korean, we do not translate doses". Restore one of those turns and English prose the model invented
// is presented as the ministry's own words, under the verified footer. A reload is enough.
//
// There is no migration to write: the missing facts were never in the blob, so nothing can recover
// them. Renaming the key makes the old shape unreadable, which is the honest outcome — the
// conversation is gone, and it was never meant to outlive the tab anyway (§2-5). Bumping the shared
// SCHEMA_VERSION instead would have taken the user's saved location and preferences with it.
const CHAT_SESSION_KEY = 'mermaid.chatSession.v2'
const CHAT_SESSION_KEY_V1 = 'mermaid.chatSession.v1'
const SAVED_FACILITIES_KEY = 'mermaid.savedFacilities.v1'
const PREFERENCES_KEY = 'mermaid.preferences.v1'
const ALLERGY_MEMORY_KEY = 'mermaid.allergyMemory.v1'

interface Versioned<T> {
  schemaVersion: string
  data: T
}

function read<T>(store: Storage, key: string, fallback: T, isValid?: (value: unknown) => value is T): T {
  const raw = store.getItem(key)
  if (!raw) return fallback
  try {
    const parsed = JSON.parse(raw) as Versioned<T>
    if (parsed.schemaVersion !== SCHEMA_VERSION) {
      // An older or newer shape. Do not guess at a migration we have not written.
      store.removeItem(key)
      return fallback
    }
    if (isValid && !isValid(parsed.data)) {
      store.removeItem(key)
      return fallback
    }
    return parsed.data
  } catch {
    store.removeItem(key)
    return fallback
  }
}

function write<T>(store: Storage, key: string, data: T): void {
  store.setItem(key, JSON.stringify({ schemaVersion: SCHEMA_VERSION, data } satisfies Versioned<T>))
}

// --- deviceId: anonymous, persistent, not an account (spec §2-5) --------------

export function getDeviceId(): string {
  let id = read<string | null>(localStorage, DEVICE_ID_KEY, null)
  if (!id) {
    id = crypto.randomUUID()
    write(localStorage, DEVICE_ID_KEY, id)
  }
  return id
}

// --- chat: sessionStorage, dies with the tab ---------------------------------

export interface StoredMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  createdAt: string
}

export interface ChatSession {
  sessionId: string
  messages: StoredMessage[]
  /** Ingredients the user mentioned this session. NOT persisted unless they opt in. */
  allergies: string[]
  /** User-authored allergen names that have no reviewed dictionary binding. */
  unverifiedAllergens: string[]
  /**
   * The user closed a clarification without adding a verified selection or unverified name, so drug
   * lookup is ended for this conversation. Persisted with the conversation because it is a safety
   * lock: without it a reload could proceed on lists that do not cover the latest declaration.
   */
  unverifiableAllergy: boolean
  /**
   * A question that was asked and never answered — the request failed, or the tab was reloaded
   * mid-flight. `messages` holds answered turns only, so without this the question would vanish
   * on reload, and a failed "I am allergic to ibuprofen" would be gone before the next request.
   * The server's allergy scan reads the questions in the request (spec 005 FR-013), so a question
   * it never sees cannot guard anything. Kept here, it comes back in the composer and rides along
   * with the next send. Cleared the moment an answer arrives.
   */
  pendingQuestion: string
  /**
   * When the person last confirmed their allergy list, or `''` if they never have.
   *
   * <p>The cut-off for `unanswered_questions` (spec 005 FR-013). A question that failed never got
   * its clarification, so a declaration inside it never reached the picker — the server has to be
   * told, or it will trust a list that was built for an earlier declaration. But once the picker HAS
   * been confirmed, everything said before it was in front of the person, pre-filled, and they told
   * us what to avoid. Without a cut-off, one failed sentence would demand a clarification it has
   * already received, on every question, for the rest of the conversation.
   */
  allergiesConfirmedAt: string
}

const EMPTY_SESSION: ChatSession = {
  sessionId: '',
  messages: [],
  allergies: [],
  unverifiedAllergens: [],
  unverifiableAllergy: false,
  pendingQuestion: '',
  allergiesConfirmedAt: '',
}

export function loadChatSession(): ChatSession {
  // Sweep the pre-grounding blob rather than leaving it to expire with the tab: it holds a medical
  // conversation, and a key nobody reads is still a key someone can read.
  sessionStorage.removeItem(CHAT_SESSION_KEY_V1)
  return read(sessionStorage, CHAT_SESSION_KEY, EMPTY_SESSION)
}

export function saveChatSession(session: ChatSession): void {
  write(sessionStorage, CHAT_SESSION_KEY, session)
}

export function clearChatSession(): void {
  sessionStorage.removeItem(CHAT_SESSION_KEY)
}

// --- saved facilities: localStorage, user-owned -------------------------------

export interface SavedFacility {
  id: string
  facilityId: string
  /** Display-only. Never treat as current opening hours — refetch on open. */
  snapshot: {
    nameKo: string
    type: import('./types').FacilityType
    addressKo: string | null
    operation: import('./types').FacilityOperation
    source: import('./types').SourceRef
  }
  alias: string
  note: string
  createdAt: string
  updatedAt: string
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isNullableString(value: unknown): value is string | null {
  return value === null || typeof value === 'string'
}

function isSavedFacility(value: unknown): value is SavedFacility {
  if (!isRecord(value) || !isRecord(value.snapshot)) return false
  const { snapshot } = value
  const source = snapshot.source
  const operation = snapshot.operation
  return (
    typeof value.id === 'string' && typeof value.facilityId === 'string' &&
    typeof value.alias === 'string' && typeof value.note === 'string' &&
    typeof value.createdAt === 'string' && typeof value.updatedAt === 'string' &&
    typeof snapshot.nameKo === 'string' &&
    (snapshot.type === 'pharmacy' || snapshot.type === 'hospital' || snapshot.type === 'emergency_room') &&
    isNullableString(snapshot.addressKo) && isRecord(operation) &&
    (operation.isOpenNow === true || operation.isOpenNow === false || operation.isOpenNow === null) &&
    (operation.status === 'open' || operation.status === 'closed' || operation.status === 'unknown') &&
    (operation.statusConfidence === 'official_realtime' || operation.statusConfidence === 'official_schedule' || operation.statusConfidence === 'inferred' || operation.statusConfidence === 'unknown') &&
    isNullableString(operation.verifiedAt) &&
    (operation.scheduleUpdatedAt === undefined || isNullableString(operation.scheduleUpdatedAt)) &&
    typeof operation.notice === 'string' &&
    isRecord(source) && typeof source.id === 'string' && typeof source.provider === 'string' &&
    isNullableString(source.recordId) && typeof source.retrievedAt === 'string' &&
    (source.dataMode === 'live' || source.dataMode === 'fixture') && typeof source.title === 'string'
  )
}

function isSavedFacilities(value: unknown): value is SavedFacility[] {
  return Array.isArray(value) && value.every(isSavedFacility)
}

export function loadSavedFacilities(): SavedFacility[] {
  return read<SavedFacility[]>(localStorage, SAVED_FACILITIES_KEY, [], isSavedFacilities).map((facility) => ({
    ...facility,
    snapshot: {
      ...facility.snapshot,
      // Saved places predate this provider field. Keep the snapshot instead of treating that harmless
      // omission as corruption, and make its nullable contract explicit for the current UI.
      operation: { ...facility.snapshot.operation, scheduleUpdatedAt: facility.snapshot.operation.scheduleUpdatedAt ?? null },
    },
  }))
}

export function saveSavedFacilities(items: SavedFacility[]): void {
  write(localStorage, SAVED_FACILITIES_KEY, items)
}

// --- preferences: UI state and separately consented allergy memory -----------

export type AppearancePreference = 'light' | 'dark' | 'device'

/** Backward-compatible save input. Legacy allergy fields are accepted but never written here. */
export interface Preferences {
  defaultRadiusM: number
  manualLocation: ManualLocation | null
  appearance?: AppearancePreference
  rememberAllergies?: boolean
  allergies?: unknown[]
  unverifiedAllergens?: unknown[]
}

export interface ManualLocation {
  lat: number
  lng: number
  label: string
}

interface StoredPreferences {
  defaultRadiusM: number
  manualLocation: ManualLocation | null
  appearance: AppearancePreference
}

const DEFAULT_PREFERENCES: StoredPreferences = {
  defaultRadiusM: 1000,
  manualLocation: null,
  appearance: 'device',
}

function isAppearancePreference(value: unknown): value is AppearancePreference {
  return value === 'light' || value === 'dark' || value === 'device'
}

function isManualLocation(value: unknown): value is ManualLocation {
  return (
    isRecord(value) &&
    typeof value.lat === 'number' &&
    typeof value.lng === 'number' &&
    typeof value.label === 'string'
  )
}

function normalizePreferences(value: Record<string, unknown> | Preferences): StoredPreferences {
  return {
    defaultRadiusM:
      typeof value.defaultRadiusM === 'number'
        ? value.defaultRadiusM
        : DEFAULT_PREFERENCES.defaultRadiusM,
    manualLocation: isManualLocation(value.manualLocation) ? value.manualLocation : null,
    appearance: isAppearancePreference(value.appearance)
      ? value.appearance
      : DEFAULT_PREFERENCES.appearance,
  }
}

function readAllergyMemoryKey(): string[] | null {
  return read<string[] | null>(
    localStorage,
    ALLERGY_MEMORY_KEY,
    null,
    (value): value is string[] =>
      Array.isArray(value) && value.every((item) => typeof item === 'string'),
  )
}

/**
 * Moves the old mixed preferences shape once, then scrubs every allergy field from that key.
 *
 * The old opt-in is honoured only for reviewed string ingredient keys. Typed, unreviewed names
 * stay session-only under the replacement contract for 009; they are deliberately not migrated.
 */
function migrateLegacyPreferences(): StoredPreferences {
  if (localStorage.getItem(PREFERENCES_KEY) === null) return DEFAULT_PREFERENCES

  const stored = read<Record<string, unknown> | null>(
    localStorage,
    PREFERENCES_KEY,
    null,
    (value): value is Record<string, unknown> | null => value === null || isRecord(value),
  )
  if (!stored) return DEFAULT_PREFERENCES

  const preferences = normalizePreferences(stored)
  const hasLegacyAllergyFields =
    Object.hasOwn(stored, 'rememberAllergies') ||
    Object.hasOwn(stored, 'allergies') ||
    Object.hasOwn(stored, 'unverifiedAllergens')

  if (!hasLegacyAllergyFields) return preferences

  const existing = readAllergyMemoryKey()
  if (stored.rememberAllergies === true) {
    if (existing === null) {
      const reviewedKeys =
        Array.isArray(stored.allergies) &&
        stored.allergies.every((item): item is string => typeof item === 'string')
          ? stored.allergies
          : []
      write(localStorage, ALLERGY_MEMORY_KEY, reviewedKeys)
    }
  }

  write(localStorage, PREFERENCES_KEY, preferences)
  return preferences
}

const appearanceListeners = new Set<() => void>()
const allergyMemoryListeners = new Set<() => void>()

function notifyAppearanceListeners(): void {
  appearanceListeners.forEach((listener) => listener())
}

function notifyAllergyMemoryListeners(): void {
  allergyMemoryListeners.forEach((listener) => listener())
}

export function loadPreferences(): StoredPreferences {
  return migrateLegacyPreferences()
}

export function savePreferences(prefs: Preferences): void {
  // Migrate before overwriting so an appearance/location update cannot erase an old consented list.
  migrateLegacyPreferences()
  write(localStorage, PREFERENCES_KEY, normalizePreferences(prefs))
  notifyAppearanceListeners()
}

/** `null` is the consent state OFF; an empty array is ON with no reviewed ingredients yet. */
export function loadAllergyMemory(): string[] | null {
  migrateLegacyPreferences()
  return readAllergyMemoryKey()
}

export function saveAllergyMemory(allergies: string[]): void {
  migrateLegacyPreferences()
  write(localStorage, ALLERGY_MEMORY_KEY, [...allergies])
  notifyAllergyMemoryListeners()
}

/** Keeps an existing opt-in copy current without turning an absent (OFF) key back on. */
export function syncAllergyMemory(allergies: string[]): void {
  if (loadAllergyMemory() === null) return
  write(localStorage, ALLERGY_MEMORY_KEY, [...allergies])
  notifyAllergyMemoryListeners()
}

export function forgetAllergyMemory(): void {
  localStorage.removeItem(ALLERGY_MEMORY_KEY)
  notifyAllergyMemoryListeners()
}

export function getAllergyMemoryEnabled(): boolean {
  return loadAllergyMemory() !== null
}

/**
 * Stable primitive snapshot for React external-store consumers.
 *
 * A boolean snapshot misses an ON -> ON content change made by another tab. Returning the reviewed
 * keys as JSON lets an open tab merge that stricter allergy state into its session as well.
 */
export function getAllergyMemorySnapshot(): string | null {
  const allergies = loadAllergyMemory()
  return allergies === null ? null : JSON.stringify(allergies)
}

export function subscribeAllergyMemory(listener: () => void): () => void {
  allergyMemoryListeners.add(listener)
  const onStorage = (event: StorageEvent) => {
    if (
      event.storageArea === localStorage &&
      (event.key === ALLERGY_MEMORY_KEY || event.key === null)
    ) {
      listener()
    }
  }
  window.addEventListener('storage', onStorage)

  return () => {
    allergyMemoryListeners.delete(listener)
    window.removeEventListener('storage', onStorage)
  }
}

export function getAppearancePreference(): AppearancePreference {
  return loadPreferences().appearance
}

export function applyAppearancePreference(appearance: AppearancePreference): void {
  if (typeof document === 'undefined') return
  document.documentElement.style.colorScheme =
    appearance === 'device' ? 'light dark' : appearance
}

export function setAppearancePreference(appearance: AppearancePreference): void {
  savePreferences({ ...loadPreferences(), appearance })
  applyAppearancePreference(appearance)
}

export function subscribeAppearancePreference(listener: () => void): () => void {
  appearanceListeners.add(listener)
  const onStorage = (event: StorageEvent) => {
    if (event.storageArea === localStorage && (event.key === PREFERENCES_KEY || event.key === null)) {
      listener()
    }
  }
  window.addEventListener('storage', onStorage)

  return () => {
    appearanceListeners.delete(listener)
    window.removeEventListener('storage', onStorage)
  }
}

export function setManualLocation(manualLocation: ManualLocation | null): void {
  savePreferences({ ...loadPreferences(), manualLocation })
}
