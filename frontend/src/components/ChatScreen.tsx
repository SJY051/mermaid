import { useRef, useState } from 'react'
import { Banner } from '@astryxdesign/core/Banner'
import { Button } from '@astryxdesign/core/Button'
import { Card } from '@astryxdesign/core/Card'
import {
  ChatComposer,
  ChatLayout,
  ChatMessage,
  ChatMessageBubble,
  ChatMessageList,
  ChatSystemMessage,
} from '@astryxdesign/core/Chat'
import { ProgressBar } from '@astryxdesign/core/ProgressBar'
import { TextArea } from '@astryxdesign/core/TextArea'
import { useChatSession, type ChatTurn } from '../lib/chatSession'
import type { MermAidAnswer } from '../lib/types'
import { AllergyBadge } from './AllergyBadge'
import { AllergenPicker } from './AllergenPicker'
import { NearbyFacilities } from './NearbyFacilities'

const SESSION_COPY =
  "Your messages are sent to answer them, but this conversation is not saved: it lives only in this tab."

function PendingAnswer({ elapsedS }: { elapsedS: number }) {
  return (
    // aria-live so a screen reader hears that something is happening — a silent wait
    // hides the safety information that an answer is on its way (P1, Review guidelines).
    <section data-testid="chat-progress" aria-live="polite" className="flex flex-col gap-2">
      <ProgressBar isIndeterminate variant="accent" label="Waiting for the answer" />
      <p className="text-sm text-primary">
        Checking your symptoms against verified government drug data and writing an answer.
      </p>
      <p className="text-sm text-secondary">
        {/* tabular-nums: the counter must not jitter as digits change. */}
        <span className="tabular-nums">{elapsedS}s</span> — a first answer can take a minute or
        two. Nothing is stuck.
        {elapsedS >= 90 && (
          <>
            {' '}
            Still working: the AI service is slow right now, but your question was received and
            this screen will update by itself.
          </>
        )}
      </p>
    </section>
  )
}

function FailedAnswer({ turn, onRetry }: { turn: ChatTurn; onRetry: () => void }) {
  const sendError = turn.error
  if (!sendError) return null

  return (
    <section data-testid="chat-error" className="flex flex-col gap-3">
      <Banner
        status="error"
        title="We could not get an answer."
        description={
          (sendError.retryable
            ? 'Your question was not lost — it is still in the box above. '
            : 'Sending the same question again will not fix this one. ' +
              'Edit your question to ask something different, or come back later. ') +
          `Technical detail: ${sendError.message}`
        }
      />
      {sendError.requestId && (
        <p className="text-xs text-secondary">
          If you report this, include this id:{' '}
          <span className="tabular-nums">{sendError.requestId}</span>
        </p>
      )}
      {/* The backend's `retryable` flag is the contract for whether this button is honest
          (types.ts). Showing it on a non-retryable failure sends a sick person into a loop. */}
      {sendError.retryable && (
        <Button label="Try again" variant="secondary" onClick={onRetry} />
      )}
    </section>
  )
}

function AnsweredTurn({ turn }: { turn: ChatTurn }) {
  const answer = turn.answer
  if (!answer) return null
  const emergency = answer.urgency.level === 'emergency'

  return (
    <section className="flex flex-col gap-3">
      {emergency && (
        <Banner
          status="error"
          title={answer.urgency.title}
          description={answer.urgency.message}
        />
      )}

      <p className="whitespace-pre-wrap text-primary">{answer.summary}</p>

      {/* TODO(team): proper medication cards with dosage pictograms — DEV-308 */}
      {answer.drugs.map((drug) => (
        <Card key={drug.id}>
          <div className="flex flex-col gap-2 p-4">
            <h2 className="text-lg font-medium text-primary">{drug.productNameKo}</h2>
            {drug.productNameEn && <p className="text-sm text-secondary">{drug.productNameEn}</p>}
            <AllergyBadge check={drug.allergyCheck} />
            {drug.warnings.map((warning, index) => (
              <p key={index} className="text-sm text-secondary">
                {warning}
              </p>
            ))}
          </div>
        </Card>
      ))}

      {/* The assistant asks for the map through `uiActions`; it never calls a tool (spec §2-1). */}
      {answer.uiActions.map((action, index) =>
        action.type === 'OPEN_FACILITY_MAP' ? (
          <NearbyFacilities
            key={index}
            types={action.payload.types}
            radiusM={action.payload.radiusM}
            openNow={action.payload.openNow}
          />
        ) : null,
      )}

      {/* Provenance. Never present fixture data as live (spec §2-14). */}
      {answer.dataStatus === 'fixture' && (
        <p className="text-xs text-secondary">
          Showing sample data — the live government data source was unavailable.
        </p>
      )}
    </section>
  )
}

