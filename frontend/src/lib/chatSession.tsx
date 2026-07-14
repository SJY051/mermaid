import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react'
import type OpenAI from 'openai'
import { describeSendFailure, parseAnswer, streamChat, type SendFailure } from './openaiClient'
import {
  clearChatSession,
  getDeviceId,
  loadChatSession,
  saveChatSession,
  type ChatSession,
  type StoredMessage,
} from './storage'
import type { MermAidAnswer } from './types'

export interface ChatTurn {
  id: string
  question: string
  answer?: MermAidAnswer
  raw?: string
  error?: SendFailure & { forInput: string }
}

interface ChatSessionContextValue {
  turns: ChatTurn[]
  streaming: boolean
  elapsedS: number
  sendError: (SendFailure & { forInput: string }) | null
  emergencyActive: boolean
  latestAnswer: MermAidAnswer | null
  allergies: string[]
  unverifiedAllergens: string[]
  /** Drug lookup is ended for this conversation: an allergy we cannot bind was declared. */
  unverifiableAllergy: boolean
  send: (text: string) => Promise<void>
  confirmAllergies: (keys: string[], unverified: string[]) => void
  declareUnverifiableAllergy: () => void
  newConversation: () => void
}

const ChatSessionContext = createContext<ChatSessionContextValue | null>(null)

function restoreSession(session: ChatSession): { turns: ChatTurn[]; messages: StoredMessage[] } {
  const turns: ChatTurn[] = []
  const messages: StoredMessage[] = []

  // storage.ts validates only the version wrapper, so a same-schema blob can still be malformed
  // (messages: null, or null entries). The shape check is ours: debris resets to an empty
  // conversation — it must never leave the whole app blank.
  if (!Array.isArray(session.messages)) return { turns, messages }

  for (let i = 0; i < session.messages.length; i += 1) {
    const user = session.messages[i]
    const assistant = session.messages[i + 1]
    if (user?.role !== 'user' || assistant?.role !== 'assistant') continue
    if (typeof user.content !== 'string' || typeof assistant.content !== 'string') continue

    try {
      // Stored assistant messages are validated answer JSON. Refuse to turn a corrupt blob into
      // fallback medical-looking prose before parseAnswer applies its render guarantees.
      JSON.parse(assistant.content)
      turns.push({
        id: user.id,
        question: user.content,
        answer: parseAnswer(assistant.content),
        raw: assistant.content,
      })
      messages.push(user, assistant)
    } catch {
      // A damaged turn is local storage debris, not a reason to make the whole app unusable.
    }
    i += 1
  }

  return { turns, messages }
}

