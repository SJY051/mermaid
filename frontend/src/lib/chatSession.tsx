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
  send: (text: string) => Promise<void>
  confirmAllergies: (keys: string[]) => void
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
  const [turns, setTurns] = useState<ChatTurn[]>(restored.turns)
  const [allergies, setAllergies] = useState(initialAllergies)
  const [streaming, setStreaming] = useState(false)
  const [elapsedS, setElapsedS] = useState(0)
  const turnsRef = useRef(restored.turns)
  const sessionRef = useRef<ChatSession>({
    ...initialSession,
    messages: restored.messages,
    allergies: initialAllergies,
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
      const nextTurns = previousTurns.at(-1)?.error
        ? [...previousTurns.slice(0, -1), pendingTurn]
        : [...previousTurns, pendingTurn]

      turnsRef.current = nextTurns
      setTurns(nextTurns)
      setStreaming(true)

      // Every user turn of this conversation rides along, newest last. The server's allergy scan
      // runs over ALL user messages in the request (spec 005 FR-013): the bare reply to the
      // allergy clarifying question ("ibuprofen") carries no allergy keyword, so a request that
      // carried only the newest turn would let that turn retrieve unguarded — and show the person
      // the very ingredient they just declared. Turns that ended in a transport error are included
      // on purpose: the person still said it, and over-including only errs toward the safe side.
      const messages: OpenAI.ChatCompletionMessageParam[] = [
        ...previousTurns.map(
          (turn): OpenAI.ChatCompletionMessageParam => ({ role: 'user', content: turn.question }),
        ),
        { role: 'user', content: text },
      ]

      try {
        let latest = ''
        const requestExtension = sessionRef.current.allergies.length
          ? {
              mermaid: {
                exclude_ingredients: [...sessionRef.current.allergies],
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
    (keys: string[]) => {
      const confirmed = [...keys]
      sessionRef.current = { ...sessionRef.current, allergies: confirmed }
      setAllergies(confirmed)
      persistSession()
    },
    [persistSession],
  )

  const newConversation = useCallback(() => {
    conversationRef.current += 1
    turnsRef.current = []
    sessionRef.current = { sessionId: '', messages: [], allergies: [] }
    setTurns([])
    setAllergies([])
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
        send,
        confirmAllergies,
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
