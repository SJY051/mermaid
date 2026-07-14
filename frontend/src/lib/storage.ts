/**
 * Browser storage, split by sensitivity (spec §2-16, §4).
 *
 * The original plan said LocalStorage made the chat "secure by design". It does not:
 * LocalStorage survives the tab, is readable by anyone with the device, and is exposed
 * to XSS. Medical consultation transcripts therefore live in `sessionStorage` and die
 * with the tab. Only the saved-places list — which is not sensitive — persists.
 *
 * Every stored value carries a `schemaVersion`. A corrupted or future-version blob must
 * never crash the app; we reset that one key and carry on.
 */

const SCHEMA_VERSION = '1.0'

const DEVICE_ID_KEY = 'mermaid.deviceId.v1'
const CHAT_SESSION_KEY = 'mermaid.chatSession.v1'
const SAVED_FACILITIES_KEY = 'mermaid.savedFacilities.v1'
const PREFERENCES_KEY = 'mermaid.preferences.v1'

interface Versioned<T> {
  schemaVersion: string
  data: T
}

function read<T>(store: Storage, key: string, fallback: T): T {
  const raw = store.getItem(key)
  if (!raw) return fallback
  try {
    const parsed = JSON.parse(raw) as Versioned<T>
    if (parsed.schemaVersion !== SCHEMA_VERSION) {
      // An older or newer shape. Do not guess at a migration we have not written.
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
  snapshot: { nameKo: string; type: string; addressKo: string | null }
  alias: string
  note: string
  createdAt: string
  updatedAt: string
}

export function loadSavedFacilities(): SavedFacility[] {
  return read<SavedFacility[]>(localStorage, SAVED_FACILITIES_KEY, [])
}

export function saveSavedFacilities(items: SavedFacility[]): void {
  write(localStorage, SAVED_FACILITIES_KEY, items)
}

// --- preferences: allergy memory is opt-in, default OFF (spec §2-5) -----------

export interface Preferences {
  rememberAllergies: boolean
  allergies: string[]
  defaultRadiusM: number
}

const DEFAULT_PREFERENCES: Preferences = {
  rememberAllergies: false,
  allergies: [],
  defaultRadiusM: 1000,
}

export function loadPreferences(): Preferences {
  const prefs = read(localStorage, PREFERENCES_KEY, DEFAULT_PREFERENCES)
  // Invariant: allergies are only kept when the user said so.
  return prefs.rememberAllergies ? prefs : { ...prefs, allergies: [] }
}

export function savePreferences(prefs: Preferences): void {
  write(localStorage, PREFERENCES_KEY, prefs.rememberAllergies ? prefs : { ...prefs, allergies: [] })
}