export function ChatProvider({ children }: { children: React.ReactNode }) {
  const [initialSession] = useState(loadChatSession)
  const [restored] = useState(() => restoreSession(initialSession))
  const [initialAllergies] = useState(() =>
    Array.isArray(initialSession.allergies)
      ? initialSession.allergies.filter((allergy): allergy is string => typeof allergy === 'string')
      : [],
  )
  const [initialUnverifiable] = useState(() => initialSession.unverifiableAllergy === true)
  const [initialUnverifiedAllergens] = useState(() =>
    Array.isArray(initialSession.unverifiedAllergens)
      ? initialSession.unverifiedAllergens.filter(
          (allergen): allergen is string => typeof allergen === 'string',
        )
      : [],
  )
  const [turns, setTurns] = useState<ChatTurn[]>(restored.turns)
  const [allergies, setAllergies] = useState(initialAllergies)
  const [unverifiedAllergens, setUnverifiedAllergens] = useState(initialUnverifiedAllergens)
  const [unverifiableAllergy, setUnverifiableAllergyState] = useState(initialUnverifiable)
  const [streaming, setStreaming] = useState(false)
  const [elapsedS, setElapsedS] = useState(0)
  const turnsRef = useRef(restored.turns)
  const sessionRef = useRef<ChatSession>({
    ...initialSession,
    messages: restored.messages,
    allergies: initialAllergies,
    unverifiedAllergens: initialUnverifiedAllergens,
    unverifiableAllergy: initialUnverifiable,
  })
  const conversationRef = useRef(0)

  // Reserved so the profile endpoints have an identity to attach to (FR-04).
  void getDeviceId()

  // A cold answer exceeds 100 seconds, and a screen that shows nothing moving for that long
  // reads as broken — the user leaves and gets no care information (P1, Review guidelines).
  // We cannot show real server progress (the proxy answers with a single validated chunk,
  // spec §5-4), so the honest display is the one thing we actually know: elapsed time.
  useEffect(() => {
    if (!streaming) return
    setElapsedS(0)
    const timer = window.setInterval(() => setElapsedS((s) => s + 1), 1000)
    return () => window.clearInterval(timer)
  }, [streaming])

  const persistSession = useCallback(() => {
    if (!sessionRef.current.sessionId) {
      sessionRef.current = { ...sessionRef.current, sessionId: crypto.randomUUID() }
    }
    saveChatSession(sessionRef.current)
  }, [])

  const send = useCallback(
    async (text: string) => {
      if (!text.trim() || streaming) return

      const conversation = conversationRef.current
      const turnId = crypto.randomUUID()
      const pendingTurn: ChatTurn = { id: turnId, question: text }
      const previousTurns = turnsRef.current
      const lastTurn = previousTurns.at(-1)
      // A retry resends the failed question verbatim, so it REPLACES the failed turn — sending it
      // again would duplicate that question. Any OTHER send after a failure (a different question
      // typed into the composer once it clears) KEEPS the failed turn: the person still asked it,
      // and its text must stay in the record and the transmitted history. Dropping it would let a
      // failed "I am allergic to ibuprofen" vanish before the next turn, and the server's allergy
      // scan over the request would then never see it (FR-013) — unguarded retrieval.
      const retryingLast = lastTurn?.error != null && lastTurn.question === text
      const nextTurns = retryingLast
        ? [...previousTurns.slice(0, -1), pendingTurn]
        : [...previousTurns, pendingTurn]

      turnsRef.current = nextTurns
      setTurns(nextTurns)
      setStreaming(true)

      // The transmitted history IS the conversation record: every kept turn's question, newest
      // last. The server's allergy scan runs over ALL user messages in the request (spec 005
      // FR-013) — a request carrying only the newest turn would let the bare reply to the
      // clarifying question ("ibuprofen") retrieve unguarded. Because nextTurns already decided
      // what this send keeps (a retry replaced the failed turn; any other send kept it), mapping
      // over it sends each question exactly once — no duplicate on retry, no lost context on a
      // drafted follow-up.
      const messages: OpenAI.ChatCompletionMessageParam[] = nextTurns.map(
        (turn): OpenAI.ChatCompletionMessageParam => ({ role: 'user', content: turn.question }),
      )

      try {
        let latest = ''
        const requestExtension =
          sessionRef.current.allergies.length || sessionRef.current.unverifiedAllergens.length
            ? {
                mermaid: {
                  ...(sessionRef.current.allergies.length
                    ? { exclude_ingredients: [...sessionRef.current.allergies] }
                    : {}),
                  ...(sessionRef.current.unverifiedAllergens.length
                    ? { unverified_allergens: [...sessionRef.current.unverifiedAllergens] }
                    : {}),
                },
              }
            : undefined
        const response = requestExtension
          ? streamChat(messages, undefined, requestExtension)
          : streamChat(messages)
        for await (const partial of response) {
          latest = partial
        }
        // Only parse once the stream has finished. A truncated JSON object must never
        // reach a medication card — see spec §5-4 and openaiClient.streamChat.
        const answer = parseAnswer(latest)
        if (conversation !== conversationRef.current) return

        const completedTurns = turnsRef.current.map((turn) =>
          turn.id === turnId ? { ...turn, answer, raw: latest } : turn,
        )
        turnsRef.current = completedTurns
        setTurns(completedTurns)

        const createdAt = new Date().toISOString()
        sessionRef.current = {
          ...sessionRef.current,
          messages: [
            ...sessionRef.current.messages,
            { id: turnId, role: 'user', content: text, createdAt },
            { id: `${turnId}:assistant`, role: 'assistant', content: latest, createdAt },
          ],
        }
        persistSession()
      } catch (e) {
        if (conversation !== conversationRef.current) return

        // A failure is a failure — not an assistant answer. Dressing it up as one (the old
        // behaviour) made errors look like medical responses with an "unavailable" badge.
        const error = { ...describeSendFailure(e), forInput: text }
        const failedTurns = turnsRef.current.map((turn) =>
          turn.id === turnId ? { ...turn, error } : turn,
        )
        turnsRef.current = failedTurns
        setTurns(failedTurns)
        // Errors are not conversation content, but completing the attempt still normalizes and
        // saves any valid answered turns already in the tab session.
        persistSession()
      } finally {
        if (conversation === conversationRef.current) setStreaming(false)
      }
    },
    [persistSession, streaming],
  )

  const confirmAllergies = useCallback(
    (keys: string[], unverified: string[]) => {
      const confirmed = [...keys]
      const unverifiedNames = [...unverified]
      sessionRef.current = {
        ...sessionRef.current,
        allergies: confirmed,
        unverifiedAllergens: unverifiedNames,
      }
      setAllergies(confirmed)
      setUnverifiedAllergens(unverifiedNames)
      persistSession()
    },
    [persistSession],
  )

  // Ends drug lookup for the conversation and PERSISTS that decision, so a reload cannot silently
  // lift the lock while the conversation (and its incomplete allergy list) is restored.
  const declareUnverifiableAllergy = useCallback(() => {
    sessionRef.current = { ...sessionRef.current, unverifiableAllergy: true }
    setUnverifiableAllergyState(true)
    persistSession()
  }, [persistSession])

  const newConversation = useCallback(() => {
    conversationRef.current += 1
    turnsRef.current = []
    sessionRef.current = {
      sessionId: '',
      messages: [],
      allergies: [],
      unverifiedAllergens: [],
      unverifiableAllergy: false,
    }
    setTurns([])
    setAllergies([])
    setUnverifiedAllergens([])
    setUnverifiableAllergyState(false)
    setStreaming(false)
    clearChatSession()
  }, [])

  const sendError = turns.at(-1)?.error ?? null
  const latestAnswer = [...turns].reverse().find((turn) => turn.answer)?.answer ?? null
  const emergencyActive = latestAnswer?.urgency.level === 'emergency'

  return (
    <ChatSessionContext.Provider
      value={{
        turns,
        streaming,
        elapsedS,
        sendError,
        emergencyActive,
        latestAnswer,
        allergies,
        unverifiedAllergens,
        unverifiableAllergy,
        send,
        confirmAllergies,
        declareUnverifiableAllergy,
        newConversation,
      }}
    >
      {children}
    </ChatSessionContext.Provider>
  )
}

export function useChatSession(): ChatSessionContextValue {
  const context = useContext(ChatSessionContext)
  if (!context) throw new Error('useChatSession must be used inside ChatProvider')
  return context
}