export function ChatScreen() {
  const {
    turns,
    streaming,
    elapsedS,
    sendError,
    latestAnswer,
    allergies,
    send,
    confirmAllergies,
    newConversation,
  } = useChatSession()
  const [input, setInput] = useState('')
  const [menuOpen, setMenuOpen] = useState(false)
  const [dismissedClarification, setDismissedClarification] =
    useState<MermAidAnswer | null>(null)
  const [editingAllergies, setEditingAllergies] = useState(false)
  const composerRef = useRef<HTMLTextAreaElement>(null)

  const clarificationNeedsSelection =
    latestAnswer?.answerId === 'allergy-clarification' &&
    latestAnswer !== dismissedClarification &&
    allergies.length === 0
  const pickerOpen = editingAllergies || clarificationNeedsSelection
  const composerPlaceholder = allergies.length
    ? 'Ask your question again — answers will avoid your selected ingredients.'
    : "I have a sore throat and a fever, and it's 11pm."

  // `retryable: false` means "this exact request will not succeed if resent" — so the block
  // lifts the moment the question is edited. Locking Ask outright would trap the user whose
  // correct next move IS an edit (INPUT_TOO_LARGE: shorten it and ask again).
  const askBlocked =
    sendError !== null && !sendError.retryable && input === sendError.forInput
  const submitBlocked = !input.trim() || streaming || askBlocked

  function submit(text: string) {
    if (!text.trim() || streaming || askBlocked) return
    void send(text)
  }

  function startNewConversation() {
    newConversation()
    setInput('')
    setMenuOpen(false)
    setDismissedClarification(null)
    setEditingAllergies(false)
  }

  function confirmSelectedAllergies(keys: string[]) {
    confirmAllergies(keys)
    setEditingAllergies(false)
    composerRef.current?.focus()
  }

  function dismissAllergenPicker() {
    setDismissedClarification(latestAnswer)
    setEditingAllergies(false)
    composerRef.current?.focus()
  }

  const composer = (
    <div className="flex flex-col gap-2 pb-2">
      {pickerOpen && (
        <AllergenPicker
          initialSelectedKeys={allergies}
          onConfirm={confirmSelectedAllergies}
          onDismiss={dismissAllergenPicker}
        />
      )}
      {allergies.length > 0 && !pickerOpen && (
        <div className="flex justify-end px-3">
          <Button
            label="Edit allergy list"
            variant="secondary"
            onClick={() => setEditingAllergies(true)}
          />
        </div>
      )}
      {/* SA-04: nudge people away from typing identifying details into a chat box. */}
      <div className="px-3">
        <ChatComposer
          density="compact"
          value={input}
          onChange={setInput}
          onSubmit={submit}
          placeholder={composerPlaceholder}
          input={
            <TextArea
              ref={composerRef}
              label="Describe your symptoms"
              description="Please do not enter your passport number or date of birth."
              placeholder={composerPlaceholder}
              rows={3}
              value={input}
              onChange={setInput}
            />
          }
          sendButton={
            <Button
              label={streaming ? 'Working…' : 'Ask'}
              variant="primary"
              isLoading={streaming}
              isDisabled={submitBlocked}
              onClick={() => submit(input)}
            />
          }
        />
      </div>
    </div>
  )

  return (
    <main className="flex h-full flex-col">
      <header className="flex items-start justify-between gap-3 px-4 py-3">
        <div>
          <h1 className="text-lg font-semibold text-primary">mermAid</h1>
          <p className="text-xs text-secondary">
            Find care and understand Korean medicines — in English, without signing in.
          </p>
        </div>
        <div className="relative">
          <button
            type="button"
            aria-label="Conversation menu"
            aria-expanded={menuOpen}
            className="min-h-11 min-w-11 rounded border border-primary px-2 text-primary"
            onClick={() => setMenuOpen((open) => !open)}
          >
            •••
          </button>
          {menuOpen && (
            <div
              role="menu"
              className="absolute right-0 z-10 mt-1 w-72 rounded border border-primary bg-surface p-2 shadow"
            >
              <button
                type="button"
                role="menuitem"
                className="min-h-11 w-full text-left text-sm font-medium text-primary"
                onClick={startNewConversation}
              >
                New conversation
              </button>
              <div role="menuitem" className="border-t border-primary pt-2 text-xs text-secondary">
                <p className="font-medium text-primary">This tab&apos;s conversation</p>
                <p>{SESSION_COPY}</p>
              </div>
            </div>
          )}
        </div>
      </header>

      <ChatLayout density="compact" className="min-h-0 flex-1" composer={composer}>
        <ChatMessageList isStreaming={streaming} density="compact">
          {turns.length === 0 && (
            <ChatSystemMessage>Describe what you are feeling to start.</ChatSystemMessage>
          )}
          {turns.map((turn, index) => {
            const pending =
              streaming && index === turns.length - 1 && !turn.answer && !turn.error
            return (
              <div key={turn.id} className="flex flex-col gap-2">
                <ChatMessage sender="user">
                  <ChatMessageBubble>{turn.question}</ChatMessageBubble>
                </ChatMessage>
                <ChatMessage sender="assistant">
                  <ChatMessageBubble variant="ghost">
                    {pending && <PendingAnswer elapsedS={elapsedS} />}
                    {turn.error && <FailedAnswer turn={turn} onRetry={() => submit(input)} />}
                    {turn.answer && <AnsweredTurn turn={turn} />}
                  </ChatMessageBubble>
                </ChatMessage>
              </div>
            )
          })}
        </ChatMessageList>
      </ChatLayout>
    </main>
  )
}
