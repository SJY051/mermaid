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
  createdAt: string
  answer?: MermAidAnswer
  raw?: string
  /** A failure that happened in THIS tab: we know what went wrong and whether resending can help. */
  error?: SendFailure & { forInput: string }
  /**
   * Restored from storage with no answer behind it. We know the question was asked and never
   * answered — and nothing else. Not why, and not whether asking again would help: the failure
   * happened in a tab that is gone. So this says exactly that and no more. Fabricating a
   * `retryable` verdict here would put a Try again button on a question the backend had already
   * called hopeless, or withhold one from a question that would have worked.
   */
  unanswered?: boolean
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
  /** The question asked but not yet answered — restored into the composer after a reload. */
  pendingQuestion: string
  /** Resolves true when an answer arrived. The composer clears on true, and only on true. */
  send: (text: string) => Promise<boolean>
  /**
   * `answersClarification` — is this confirmation the reply to a clarification the SERVER asked
   * for? Only then may it cut off the questions that went unanswered before it. See below.
   */
  confirmAllergies: (
    keys: string[],
    unverified: string[],
    answersClarification: boolean,
  ) => void
  declareUnverifiableAllergy: () => void
  newConversation: () => void
}

const ChatSessionContext = createContext<ChatSessionContextValue | null>(null)

function restoreSession(session: ChatSession): ChatTurn[] {
  const turns: ChatTurn[] = []

  // storage.ts validates only the version wrapper, so a same-schema blob can still be malformed
  // (messages: null, or null entries). The shape check is ours: debris resets to an empty
  // conversation — it must never leave the whole app blank.
  if (!Array.isArray(session.messages)) return turns

  for (let i = 0; i < session.messages.length; i += 1) {
    const user = session.messages[i]
    if (user?.role !== 'user' || typeof user.content !== 'string') continue

    const assistant = session.messages[i + 1]
    const answered = assistant?.role === 'assistant' && typeof assistant.content === 'string'
    if (!answered) {
      // A user message with no answer behind it: the request failed, or the tab was reloaded while
      // it was still in flight. Kept, and honestly labelled.
      // The question was asked and never answered. It stays in the conversation — and so in every
      // later request, which is what the server's allergy scan reads (spec 005 FR-013). A question
      // dropped from the record is dropped from the scan, and that is how a failed "I am allergic
      // to ibuprofen" ends up guarding nothing.
      turns.push({
        id: user.id,
        question: user.content,
        createdAt: user.createdAt,
        unanswered: true,
      })
      continue
    }

    try {
      // Stored assistant messages are validated answer JSON. Refuse to turn a corrupt blob into
      // fallback medical-looking prose before parseAnswer applies its render guarantees.
      JSON.parse(assistant.content)
      turns.push({
        id: user.id,
        question: user.content,
        createdAt: user.createdAt,
        answer: parseAnswer(assistant.content),
        raw: assistant.content,
      })
    } catch {
      // A damaged answer is storage debris. The question still happened, so it stays — dropping it
      // would take it out of the next request's history too.
      turns.push({
        id: user.id,
        question: user.content,
        createdAt: user.createdAt,
        unanswered: true,
      })
    }
    i += 1
  }

  return turns
}

/**
 * The stored transcript IS the turn list — every question, and the answer to the ones that got one.
 *
 * <p>Deriving it rather than appending to it is the point. An append-on-success record holds only
 * answered turns, so a question that failed and was then edited into a different one vanished on
 * reload — the memory was right and the record was wrong, and the record is what the next request
 * is built from. One list cannot disagree with itself.
 */
