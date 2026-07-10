const DEVICE_ID_KEY = 'mermaid.deviceId'
const CHAT_HISTORY_KEY = 'mermaid.chatHistory'

/**
 * The anonymous id that lets "no login" (FR-01) and "the profile's avoided ingredients"
 * (FR-04) coexist. Generated once, in the browser, never derived from anything personal.
 * See spec §2-5.
 */
export function getDeviceId(): string {
  let id = localStorage.getItem(DEVICE_ID_KEY)
  if (!id) {
    id = crypto.randomUUID()
    localStorage.setItem(DEVICE_ID_KEY, id)
  }
  return id
}

/**
 * Chat history lives here and only here.
 *
 * The original spec's reasoning holds: a medical consultation transcript sitting on a
 * server is a liability we can simply refuse to take on. Do not add an endpoint that
 * uploads this. See spec §2-4 and the V1__init.sql header.
 */
export function loadChatHistory<T>(): T[] {
  const raw = localStorage.getItem(CHAT_HISTORY_KEY)
  if (!raw) return []
  try {
    return JSON.parse(raw) as T[]
  } catch {
    return []
  }
}

export function saveChatHistory<T>(messages: T[]): void {
  localStorage.setItem(CHAT_HISTORY_KEY, JSON.stringify(messages))
}

export function clearChatHistory(): void {
  localStorage.removeItem(CHAT_HISTORY_KEY)
}
