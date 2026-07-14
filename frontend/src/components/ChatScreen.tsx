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

/**
 * A question that got no answer, and is no longer the one the composer is holding.
 *
 * <p>Either it failed and the person moved on — they edited their question, and the new one was
 * answered — or it came back from storage, where a failure's cause does not survive. Both cases
 * have the same honest shape: we know it was asked and never answered, and we do not know that
 * asking it again would work. So there is no Try again here, and no claim that it is in the box:
 * the box holds whatever the person is writing now. The question itself is still in the record, and
 * still in every request we send (FR-013), which is what it is there for.
 */
function UnansweredQuestion() {
  return (
    <p data-testid="chat-unanswered" className="text-sm text-secondary">
      This question was never answered. It is still part of this conversation — ask it again if you
      still need it.
    </p>
  )
}

function FailedAnswer({
  turn,
  onRetry,
  onStartOver,
}: {
  turn: ChatTurn
  onRetry: () => void
  onStartOver: () => void
}) {
  const sendError = turn.error
  if (!sendError) return null

  return (
    <section data-testid="chat-error" className="flex flex-col gap-3">
      <Banner
        status="error"
        title="We could not get an answer."
        description={
          (sendError.retryable
            ? // Always true now, and cheap to keep true: the box is never cleared until an answer
              // arrives, so the question that failed is the question still sitting in it.
              'Your question was not lost — it is still in the box above. '
            : // The question stays in this conversation, so it also rides in the next request — we
              // keep it because it may be the sentence that declared an allergy (FR-013), and it is
              // the only place that declaration lives. Which means a request the server refused for
              // what this question CONTAINS will be refused again. Editing may work; if it does not,
              // the way out is a new conversation, and saying so beats letting someone try forever.
              'Asking this exact question again will not help. Try asking it differently — and if ' +
              'that keeps failing, start a new conversation. ') +
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
      {sendError.retryable ? (
        <Button label="Try again" variant="secondary" onClick={onRetry} />
      ) : (
        <Button label="Start a new conversation" variant="secondary" onClick={onStartOver} />
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

      {/* Answer-level warnings — the server-authored unverified-allergen caveat lands here (§2-2,
          FR-017). It must be visible even when no drug card carries a per-card warning, or a
          name-only allergy check reads as a full one. role=status so a screen reader hears it. */}
      {answer.warnings.length > 0 && (
        <div
          role="status"
          className="flex flex-col gap-1 rounded border border-[#e0a800] bg-surface p-3 text-sm text-primary"
        >
          {answer.warnings.map((warning, index) => (
            <p key={index}>{warning}</p>
          ))}
        </div>
      )}

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
    unverifiedAllergens,
    unverifiableAllergy,
    pendingQuestion,
    send,
    confirmAllergies,
    declareUnverifiableAllergy,
    newConversation,
  } = useChatSession()
  // Seeded from the session: a question asked but never answered (the request failed, or the tab
  // was reloaded mid-flight) comes back into the box, because that is the only place it lives.
  const [input, setInput] = useState(pendingQuestion)
  const [menuOpen, setMenuOpen] = useState(false)
  // The clarification this user has already answered — by confirming a selection OR by
  // dismissing it. A LATER clarification is a different answer object, so it re-opens the
  // picker even when a selection already exists. Gating on `allergies.length === 0` instead
  // would suppress the picker for a second, newly-declared allergy and leave the stale list
  // in place — the request would then carry only the old exclude_ingredients and the backend,
  // seeing a non-empty resolved list, would retrieve as if the new allergen were never named.
  const [handledClarification, setHandledClarification] =
    useState<MermAidAnswer | null>(null)
  const [editingAllergies, setEditingAllergies] = useState(false)
  const composerRef = useRef<HTMLTextAreaElement>(null)

  const clarificationNeedsSelection =
    latestAnswer?.answerId === 'allergy-clarification' &&
    latestAnswer !== handledClarification
  const pickerOpen = editingAllergies || clarificationNeedsSelection
  // Two lists, two different promises, and conflating them overstates the safety of one. Only
  // `allergies` become `exclude_ingredients` — resolved keys the backend filters retrieval on.
  // `unverifiedAllergens` are strings the user typed: the backend matches them against ingredient
  // names and warns, and never excludes a product on their account (§2-6 — an unsigned binding may
  // not block). "Answers will avoid" would promise a filter that does not run for them.
  const composerPlaceholder = unverifiedAllergens.length
    ? allergies.length
      ? 'Ask again — answers avoid your selected ingredients. The allergens you typed are checked by name only.'
      : 'Ask again — the allergens you typed are checked by name only, not avoided.'
    : allergies.length
      ? 'Ask your question again — answers will avoid your selected ingredients.'
      : "I have a sore throat and a fever, and it's 11pm."

  // `retryable: false` means "this exact request will not succeed if resent" — so the block
  // lifts the moment the question is edited. Locking Ask outright would trap the user whose
  // correct next move IS an edit (INPUT_TOO_LARGE: shorten it and ask again).
  const askBlocked =
    sendError !== null && !sendError.retryable && input === sendError.forInput
  // While the picker is open, any request would carry the STALE exclude_ingredients — the newly
  // declared allergen is not in the list until the user confirms. Sending first would let the
  // backend proceed on a resolved-but-incomplete list and show a product containing it as
  // no_match_found. So the structured list must be updated (confirm) or the picker answered
  // (dismiss) before anything is sent. The picker itself, not the composer, is the next action.
  const submitBlocked = !input.trim() || streaming || askBlocked || pickerOpen

  // The box empties when — and only when — the answer arrives.
  //
  // Clearing at send instead (the first fix for "…ibuprofenibuprofen", where a question left in
  // the box was resent glued to the front of the next one) opened a state space: for the ~100s a
  // cold answer takes, the box was free to hold something OTHER than the question in flight. A
  // draft. Everything that could then happen to it — retry sends the draft, retry sends the failed
  // question twice, a different follow-up drops the failed question from the request, a reload
  // drops it from sessionStorage — was a separate defect in a separate layer, and review found
  // them one at a time, six in a row.
  //
  // There is no draft here. The box holds exactly one thing: the question that has not been
  // answered yet. It is locked while that question is in flight (below), it survives a failure
  // untouched, it is persisted so a reload brings it back, and it empties on success. The state
  // space is not guarded — it does not exist.
  async function submit(text: string) {
    if (submitBlocked) return
    const answered = await send(text)
    if (answered) setInput('')
  }

  // Try again sends whatever the box holds — which is the failed question, unless the person has
  // since edited it, and then the edit is what they mean to ask. Sending a remembered copy instead
  // would send the old text and then clear the new: their edited symptom or allergy would go
  // nowhere, and vanish. There is one send path, and it reads the box.
  function retryFailed() {
    void submit(input)
  }

  function startNewConversation() {
    newConversation()
    setInput('')
    setMenuOpen(false)
    setHandledClarification(null)
    setEditingAllergies(false)
  }

  function confirmSelectedAllergies(keys: string[], unverified: string[]) {
    // Confirming is the user asserting "this list — selections plus unverified chips — is my
    // allergies". It covers the declaration whether or not it added a NEW entry: re-stating an
    // already-selected allergy (ibuprofen selected, then "…and I'm allergic to ibuprofen") must
    // proceed on the existing list, not lock the conversation. Only "My allergy isn't listed"
    // (dismiss) declares something the list cannot express and ends lookup.
    confirmAllergies(keys, unverified)
    // Confirming answers the current clarification: close the picker until a LATER one arrives.
    // The composer takes the picker's place again on the next render — its reappearance is the
    // cue to ask again, so no explicit focus call (which would race that remount) is needed.
    setHandledClarification(latestAnswer)
    setEditingAllergies(false)
  }

  function dismissAllergenPicker() {
    setHandledClarification(latestAnswer)
    setEditingAllergies(false)
    if (clarificationNeedsSelection) {
      // Closing a clarification without adding an item leaves the new declaration uncovered.
      // Persist the lock so reload cannot silently resume on the older, incomplete lists.
      declareUnverifiableAllergy()
    }
  }

  // Drug lookup ended for this conversation: an allergy we cannot verify was declared.
  const unverifiableNotice = (
    <div className="flex flex-col gap-3 px-3 pb-2">
      <p className="text-sm text-primary">
        You told us about an allergy that isn&rsquo;t in our list, so we can&rsquo;t check
        medicines against it in this conversation. Please ask a pharmacist, who can advise on what
        you can take.
      </p>
      <div>
        <Button label="Start a new conversation" variant="primary" onClick={startNewConversation} />
      </div>
    </div>
  )

  // The picker takes the composer's place, not a panel above it: with no composer there is no
  // Ask, so a request cannot carry a stale exclude_ingredients before the selection is made
  // (the 2nd-P0 fix, now structural rather than a disabled-button guard).
  const pickerPanel = (
    <div className="px-3 pb-2">
      <AllergenPicker
        initialSelectedKeys={allergies}
        initialUnverifiedAllergens={unverifiedAllergens}
        onConfirm={confirmSelectedAllergies}
        onDismiss={dismissAllergenPicker}
      />
    </div>
  )

  const composerPanel = (
    <div className="flex flex-col gap-2 pb-2">
      {(allergies.length > 0 || unverifiedAllergens.length > 0) && (
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
            /* Locked while the answer is in flight. The box holds the question that was asked, and
               for the ~100s a cold answer takes it must keep holding exactly that — an editable box
               is a draft, and a draft is what the six review findings were all made of. It unlocks
               on the answer (empty) or on the failure (the question, ready to edit or resend). */
            <TextArea
              ref={composerRef}
              label="Describe your symptoms"
              description="Please do not enter your passport number or date of birth."
              placeholder={composerPlaceholder}
              rows={3}
              value={input}
              onChange={setInput}
              isDisabled={streaming}
              // Not a bare `disabled`: with a message, astryx keeps the field focusable and marks
              // it aria-disabled, so a keyboard or screen-reader user is told WHY it will not take
              // their text. A silently dead box during a 100-second wait reads as a broken app.
              disabledMessage="Your question is being answered. The box unlocks when the answer arrives."
            />
          }
          sendButton={
            <Button
              label={streaming ? 'Working…' : 'Ask'}
              variant="primary"
              isLoading={streaming}
              isDisabled={submitBlocked}
              onClick={() => void submit(input)}
            />
          }
        />
      </div>
    </div>
  )

  const composer = unverifiableAllergy
    ? unverifiableNotice
    : pickerOpen
      ? pickerPanel
      : composerPanel

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
            // Try again sends the composer, and the composer holds the question that has not been
            // answered — which is this turn only while it is the last one. Once the person edits it
            // into something else and that succeeds, this turn is history: the box has moved on, so
            // a Try again here would send the wrong text, and "it is still in the box above" would
            // be a lie. History says what it knows and offers no button.
            const active = index === turns.length - 1
            return (
              <div key={turn.id} className="flex flex-col gap-2">
                <ChatMessage sender="user">
                  <ChatMessageBubble>{turn.question}</ChatMessageBubble>
                </ChatMessage>
                <ChatMessage sender="assistant">
                  <ChatMessageBubble variant="ghost">
                    {pending && <PendingAnswer elapsedS={elapsedS} />}
                    {turn.error &&
                      (active ? (
                        <FailedAnswer
                          turn={turn}
                          onRetry={retryFailed}
                          onStartOver={startNewConversation}
                        />
                      ) : (
                        <UnansweredQuestion />
                      ))}
                    {turn.unanswered && <UnansweredQuestion />}
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