function messagesOf(turns: ChatTurn[]): StoredMessage[] {
  const messages: StoredMessage[] = []
  for (const turn of turns) {
    messages.push({
      id: turn.id,
      role: 'user',
      content: turn.question,
      createdAt: turn.createdAt,
    })
    if (turn.raw) {
      messages.push({
        id: `${turn.id}:assistant`,
        role: 'assistant',
        content: turn.raw,
        createdAt: turn.createdAt,
      })
    }
  }
  return messages
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
  const [initialPending] = useState(() =>
    typeof initialSession.pendingQuestion === 'string' ? initialSession.pendingQuestion : '',
  )
  const [turns, setTurns] = useState<ChatTurn[]>(restored)
  const [allergies, setAllergies] = useState(initialAllergies)
  const [unverifiedAllergens, setUnverifiedAllergens] = useState(initialUnverifiedAllergens)
  const [unverifiableAllergy, setUnverifiableAllergyState] = useState(initialUnverifiable)
  const [pendingQuestion, setPendingQuestion] = useState(initialPending)
  const [streaming, setStreaming] = useState(false)
  const [elapsedS, setElapsedS] = useState(0)
  const turnsRef = useRef(restored)
  const sessionRef = useRef<ChatSession>({
    ...initialSession,
    messages: messagesOf(restored),
    allergies: initialAllergies,
    unverifiedAllergens: initialUnverifiedAllergens,
    unverifiableAllergy: initialUnverifiable,
    pendingQuestion: initialPending,
    allergiesConfirmedAt:
      typeof initialSession.allergiesConfirmedAt === 'string'
        ? initialSession.allergiesConfirmedAt
        : '',
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
    // The record is the turn list, always — never an append of whatever just happened. A turn that
    // failed and was kept (the user edited their question rather than retrying it) is a question
    // the person asked, and it must ride in every later request (FR-013). Appending only answered
    // turns is what dropped it: memory kept it, storage did not, and storage is what a reload reads.
    sessionRef.current = { ...sessionRef.current, messages: messagesOf(turnsRef.current) }
    saveChatSession(sessionRef.current)
  }, [])

  const send = useCallback(
    async (text: string): Promise<boolean> => {
      if (!text.trim() || streaming) return false

      const conversation = conversationRef.current
      const turnId = crypto.randomUUID()
      const pendingTurn: ChatTurn = {
        id: turnId,
        question: text,
        createdAt: new Date().toISOString(),
      }
      const previousTurns = turnsRef.current
      const lastTurn = previousTurns.at(-1)
      const lastError = lastTurn?.error
      // A retry resends the failed question verbatim, so it REPLACES that turn — sending it again
      // would put the same question in the request twice. Any OTHER send after a failure KEEPS the
      // failed turn: the person asked it, and its text must stay in the record and in the request.
      // Dropping it would let a failed "I am allergic to ibuprofen" vanish before the next turn,
      // and the server's scan over the request's questions would never see it (FR-013).
      //
      // No exception for a non-retryable failure, and there was one here for an hour. The argument
      // for dropping it was recovery: a request the server refuses for its size would be refused
      // again while its text rode along in the history, so "shorten your question" could not work.
      // But dropping it drops whatever it said — and what it says may be "I am allergic to
      // ibuprofen", which the server reads from the request's questions (FR-013) and which nothing
      // else in this tab is holding, because the turn that would have offered the picker is the one
      // that failed. A recovery path that silently discards a declared allergy is worse than the
      // dead end it fixes, and INPUT_TOO_LARGE is not even thrown today — the backend declares the
      // code and never uses it. The escape from a poisoned conversation is a new conversation, and
      // the person is told so.
      const retryingLast = lastError != null && lastTurn?.question === text
      const nextTurns = retryingLast
        ? [...previousTurns.slice(0, -1), pendingTurn]
        : [...previousTurns, pendingTurn]

      turnsRef.current = nextTurns
      setTurns(nextTurns)
      setStreaming(true)

      // Asked, not yet answered. If the tab reloads before the answer lands, this is what brings
      // the question back into the composer — see ChatSession.pendingQuestion.
      sessionRef.current = { ...sessionRef.current, pendingQuestion: text }
      setPendingQuestion(text)
      persistSession()

      // The transmitted history IS the conversation record: every kept turn's question, newest
      // last. The server's allergy scan runs over ALL user messages in the request (spec 005
      // FR-013) — a request carrying only the newest turn would let the bare reply to the
      // clarifying question ("ibuprofen") retrieve unguarded. Mapping over nextTurns sends each
      // kept question exactly once: the replaced failed turn is gone, so no duplicate on retry.
      const messages: OpenAI.ChatCompletionMessageParam[] = nextTurns.map(
        (turn): OpenAI.ChatCompletionMessageParam => ({ role: 'user', content: turn.question }),
      )

      // Questions that were asked and never answered — and that no allergy confirmation has been
      // through since. The clarification is what turns a declaration into a structured list, so a
      // declaration whose turn FAILED never reached the picker: "I am also allergic to aspirin",
      // lost to a network error, would ride in the history while the request carried the list built
      // for an earlier declaration, and the server — seeing a complete, resolved list — would
      // retrieve. We cannot tell which sentences declare an allergy (that judgement is the server's,
      // and it is the whole point of the redesign); we can only report the fact that these ones were
      // never answered. The confirmation is the cut-off: once the person has confirmed their list,
      // the picker HAS seen everything they said before it, and reporting those turns forever would
      // put the clarification in front of them on every question for the rest of the conversation.
      const confirmedAt = sessionRef.current.allergiesConfirmedAt ?? ''
      const unansweredQuestions = nextTurns
        .filter((turn) => turn.id !== turnId)
        .filter((turn) => (turn.error != null || turn.unanswered === true) && !turn.answer)
        .filter((turn) => turn.createdAt > confirmedAt)
        .map((turn) => turn.question)

      try {
        let latest = ''
        const mermaidFields = {
          ...(sessionRef.current.allergies.length
            ? { exclude_ingredients: [...sessionRef.current.allergies] }
            : {}),
          ...(sessionRef.current.unverifiedAllergens.length
            ? { unverified_allergens: [...sessionRef.current.unverifiedAllergens] }
            : {}),
          ...(unansweredQuestions.length ? { unanswered_questions: unansweredQuestions } : {}),
        }
        const requestExtension =
          Object.keys(mermaidFields).length > 0 ? { mermaid: mermaidFields } : undefined
        const response = requestExtension
          ? streamChat(messages, undefined, requestExtension)
          : streamChat(messages)
        for await (const partial of response) {
          latest = partial
        }
        // Only parse once the stream has finished. A truncated JSON object must never
        // reach a medication card — see spec §5-4 and openaiClient.streamChat.
        const answer = parseAnswer(latest)
        if (conversation !== conversationRef.current) return false

        const completedTurns = turnsRef.current.map((turn) =>
          turn.id === turnId ? { ...turn, answer, raw: latest } : turn,
        )
        turnsRef.current = completedTurns
        setTurns(completedTurns)

        sessionRef.current = {
          ...sessionRef.current,
          // Answered: the question now lives in the transcript, so it is no longer pending.
          pendingQuestion: '',
        }
        setPendingQuestion('')
        persistSession()
        return true
      } catch (e) {
        if (conversation !== conversationRef.current) return false

        // A failure is a failure — not an assistant answer. Dressing it up as one (the old
        // behaviour) made errors look like medical responses with an "unavailable" badge.
        const error = { ...describeSendFailure(e), forInput: text }
        const failedTurns = turnsRef.current.map((turn) =>
          turn.id === turnId ? { ...turn, error } : turn,
        )
        turnsRef.current = failedTurns
        setTurns(failedTurns)
        // The question stays pending: unanswered, still in the composer, and still persisted, so a
        // reload brings it back rather than dropping it out of the next request's history.
        persistSession()
        return false
      } finally {
        if (conversation === conversationRef.current) setStreaming(false)
      }
    },
    [persistSession, streaming],
  )

  const confirmAllergies = useCallback(
    (keys: string[], unverified: string[], answersClarification: boolean) => {
      const confirmed = [...keys]
      const unverifiedNames = [...unverified]
      sessionRef.current = {
        ...sessionRef.current,
        allergies: confirmed,
        unverifiedAllergens: unverifiedNames,
        // The cut-off for `unanswered_questions`, and it is earned only by answering the
        // clarification the SERVER asked for. That clarification exists BECAUSE of those unanswered
        // questions: it puts the picker in front of the person and asks them to state their
        // allergies, so what they confirm supersedes what came before it.
        //
        // "Edit allergy list" is not that. Nobody asked; the person opened a menu to change one
        // entry, and their failed "I am also allergic to aspirin" is not what they are looking at.
        // Stamping there would drop that declaration from the next request while still sending a
        // list that looks complete — and the server, seeing a resolved list, would retrieve. The
        // seam this whole mechanism exists to close would reopen through the menu.
        //
        // So a manual edit leaves the cut-off where it was. The declaration stays reported, the
        // server stays fail-closed, and the clarification comes back — which is exactly what should
        // happen while an allergy the person stated is still unresolved.
        ...(answersClarification ? { allergiesConfirmedAt: new Date().toISOString() } : {}),
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
      pendingQuestion: '',
      allergiesConfirmedAt: '',
    }
    setTurns([])
    setAllergies([])
    setUnverifiedAllergens([])
    setUnverifiableAllergyState(false)
    setPendingQuestion('')
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
        pendingQuestion,
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
