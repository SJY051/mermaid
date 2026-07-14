import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ChatProvider } from '../lib/chatSession'
import { loadChatSession } from '../lib/storage'
import { ChatScreen } from './ChatScreen'

// jsdom has no layout engine, so astryx's scroll-following ResizeObserver needs a no-op browser
// boundary in component tests. The observer's behavior belongs to the kit; these tests own chat.
vi.stubGlobal(
  'ResizeObserver',
  class {
    observe() {}
    unobserve() {}
    disconnect() {}
  },
)

/**
 * The chat's cold path exceeds 100 seconds. These tests pin the four things a person waiting
 * that long must see: that something is visibly happening, that success renders the answer,
 * that failure is an honest error with a retry — never dressed up as an assistant answer —
 * and that the emergency banner shows when the server says emergency.
 *
 * `streamChat` is mocked; `parseAnswer` is the real one, because its guarantees (disclaimer,
 * no fabricated drugs) are part of what the screen shows.
 */
const streamChatMock = vi.hoisted(() => vi.fn())
const fetchMock = vi.fn()
vi.mock('../lib/openaiClient', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../lib/openaiClient')>()
  return { ...actual, streamChat: streamChatMock }
})

/** A generator the test resolves by hand, so the pending state can be observed. */
function pendingStream() {
  let release!: (text: string) => void
  let fail!: (e: Error) => void
  const gate = new Promise<string>((resolve, reject) => {
    release = resolve
    fail = reject
  })
  async function* stream() {
    yield await gate
  }
  return { stream, release, fail }
}

async function* completedStream(answer: string) {
  yield answer
}

const validAnswer = JSON.stringify({
  schemaVersion: '1.0',
  answerId: 'a1',
  language: 'en',
  dataStatus: 'live',
  urgency: { level: 'routine', title: 'T', message: 'M', reasonCodes: [], actions: [] },
  summary: 'Drink water and rest.',
  clarifyingQuestions: [],
  guidance: [],
  drugs: [],
  uiActions: [],
  sourceRefs: [],
  warnings: [],
  disclaimer: 'Server disclaimer.',
})

const clarificationAnswer = JSON.stringify({
  ...JSON.parse(validAnswer),
  answerId: 'allergy-clarification',
  summary: 'Tell us the exact ingredient, or ask a pharmacist if it is not listed.',
})

const allergenOptions = [
  { key: 'ibuprofen', label: 'Ibuprofen' },
  { key: 'acetylsalicylic-acid', label: 'Aspirin (acetylsalicylic acid)' },
]

function serveAllergenOptions(options = allergenOptions) {
  fetchMock.mockResolvedValue({
    ok: true,
    json: async () => options,
  })
}

function renderChat() {
  return render(
    <ChatProvider>
      <ChatScreen />
    </ChatProvider>,
  )
}

async function ask(text = 'I have a headache') {
  const user = userEvent.setup()
  await user.type(screen.getByRole('textbox'), text)
  await user.click(screen.getByRole('button', { name: /ask/i }))
  return user
}

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  // Restore real timers HERE, not in the test body: a test that times out never reaches its
  // own finally, and every later test would then hang inside waitFor on a frozen clock.
  vi.useRealTimers()
  streamChatMock.mockReset()
})

describe('while the answer is being written (the >100s cold path)', () => {
  it('shows a live progress region, not a frozen screen', async () => {
    const { stream, release } = pendingStream()
    streamChatMock.mockReturnValue(stream())
    renderChat()
    await ask()

    const progress = await screen.findByTestId('chat-progress')
    expect(progress).toHaveTextContent(/writing an answer/i)
    expect(progress).toHaveTextContent(/nothing is stuck/i)

    release(validAnswer)
    await waitFor(() => expect(screen.queryByTestId('chat-progress')).not.toBeInTheDocument())
  })

  it('counts elapsed seconds, and reassures when the wait passes 90s', async () => {
    vi.useFakeTimers()
    const { stream, release } = pendingStream()
    streamChatMock.mockReturnValue(stream())
    renderChat()

    // userEvent and findBy* both wait on real timeouts, which a fake clock freezes.
    // Drive the DOM synchronously instead.
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'headache' } })
    fireEvent.click(screen.getByRole('button', { name: /ask/i }))

    const progress = screen.getByTestId('chat-progress')
    expect(progress).toHaveTextContent(/\b0s\b/)

    await vi.advanceTimersByTimeAsync(12_000)
    expect(progress).toHaveTextContent(/\b12s\b/)
    expect(progress).not.toHaveTextContent(/still working/i)

    await vi.advanceTimersByTimeAsync(80_000)
    expect(progress).toHaveTextContent(/\b92s\b/)
    // Past 90s the screen must say, in words, that it is not stuck.
    expect(progress).toHaveTextContent(/still working/i)

    release(validAnswer)
  })
})

describe('when the answer arrives', () => {
  it('renders the summary and identifies fixture provenance', async () => {
    const fixtureAnswer = JSON.parse(validAnswer)
    fixtureAnswer.dataStatus = 'fixture'
    const { stream, release } = pendingStream()
    streamChatMock.mockReturnValue(stream())
    renderChat()
    await ask()
    release(JSON.stringify(fixtureAnswer))

    expect(await screen.findByText('Drink water and rest.')).toBeInTheDocument()
    expect(screen.getByText(/showing sample data/i)).toBeInTheDocument()
  })

  it('shows the emergency banner when the server says emergency', async () => {
    const emergency = JSON.parse(validAnswer)
    emergency.urgency = {
      level: 'emergency',
      title: 'Call 119 now',
      message: 'Your symptoms need immediate care.',
      reasonCodes: [],
      actions: [],
    }
    const { stream, release } = pendingStream()
    streamChatMock.mockReturnValue(stream())
    renderChat()
    await ask('crushing chest pain')
    release(JSON.stringify(emergency))

    expect(await screen.findByText('Call 119 now')).toBeInTheDocument()
  })
})

describe('when the request fails', () => {
  it('shows an honest error — not something dressed as an assistant answer', async () => {
    const { stream, fail } = pendingStream()
    streamChatMock.mockReturnValue(stream())
    renderChat()
    await ask()
    fail(new Error('LLM upstream timed out'))

    const error = await screen.findByTestId('chat-error')
    expect(error).toHaveTextContent(/could not get an answer/i)
    expect(error).toHaveTextContent('LLM upstream timed out')
    // The failure must not render as a medical response.
    expect(screen.queryByText(/sorry — something went wrong/i)).not.toBeInTheDocument()
    // The question survives for the retry.
    expect(screen.getByRole('textbox')).toHaveValue('I have a headache')
  })

  it('blocks resending an unchanged question the backend called non-retryable — until it is edited', async () => {
    const { stream, fail } = pendingStream()
    streamChatMock.mockReturnValue(stream())
    renderChat()
    const user = await ask()
    fail(
      Object.assign(new Error('500'), {
        error: {
          code: 'INTERNAL_ERROR',
          message: 'Something broke on our side.',
          retryable: false,
          request_id: 'req-42',
        },
      }),
    )

    const error = await screen.findByTestId('chat-error')
    // The backend said resending this exact request cannot help — offering any resend button
    // would be a lie that loops a sick person (types.ts: "Whether offering a 'try again'
    // button is honest"). That covers the primary Ask button too, not just Try again.
    expect(error).toHaveTextContent(/asking this exact question again will not help/i)
    expect(screen.queryByRole('button', { name: /try again/i })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /ask/i })).toBeDisabled()
    // The id that finds the server log, for the bug report.
    expect(error).toHaveTextContent('req-42')

    // "Non-retryable" is about the unchanged request — editing the question is the recovery
    // path (INPUT_TOO_LARGE: shorten it), so the block must lift on edit.
    await user.type(screen.getByRole('textbox'), ' since yesterday')
    expect(screen.getByRole('button', { name: /ask/i })).toBeEnabled()
  })

  it('locks the composer while the answer is in flight, so no draft can exist (P1)', async () => {
    // The root of six review findings in a row: clearing the box at send let it hold something
    // OTHER than the question in flight for the ~100s a cold answer takes. Every one of those
    // findings was a different thing going wrong with that draft. There is no draft now — the box
    // holds the asked question and cannot be edited until the answer (or the failure) arrives.
    const first = pendingStream()
    streamChatMock.mockReturnValueOnce(first.stream())
    renderChat()
    const user = await ask('I am allergic to ibuprofen')

    const box = screen.getByRole('textbox')
    expect(box).toHaveValue('I am allergic to ibuprofen')
    // aria-disabled, not `disabled`: the field stays focusable and says why it is locked, so a
    // keyboard or screen-reader user is not left at a silently dead box for a hundred seconds.
    expect(box).toHaveAttribute('aria-disabled', 'true')
    await user.type(box, ' and I also have fever')
    expect(box).toHaveValue('I am allergic to ibuprofen')

    first.release(validAnswer)
    await screen.findByText('Drink water and rest.')
    // The answer landed: only now does the box empty, and only now is it editable again.
    expect(screen.getByRole('textbox')).toHaveValue('')
    expect(screen.getByRole('textbox')).not.toHaveAttribute('aria-disabled', 'true')
  })

  it('keeps the failed question in the box, and Try again sends it exactly once (P1)', async () => {
    const first = pendingStream()
    const second = pendingStream()
    streamChatMock.mockReturnValueOnce(first.stream()).mockReturnValueOnce(second.stream())
    renderChat()
    const user = await ask('What can I take for a fever?')

    first.fail(new Error('boom'))
    const error = await screen.findByTestId('chat-error')

    // The banner promises the question is still in the box. It is — it never left.
    expect(screen.getByRole('textbox')).toHaveValue('What can I take for a fever?')
    expect(error).toHaveTextContent('still in the box above')

    await user.click(screen.getByRole('button', { name: /try again/i }))
    second.release(validAnswer)
    expect(await screen.findByText('Drink water and rest.')).toBeInTheDocument()

    // A retry REPLACES the failed turn, so its question rides exactly once. Asserting only the
    // last message would miss the duplicate, which is what this guards.
    const retryCall = streamChatMock.mock.calls[1][0] as { role: string; content: string }[]
    expect(retryCall.filter((m) => m.role === 'user')).toEqual([
      { role: 'user', content: 'What can I take for a fever?' },
    ])
    expect(screen.getByRole('textbox')).toHaveValue('')
  })

  it('Try again sends what the box holds now, not the question that failed (P1)', async () => {
    // The box unlocks after a failure, so the person can correct what they asked. If Try again then
    // resent a remembered copy of the OLD text, their correction would go nowhere — and worse, the
    // success would clear the box, so a newly typed allergy or symptom would be gone for good.
    const first = pendingStream()
    streamChatMock.mockReturnValueOnce(first.stream())
    renderChat()
    const user = await ask('What can I take for a fever?')
    first.fail(new Error('boom'))
    await screen.findByTestId('chat-error')

    streamChatMock.mockReturnValueOnce(completedStream(validAnswer))
    await user.clear(screen.getByRole('textbox'))
    await user.type(screen.getByRole('textbox'), 'I am allergic to ibuprofen, what can I take?')
    await user.click(screen.getByRole('button', { name: /try again/i }))
    await screen.findByText('Drink water and rest.')

    const sent = streamChatMock.mock.calls[1][0] as { role: string; content: string }[]
    expect(sent.filter((m) => m.role === 'user').map((m) => m.content)).toContain(
      'I am allergic to ibuprofen, what can I take?',
    )
  })

  it('never drops a declared allergy to escape a non-retryable failure (P0)', async () => {
    // For an hour this branch dropped the failed turn when the server called it non-retryable, so
    // that a "shorten your question" recovery could work. But the sentence being dropped may be the
    // one that declared an allergy — and it is the only place that declaration lives, because the
    // turn that would have offered the picker is the turn that failed. The server reads the
    // declaration from the questions in the request (FR-013); drop it and the next turn retrieves
    // unguarded and can hand the person the very ingredient they named. The dead end is the lesser
    // harm, and it has an honest exit: a new conversation.
    const { stream, fail } = pendingStream()
    streamChatMock.mockReturnValueOnce(stream())
    renderChat()
    const user = await ask('I am allergic to ibuprofen, and I have a bad headache')
    fail(
      Object.assign(new Error('400'), {
        error: {
          code: 'INVALID_REQUEST',
          message: 'That question could not be processed.',
          retryable: false,
          request_id: 'req-9',
        },
      }),
    )
    await screen.findByTestId('chat-error')

    streamChatMock.mockReturnValueOnce(completedStream(validAnswer))
    await user.clear(screen.getByRole('textbox'))
    await user.type(screen.getByRole('textbox'), 'what can I take?')
    await user.click(screen.getByRole('button', { name: /ask/i }))
    await screen.findByText('Drink water and rest.')

    const sent = streamChatMock.mock.calls[1][0] as { role: string; content: string }[]
    expect(sent.filter((m) => m.role === 'user').map((m) => m.content)).toEqual([
      'I am allergic to ibuprofen, and I have a bad headache',
      'what can I take?',
    ])
  })

  it('offers a way out of a non-retryable failure instead of a retry that cannot work (P1)', async () => {
    const { stream, fail } = pendingStream()
    streamChatMock.mockReturnValueOnce(stream())
    renderChat()
    const user = await ask('I am allergic to ibuprofen, and I have a bad headache')
    fail(
      Object.assign(new Error('400'), {
        error: {
          code: 'INVALID_REQUEST',
          message: 'That question could not be processed.',
          retryable: false,
          request_id: 'req-9',
        },
      }),
    )
    const error = await screen.findByTestId('chat-error')

    // No Try again — the backend said resending cannot help. But the person is not left to guess:
    // the failed question stays in this conversation, so a request refused for what it contains
    // will be refused again, and the exit is a new conversation. The banner says so, and offers it.
    expect(screen.queryByRole('button', { name: /try again/i })).not.toBeInTheDocument()
    expect(error).toHaveTextContent(/start a new conversation/i)

    await user.click(screen.getByRole('button', { name: /start a new conversation/i }))
    expect(screen.queryByTestId('chat-error')).not.toBeInTheDocument()
    expect(loadChatSession().messages).toEqual([])
  })

  it('keeps the failed question in the request when it is edited rather than retried (P1)', async () => {
    // A retry replaces the failed turn; an EDIT does not. The person still asked the first
    // question, and if the edit is what drops it, a failed "I am allergic to ibuprofen" edited into
    // "what can I take for a headache" would reach the server with no allergy in it at all — and
    // the answer would offer them ibuprofen. Editing keeps both questions in the request.
    const first = pendingStream()
    streamChatMock.mockReturnValueOnce(first.stream())
    renderChat()
    const user = await ask('I am allergic to ibuprofen')
    first.fail(new Error('boom'))
    await screen.findByTestId('chat-error')

    streamChatMock.mockReturnValueOnce(completedStream(validAnswer))
    await user.clear(screen.getByRole('textbox'))
    await user.type(screen.getByRole('textbox'), 'What can I take for a headache?')
    await user.click(screen.getByRole('button', { name: /ask/i }))
    await screen.findByText('Drink water and rest.')

    const sent = streamChatMock.mock.calls[1][0] as { role: string; content: string }[]
    expect(sent.filter((m) => m.role === 'user')).toEqual([
      { role: 'user', content: 'I am allergic to ibuprofen' },
      { role: 'user', content: 'What can I take for a headache?' },
    ])
  })

  it('keeps a failed question across a reload and sends it with the next request (P1)', async () => {
    // sessionStorage holds answered turns only, so a failed "I am allergic to ibuprofen" used to
    // vanish on reload — and the server's scan reads the questions in the request (FR-013), so a
    // question it never sees guards nothing: the next turn would retrieve unguarded and could hand
    // the person the very ingredient they named. The unanswered question is persisted, restored
    // into the box, and rides along when they ask again.
    const first = pendingStream()
    streamChatMock.mockReturnValueOnce(first.stream())
    const { unmount } = renderChat()
    await ask('I am allergic to ibuprofen')
    first.fail(new Error('boom'))
    await screen.findByTestId('chat-error')
    unmount()

    streamChatMock.mockReturnValueOnce(completedStream(validAnswer))
    renderChat()
    const reloadedUser = userEvent.setup()
    const box = screen.getByRole('textbox')
    // Two places, one question: the transcript keeps it (unanswered, honestly labelled) and the box
    // holds it ready to send. Neither is a copy the other can lose.
    expect(box).toHaveValue('I am allergic to ibuprofen')
    // Restored, so we know it was asked and never answered — and nothing else. No fabricated
    // "retryable" verdict, and so no Try again button making a promise we cannot keep.
    expect(screen.getByTestId('chat-unanswered')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /try again/i })).not.toBeInTheDocument()

    await reloadedUser.type(box, ' and a headache')
    await reloadedUser.click(screen.getByRole('button', { name: /ask/i }))
    await screen.findByText('Drink water and rest.')

    // The edit did not overwrite the declaration — it followed it. Both ride, and the server's scan
    // sees the allergy either way (FR-013). A verbatim retry would have replaced it instead.
    const sent = streamChatMock.mock.calls.at(-1)![0] as { role: string; content: string }[]
    expect(sent.filter((m) => m.role === 'user')).toEqual([
      { role: 'user', content: 'I am allergic to ibuprofen' },
      { role: 'user', content: 'I am allergic to ibuprofen and a headache' },
    ])
  })

  it('drops the retry affordance once the failed turn is history (P1)', async () => {
    // Try again sends the composer. That is right while the failed question IS what the composer
    // holds — and wrong the moment the person edits it into something else and that succeeds: the
    // box is empty now, so the old turn's Try again would send nothing or, worse, an unrelated
    // draft, while its banner still claimed the question was "still in the box above".
    const first = pendingStream()
    streamChatMock.mockReturnValueOnce(first.stream())
    renderChat()
    const user = await ask('What can I take for a fever?')
    first.fail(new Error('boom'))
    await screen.findByTestId('chat-error')
    expect(screen.getByRole('button', { name: /try again/i })).toBeInTheDocument()

    streamChatMock.mockReturnValueOnce(completedStream(validAnswer))
    await user.clear(screen.getByRole('textbox'))
    await user.type(screen.getByRole('textbox'), 'Actually, what about my headache?')
    await user.click(screen.getByRole('button', { name: /ask/i }))
    await screen.findByText('Drink water and rest.')

    // The failed turn is still in the conversation (and in the request history) — but it no longer
    // offers a button that would send the wrong thing, and it no longer claims to be in the box.
    expect(screen.getByTestId('chat-unanswered')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /try again/i })).not.toBeInTheDocument()
    expect(screen.queryByText(/still in the box above/i)).not.toBeInTheDocument()
  })

  it('does not invent a retry verdict for a failure it restored from storage (P1)', async () => {
    // The backend said this exact question cannot succeed if resent (retryable: false), and the app
    // blocked it. A reload cannot remember that — the failure lived in the tab that is gone — so
    // restoring it as "retryable" would offer Try again on a question already called hopeless.
    // We know it was asked and never answered. We say that, and nothing more.
    const { stream, fail } = pendingStream()
    streamChatMock.mockReturnValueOnce(stream())
    const { unmount } = renderChat()
    await ask('a question the server cannot answer')
    fail(
      Object.assign(new Error('400'), {
        error: {
          code: 'INPUT_TOO_LARGE',
          message: 'That question is too long.',
          retryable: false,
          request_id: 'req-7',
        },
      }),
    )
    await screen.findByTestId('chat-error')
    expect(screen.queryByRole('button', { name: /try again/i })).not.toBeInTheDocument()
    unmount()

    renderChat()
    expect(screen.getByTestId('chat-unanswered')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /try again/i })).not.toBeInTheDocument()
    expect(screen.queryByText(/will not fix this one/i)).not.toBeInTheDocument()
  })

  it('reports a question that never got an answer, so the server can gate on it (P0)', async () => {
    // The list already says ibuprofen. The person then declares a SECOND allergy and that request
    // fails — so the clarification never came back, the picker never opened, and aspirin is in no
    // structured list. The sentence rides in the history, and the server, seeing a complete resolved
    // list, would retrieve and could hand them an aspirin product marked no_match_found (§2-2).
    // The client cannot tell which sentences declare an allergy — that judgement is the server's —
    // so it reports the fact it does know: this question was never answered.
    serveAllergenOptions()
    const first = pendingStream()
    streamChatMock.mockReturnValueOnce(first.stream())
    renderChat()
    const user = await ask('I am also allergic to aspirin')
    first.fail(new Error('boom'))
    await screen.findByTestId('chat-error')

    streamChatMock.mockReturnValueOnce(completedStream(validAnswer))
    await user.clear(screen.getByRole('textbox'))
    await user.type(screen.getByRole('textbox'), 'what can I take for a headache?')
    await user.click(screen.getByRole('button', { name: /ask/i }))
    await screen.findByText('Drink water and rest.')

    const extension = streamChatMock.mock.calls[1][2] as { mermaid: Record<string, unknown> }
    expect(extension.mermaid.unanswered_questions).toEqual(['I am also allergic to aspirin'])
  })

  it('stops reporting an unanswered question once the allergy list is confirmed (P0)', async () => {
    // Fail-closed must terminate. The failed sentence never gets an answer — it cannot — so without
    // a cut-off the server would return the clarification on every question for the rest of the
    // conversation. Confirming the picker IS the answer: the person saw their list, pre-filled, and
    // said what to avoid. Everything said before that moment has been in front of them.
    serveAllergenOptions()
    const first = pendingStream()
    streamChatMock
      .mockReturnValueOnce(first.stream())
      .mockReturnValueOnce(completedStream(clarificationAnswer))
    renderChat()
    const user = await ask('I am also allergic to aspirin')
    first.fail(new Error('boom'))
    await screen.findByTestId('chat-error')

    // The next question draws the clarification, and the picker opens.
    await user.clear(screen.getByRole('textbox'))
    await user.type(screen.getByRole('textbox'), 'what can I take?')
    await user.click(screen.getByRole('button', { name: /ask/i }))
    const picker = await screen.findByRole('dialog', { name: /tell us your allergy/i })

    streamChatMock.mockReturnValueOnce(completedStream(validAnswer))
    await user.click(within(picker).getByRole('checkbox', { name: 'Aspirin (acetylsalicylic acid)' }))
    await user.click(within(picker).getByRole('button', { name: 'Use selected allergies' }))
    await user.type(screen.getByRole('textbox'), 'what can I take?')
    await user.click(screen.getByRole('button', { name: /ask/i }))
    await screen.findByText('Drink water and rest.')

    const extension = streamChatMock.mock.calls.at(-1)![2] as { mermaid: Record<string, unknown> }
    expect(extension.mermaid.exclude_ingredients).toEqual(['acetylsalicylic-acid'])
    expect(extension.mermaid.unanswered_questions).toBeUndefined()
  })

  it('does not treat a restored, already-answered clarification as a fresh one (P0)', async () => {
    // `handledClarification` is React state and dies with the tab. `latestAnswer` does not — it is
    // rebuilt from the stored transcript. So after a reload the answered clarification came back with
    // nothing marking it answered, the picker reopened as if the SERVER had just asked, and
    // confirming it stamped a fresh cut-off — which swallowed a failed declaration the person had
    // never seen. The seam `unanswered_questions` exists to close, reopened by pressing F5.
    serveAllergenOptions()
    streamChatMock.mockReturnValueOnce(completedStream(clarificationAnswer))
    const first = renderChat()
    let user = await ask('I am allergic to ibuprofen')
    const picker = await screen.findByRole('dialog', { name: /tell us your allergy/i })
    await user.click(within(picker).getByRole('checkbox', { name: 'Ibuprofen' }))
    await user.click(within(picker).getByRole('button', { name: 'Use selected allergies' }))

    // A second declaration, and its turn dies before the server ever hears it.
    const failing = pendingStream()
    streamChatMock.mockReturnValueOnce(failing.stream())
    await user.type(screen.getByRole('textbox'), 'I am also allergic to aspirin')
    await user.click(screen.getByRole('button', { name: /ask/i }))
    failing.fail(new Error('boom'))
    await screen.findByTestId('chat-error')

    // The reload. sessionStorage survives; React state does not.
    first.unmount()
    renderChat()
    user = userEvent.setup()

    // The picker must NOT reopen: that clarification was answered, and the session says so.
    expect(
      screen.queryByRole('dialog', { name: /tell us your allergy/i }),
    ).not.toBeInTheDocument()

    streamChatMock.mockReturnValueOnce(completedStream(validAnswer))
    await user.type(screen.getByRole('textbox'), 'what can I take for a headache?')
    await user.click(screen.getByRole('button', { name: /ask/i }))
    await screen.findByText('Drink water and rest.')

    // And the failed declaration is still reported — the reload did not cut it off.
    const extension = streamChatMock.mock.calls.at(-1)![2] as { mermaid: Record<string, unknown> }
    expect(extension.mermaid.unanswered_questions).toEqual(['I am also allergic to aspirin'])
    expect(extension.mermaid.exclude_ingredients).toEqual(['ibuprofen'])
  })

  it('keeps reporting a failed declaration when the list is edited from the menu, not asked for (P0)', async () => {
    // The cut-off is earned by ANSWERING a clarification, and "Edit allergy list" is not one.
    // Nobody asked; the person opened the menu to change an entry, and their failed "I am also
    // allergic to aspirin" is not what they are looking at. Confirming an unchanged list there must
    // not drop it from the next request — the server would see a resolved, complete-looking list
    // and retrieve, which is the exact seam unanswered_questions exists to close.
    serveAllergenOptions()
    streamChatMock.mockReturnValueOnce(completedStream(clarificationAnswer))
    renderChat()
    const user = await ask('I am allergic to ibuprofen')
    const picker = await screen.findByRole('dialog', { name: /tell us your allergy/i })
    await user.click(within(picker).getByRole('checkbox', { name: 'Ibuprofen' }))
    await user.click(within(picker).getByRole('button', { name: 'Use selected allergies' }))

    // A second declaration, and its turn dies before the server ever hears it.
    const failing = pendingStream()
    streamChatMock.mockReturnValueOnce(failing.stream())
    await user.type(screen.getByRole('textbox'), 'I am also allergic to aspirin')
    await user.click(screen.getByRole('button', { name: /ask/i }))
    failing.fail(new Error('boom'))
    await screen.findByTestId('chat-error')

    // The person opens the list themselves and confirms it unchanged.
    await user.click(screen.getByRole('button', { name: 'Edit allergy list' }))
    const edit = await screen.findByRole('dialog', { name: /tell us your allergy/i })
    await user.click(within(edit).getByRole('button', { name: 'Use selected allergies' }))

    streamChatMock.mockReturnValueOnce(completedStream(validAnswer))
    await user.clear(screen.getByRole('textbox'))
    await user.type(screen.getByRole('textbox'), 'what can I take for a headache?')
    await user.click(screen.getByRole('button', { name: /ask/i }))
    await screen.findByText('Drink water and rest.')

    const extension = streamChatMock.mock.calls.at(-1)![2] as { mermaid: Record<string, unknown> }
    expect(extension.mermaid.unanswered_questions).toEqual(['I am also allergic to aspirin'])
    // And the list itself is still only what was actually resolved — the aspirin was never bound.
    expect(extension.mermaid.exclude_ingredients).toEqual(['ibuprofen'])
  })

  it('says the escape will clear the allergy list before the user takes it (P1)', async () => {
    // "Start a new conversation" is the way out of a non-retryable failure, and it resets the
    // session — allergies included. That list is what retrieval is filtered on. Recommending it
    // without saying so walks someone into clearing their own guard, and the next answer would be
    // built as if they had never told us.
    serveAllergenOptions()
    streamChatMock.mockReturnValueOnce(completedStream(clarificationAnswer))
    renderChat()
    const user = await ask('I am allergic to ibuprofen')
    const picker = await screen.findByRole('dialog', { name: /tell us your allergy/i })
    await user.click(within(picker).getByRole('checkbox', { name: 'Ibuprofen' }))
    await user.click(within(picker).getByRole('button', { name: 'Use selected allergies' }))

    const failing = pendingStream()
    streamChatMock.mockReturnValueOnce(failing.stream())
    await user.type(screen.getByRole('textbox'), 'what can I take?')
    await user.click(screen.getByRole('button', { name: /ask/i }))
    failing.fail(
      Object.assign(new Error('400'), {
        error: {
          code: 'INVALID_REQUEST',
          message: 'That question could not be processed.',
          retryable: false,
          request_id: 'req-3',
        },
      }),
    )

    const error = await screen.findByTestId('chat-error')
    expect(error).toHaveTextContent(/clears the allergy list you gave us/i)
    expect(error).toHaveTextContent(/tell us again/i)
  })

  it('keeps a failed question in storage after a later turn succeeds (P1)', async () => {
    // The record used to be an append of answered turns only. So a failed "I am allergic to
    // ibuprofen", kept in memory when the user edited it into a different question, was never
    // written down — and the next reload built its history from storage, where the declaration no
    // longer existed. Every request after that reload went out unguarded. The record is now the
    // turn list itself, so what memory keeps, storage keeps.
    const first = pendingStream()
    streamChatMock.mockReturnValueOnce(first.stream())
    const { unmount } = renderChat()
    const user = await ask('I am allergic to ibuprofen')
    first.fail(new Error('boom'))
    await screen.findByTestId('chat-error')

    streamChatMock.mockReturnValueOnce(completedStream(validAnswer))
    await user.clear(screen.getByRole('textbox'))
    await user.type(screen.getByRole('textbox'), 'What can I take for a headache?')
    await user.click(screen.getByRole('button', { name: /ask/i }))
    await screen.findByText('Drink water and rest.')

    const stored = loadChatSession().messages.filter((m) => m.role === 'user').map((m) => m.content)
    expect(stored).toEqual([
      'I am allergic to ibuprofen',
      'What can I take for a headache?',
    ])

    // And it is still there for the request after a reload.
    unmount()
    streamChatMock.mockReturnValueOnce(completedStream(validAnswer))
    renderChat()
    const reloaded = userEvent.setup()
    await reloaded.type(screen.getByRole('textbox'), 'What about ibuprofen gel?')
    await reloaded.click(screen.getByRole('button', { name: /ask/i }))
    await waitFor(() => expect(streamChatMock).toHaveBeenCalledTimes(3))

    const sent = streamChatMock.mock.calls.at(-1)![0] as { role: string; content: string }[]
    expect(sent.filter((m) => m.role === 'user').map((m) => m.content)).toEqual([
      'I am allergic to ibuprofen',
      'What can I take for a headache?',
      'What about ibuprofen gel?',
    ])
  })

  it('retries from the error state and succeeds', async () => {
    const first = pendingStream()
    const second = pendingStream()
    streamChatMock.mockReturnValueOnce(first.stream()).mockReturnValueOnce(second.stream())
    renderChat()
    const user = await ask()
    first.fail(new Error('boom'))
    await screen.findByTestId('chat-error')

    await user.click(screen.getByRole('button', { name: /try again/i }))
    expect(screen.queryByTestId('chat-error')).not.toBeInTheDocument()
    second.release(validAnswer)

    expect(await screen.findByText('Drink water and rest.')).toBeInTheDocument()
    expect(streamChatMock).toHaveBeenCalledTimes(2)
  })
})

describe('conversation state', () => {
  it('accumulates two questions and two answers', async () => {
    const secondAnswer = JSON.stringify({
      ...JSON.parse(validAnswer),
      answerId: 'a2',
      summary: 'Keep monitoring your temperature.',
    })
    streamChatMock
      .mockReturnValueOnce(completedStream(validAnswer))
      .mockReturnValueOnce(completedStream(secondAnswer))
    renderChat()
    const user = await ask('My throat hurts')
    expect(await screen.findByText('Drink water and rest.')).toBeInTheDocument()

    await user.clear(screen.getByRole('textbox'))
    await user.type(screen.getByRole('textbox'), 'I also have a fever')
    await user.click(screen.getByRole('button', { name: /ask/i }))

    expect(await screen.findByText('Keep monitoring your temperature.')).toBeInTheDocument()
    const userMessages = screen.getAllByRole('article', { name: 'Message from user' })
    expect(userMessages).toHaveLength(2)
    expect(within(userMessages[0]).getByText('My throat hurts')).toBeInTheDocument()
    expect(within(userMessages[1]).getByText('I also have a fever')).toBeInTheDocument()
    expect(screen.getByText('Drink water and rest.')).toBeInTheDocument()
  })

  it('sends every earlier user turn with the request, newest last (spec 005 FR-013)', async () => {
    // The server's allergy scan runs over ALL user messages in the request. The turn that
    // matters is the bare reply to the allergy clarifying question: "ibuprofen" alone carries
    // no allergy keyword, so a request carrying only the newest turn would retrieve unguarded
    // and could show the person the very ingredient they just declared. Mutation: send only
    // the current turn again — this goes red.
    const secondAnswer = JSON.stringify({
      ...JSON.parse(validAnswer),
      answerId: 'a2',
      summary: 'Which ingredient are you allergic to?',
    })
    streamChatMock
      .mockReturnValueOnce(completedStream(validAnswer))
      .mockReturnValueOnce(completedStream(secondAnswer))
    renderChat()
    const user = await ask('I am allergic to something, what can I take?')
    expect(await screen.findByText('Drink water and rest.')).toBeInTheDocument()

    await user.clear(screen.getByRole('textbox'))
    await user.type(screen.getByRole('textbox'), 'ibuprofen')
    await user.click(screen.getByRole('button', { name: /ask/i }))
    await screen.findByText('Which ingredient are you allergic to?')

    expect(streamChatMock).toHaveBeenLastCalledWith([
      { role: 'user', content: 'I am allergic to something, what can I take?' },
      { role: 'user', content: 'ibuprofen' },
    ])
  })

  it('clears the composer after a successful send, so the next question is sent alone', async () => {
    const secondAnswer = JSON.stringify({
      ...JSON.parse(validAnswer),
      answerId: 'a2',
      summary: 'Keep monitoring your temperature.',
    })
    streamChatMock
      .mockReturnValueOnce(completedStream(validAnswer))
      .mockReturnValueOnce(completedStream(secondAnswer))
    renderChat()
    const user = await ask('I have a headache but I am allergic to ibuprofen')
    expect(await screen.findByText('Drink water and rest.')).toBeInTheDocument()

    // Deliberately NO user.clear() here — that call in the test above is what masked this
    // bug live: the answered question stayed in the box and rode, unnoticed, in front of
    // the next one ("…ibuprofenibuprofen", verified in a browser 2026-07-14).
    expect(screen.getByRole('textbox')).toHaveValue('')
    await user.type(screen.getByRole('textbox'), 'I also have a fever')
    await user.click(screen.getByRole('button', { name: /ask/i }))

    expect(await screen.findByText('Keep monitoring your temperature.')).toBeInTheDocument()
    const secondCall = streamChatMock.mock.calls[1][0] as { role: string; content: string }[]
    const lastUserMessage = secondCall.filter((m) => m.role === 'user').at(-1)
    expect(lastUserMessage?.content).toBe('I also have a fever')
  })

  it('persists a completed turn and restores it in a fresh provider', async () => {
    streamChatMock.mockReturnValue(completedStream(validAnswer))
    const firstRender = renderChat()
    await ask()
    expect(await screen.findByText('Drink water and rest.')).toBeInTheDocument()

    const stored = loadChatSession()
    expect(stored.sessionId).not.toBe('')
    expect(stored.messages).toEqual([
      expect.objectContaining({ role: 'user', content: 'I have a headache' }),
      expect.objectContaining({ role: 'assistant', content: validAnswer }),
    ])

    firstRender.unmount()
    renderChat()
    expect(screen.getByText('Drink water and rest.')).toBeInTheDocument()
  })

  it('starts a new conversation by clearing the list and stored session', async () => {
    streamChatMock.mockReturnValue(completedStream(validAnswer))
    renderChat()
    await ask()
    expect(await screen.findByText('Drink water and rest.')).toBeInTheDocument()

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Conversation menu' }))
    await user.click(screen.getByRole('menuitem', { name: 'New conversation' }))

    expect(screen.queryByText('Drink water and rest.')).not.toBeInTheDocument()
    expect(screen.queryByText('I have a headache')).not.toBeInTheDocument()
    expect(loadChatSession().messages).toEqual([])
    expect(sessionStorage.getItem('mermaid.chatSession.v1')).toBeNull()
  })

  it('survives a malformed stored session instead of leaving the app blank', () => {
    // Same schema version, broken shape — what stale or corrupt tab storage actually looks like.
    // storage.ts validates only the wrapper, so the provider owns this guard: debris must reset
    // to an empty conversation, never crash ChatProvider into a blank screen.
    for (const messages of [null, [null], [{ role: 'user' }]]) {
      sessionStorage.setItem(
        'mermaid.chatSession.v1',
        JSON.stringify({
          schemaVersion: '1.0',
          data: { sessionId: 's', messages, allergies: [] },
        }),
      )
      const view = renderChat()
      expect(screen.getByText('Describe what you are feeling to start.')).toBeInTheDocument()
      view.unmount()
      sessionStorage.clear()
    }
  })

  it("shows the canonical storage and transmission copy in the session menu", async () => {
    const user = userEvent.setup()
    renderChat()

    await user.click(screen.getByRole('button', { name: 'Conversation menu' }))

    expect(screen.getByText(SESSION_COPY)).toBeInTheDocument()
  })
})

describe('allergen picker (spec 005 FR-014)', () => {
  it('appears only when the latest answer is the allergy clarification', async () => {
    serveAllergenOptions()
    streamChatMock
      .mockReturnValueOnce(completedStream(validAnswer))
      .mockReturnValueOnce(completedStream(clarificationAnswer))
    renderChat()

    const user = await ask('I have a headache')
    expect(await screen.findByText('Drink water and rest.')).toBeInTheDocument()
    expect(screen.queryByRole('dialog', { name: /tell us your allergy/i })).not.toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalled()

    await user.clear(screen.getByRole('textbox'))
    await user.type(screen.getByRole('textbox'), 'I am allergic to a pain medicine')
    await user.click(screen.getByRole('button', { name: /ask/i }))

    expect(
      await screen.findByRole('dialog', { name: /tell us your allergy/i }),
    ).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/ingredients/allergen-options')
  })

  it('renders only options returned by the server and stays hidden when they cannot be fetched', async () => {
    const serverOptions = [
      { key: 'server-key-a', label: 'Server label A' },
      { key: 'server-key-b', label: 'Server label B' },
    ]
    serveAllergenOptions(serverOptions)
    streamChatMock.mockReturnValue(completedStream(clarificationAnswer))
    const firstView = renderChat()
    await ask('I have an allergy')

    const picker = await screen.findByRole('dialog', { name: /tell us your allergy/i })
    expect(within(picker).getByRole('checkbox', { name: 'Server label A' })).toBeInTheDocument()
    expect(within(picker).getByRole('checkbox', { name: 'Server label B' })).toBeInTheDocument()
    expect(within(picker).getAllByRole('checkbox')).toHaveLength(serverOptions.length)
    expect(picker).not.toHaveTextContent(/ibuprofen|aspirin/i)

    firstView.unmount()
    sessionStorage.clear()
    streamChatMock.mockReset()
    streamChatMock.mockReturnValue(completedStream(clarificationAnswer))
    fetchMock.mockReset()
    fetchMock.mockRejectedValue(new Error('offline'))
    renderChat()
    await ask('I have another allergy')

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    expect(screen.queryByRole('dialog', { name: /tell us your allergy/i })).not.toBeInTheDocument()
    expect(screen.getByText(/ask a pharmacist if it is not listed/i)).toBeInTheDocument()
  })

  it('stores both allergen channels in this tab and sends both on the next request', async () => {
    serveAllergenOptions()
    streamChatMock
      .mockReturnValueOnce(completedStream(clarificationAnswer))
      .mockReturnValueOnce(completedStream(validAnswer))
    renderChat()
    const user = await ask('I am allergic to pain medicine')
    const picker = await screen.findByRole('dialog', { name: /tell us your allergy/i })

    await user.click(within(picker).getByRole('checkbox', { name: 'Ibuprofen' }))
    await user.type(within(picker).getByRole('combobox', { name: 'Allergy name' }), 'Yellow dye')
    await user.click(within(picker).getByRole('button', { name: 'Add allergy' }))
    await user.click(within(picker).getByRole('button', { name: 'Use selected allergies' }))

    expect(loadChatSession().allergies).toEqual(['ibuprofen'])
    expect(loadChatSession().unverifiedAllergens).toEqual(['Yellow dye'])
    expect(Object.values(localStorage).join('\n')).not.toContain('ibuprofen')
    expect(Object.values(localStorage).join('\n')).not.toContain('Yellow dye')
    // Both channels are present, and they carry different promises: ibuprofen is excluded from
    // retrieval, "Yellow dye" only warns on a name match. The placeholder says exactly that.
    expect(screen.getByRole('textbox')).toHaveAttribute(
      'placeholder',
      'Ask again — answers avoid your selected ingredients. The allergens you typed are checked by name only.',
    )

    await user.clear(screen.getByRole('textbox'))
    await user.type(screen.getByRole('textbox'), 'What can I take for this headache?')
    await user.click(screen.getByRole('button', { name: /ask/i }))
    await screen.findByText('Drink water and rest.')

    expect(streamChatMock).toHaveBeenLastCalledWith(
      [
        { role: 'user', content: 'I am allergic to pain medicine' },
        { role: 'user', content: 'What can I take for this headache?' },
      ],
      undefined,
      {
        mermaid: {
          exclude_ingredients: ['ibuprofen'],
          unverified_allergens: ['Yellow dye'],
        },
      },
    )
  })

  it('never promises to avoid an allergen it can only name-check (P1)', async () => {
    // Unverified chips alone. The backend sends these as `unverified_allergens`, which it matches by
    // name and warns on — it never excludes a product for them (§2-6: an unsigned binding may not
    // block). Telling this user "answers will avoid your selected ingredients" would promise a
    // filter that does not run, and they would read a warning card as one that had been filtered.
    serveAllergenOptions()
    streamChatMock.mockReturnValue(completedStream(clarificationAnswer))
    renderChat()
    const user = await ask('I am allergic to something')
    const picker = await screen.findByRole('dialog', { name: /tell us your allergy/i })

    await user.type(within(picker).getByRole('combobox', { name: 'Allergy name' }), 'Yellow dye')
    await user.click(within(picker).getByRole('button', { name: 'Add allergy' }))
    await user.click(within(picker).getByRole('button', { name: 'Use selected allergies' }))

    expect(loadChatSession().allergies).toEqual([])
    expect(loadChatSession().unverifiedAllergens).toEqual(['Yellow dye'])

    const placeholder = screen.getByRole('textbox').getAttribute('placeholder')
    expect(placeholder).toBe('Ask again — the allergens you typed are checked by name only, not avoided.')
    expect(placeholder).not.toMatch(/will avoid/i)
    expect(placeholder).not.toMatch(/safe/i)
  })

  it('declares an allergen typed but never Added, when the user confirms (P0)', async () => {
    // The Add button is one affordance; the confirm button is the one that says "use my allergies".
    // A person who types their allergen and reaches for the second, never noticing the first, has
    // declared it — the screen agrees with them. Dropping it would mark the clarification answered
    // and let the next request go out on a list the backend then treats as complete, showing a
    // product that contains the allergen as `no_match_found` (§2-2). Confirming reads the box.
    serveAllergenOptions()
    streamChatMock
      .mockReturnValueOnce(completedStream(clarificationAnswer))
      .mockReturnValueOnce(completedStream(validAnswer))
    renderChat()
    const user = await ask('I am allergic to something')
    const picker = await screen.findByRole('dialog', { name: /tell us your allergy/i })

    await user.click(within(picker).getByRole('checkbox', { name: 'Ibuprofen' }))
    await user.type(within(picker).getByRole('combobox', { name: 'Allergy name' }), 'Yellow dye')
    // No Add click. Straight to confirm.
    await user.click(within(picker).getByRole('button', { name: 'Use selected allergies' }))

    expect(loadChatSession().allergies).toEqual(['ibuprofen'])
    expect(loadChatSession().unverifiedAllergens).toEqual(['Yellow dye'])

    await user.type(screen.getByRole('textbox'), 'What can I take for this headache?')
    await user.click(screen.getByRole('button', { name: /ask/i }))
    await screen.findByText('Drink water and rest.')

    expect(streamChatMock).toHaveBeenLastCalledWith(
      expect.anything(),
      undefined,
      { mermaid: { exclude_ingredients: ['ibuprofen'], unverified_allergens: ['Yellow dye'] } },
    )
  })

  it('declares a typed allergen that resolves to a listed option, when the user confirms (P0)', async () => {
    serveAllergenOptions()
    streamChatMock.mockReturnValue(completedStream(clarificationAnswer))
    renderChat()
    const user = await ask('I am allergic to something')
    const picker = await screen.findByRole('dialog', { name: /tell us your allergy/i })

    await user.type(within(picker).getByRole('combobox', { name: 'Allergy name' }), 'Ibuprofen')
    await user.click(within(picker).getByRole('button', { name: 'Use selected allergies' }))

    // It matched the reviewed list, so it binds as a verified exclusion — not a name-match chip.
    expect(loadChatSession().allergies).toEqual(['ibuprofen'])
    expect(loadChatSession().unverifiedAllergens).toEqual([])
  })

  it('resolves autocomplete text to a verified option and makes an honest unverified chip removable', async () => {
    serveAllergenOptions()
    streamChatMock.mockReturnValue(completedStream(clarificationAnswer))
    renderChat()
    const user = await ask('I am allergic to something')
    const picker = await screen.findByRole('dialog', { name: /tell us your allergy/i })
    const input = within(picker).getByRole('combobox', { name: 'Allergy name' })

    await user.type(input, 'Ibuprofen')
    await user.click(within(picker).getByRole('button', { name: 'Add allergy' }))
    expect(within(picker).getByRole('checkbox', { name: 'Ibuprofen' })).toBeChecked()
    expect(within(picker).queryByText(/Ibuprofen — name-match warnings only/i)).not.toBeInTheDocument()

    await user.type(input, 'Yellow dye')
    await user.click(within(picker).getByRole('button', { name: 'Add allergy' }))
    const unverifiedChip = within(picker).getByText(/Yellow dye — name-match warnings only/i)
    expect(unverifiedChip).toBeInTheDocument()
    expect(picker).toHaveTextContent(/a pharmacist can fully check this one/i)
    expect(unverifiedChip.parentElement?.className).not.toMatch(/green|success/i)

    await user.click(within(picker).getByRole('button', { name: 'Remove Yellow dye' }))
    expect(within(picker).queryByText(/Yellow dye — name-match warnings only/i)).not.toBeInTheDocument()
  })

  it('re-opens after a second declaration pre-filled with verified selections and unverified chips', async () => {
    serveAllergenOptions()
    streamChatMock
      .mockReturnValueOnce(completedStream(clarificationAnswer))
      .mockReturnValueOnce(completedStream(clarificationAnswer))
      .mockReturnValueOnce(completedStream(validAnswer))
    renderChat()
    const user = await ask('I am allergic to ibuprofen')

    const first = await screen.findByRole('dialog', { name: /tell us your allergy/i })
    await user.click(within(first).getByRole('checkbox', { name: 'Ibuprofen' }))
    await user.type(within(first).getByRole('combobox', { name: 'Allergy name' }), 'Yellow dye')
    await user.click(within(first).getByRole('button', { name: 'Add allergy' }))
    await user.click(within(first).getByRole('button', { name: 'Use selected allergies' }))

    await user.clear(screen.getByRole('textbox'))
    await user.type(screen.getByRole('textbox'), 'I am also allergic to aspirin')
    await user.click(screen.getByRole('button', { name: /ask/i }))

    const reopened = await screen.findByRole('dialog', { name: /tell us your allergy/i })
    expect(within(reopened).getByRole('checkbox', { name: 'Ibuprofen' })).toBeChecked()
    expect(within(reopened).getByText(/Yellow dye — name-match warnings only/i)).toBeInTheDocument()
  })

  it('shows no composer and no Ask while the picker is open', async () => {
    serveAllergenOptions()
    streamChatMock.mockReturnValue(completedStream(clarificationAnswer))
    renderChat()
    await ask('I am allergic to a pain medicine')
    await screen.findByRole('dialog', { name: /tell us your allergy/i })
    expect(streamChatMock).toHaveBeenCalledTimes(1)

    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^ask$/i })).not.toBeInTheDocument()
  })

  it('reload keeps the unverifiable-allergy lock set by dismiss', async () => {
    // The lock is only ever set by "My allergy isn't listed" (dismiss). It must survive a reload:
    // a restored conversation whose lock was dropped could resume retrieval on an incomplete list.
    serveAllergenOptions()
    streamChatMock.mockReturnValue(completedStream(clarificationAnswer))
    const first = renderChat()
    const user = await ask('I am allergic to something not in the list')
    const picker = await screen.findByRole('dialog', { name: /tell us your allergy/i })
    await user.click(within(picker).getByRole('button', { name: 'Cancel' }))
    expect(loadChatSession().unverifiableAllergy).toBe(true)

    first.unmount()
    renderChat()
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(screen.getByText(/isn.t in our list/i)).toBeInTheDocument()
  })

  it('re-stating an already-covered allergy confirms without locking (P1)', async () => {
    // ibuprofen is already selected; a new question repeats the allergy, so the backend reopens
    // the clarification (current turn declares). The picker is prefilled; confirming adds no NEW
    // key, but it must proceed on the existing list — restating a known allergy must not end
    // drug lookup (that would block care for anyone who mentions their allergy again).
    sessionStorage.setItem(
      'mermaid.chatSession.v1',
      JSON.stringify({
        schemaVersion: '1.0',
        data: {
          sessionId: 's1',
          messages: [],
          allergies: ['ibuprofen'],
          unverifiedAllergens: [],
          unverifiableAllergy: false,
        },
      }),
    )
    serveAllergenOptions()
    streamChatMock.mockReturnValue(completedStream(clarificationAnswer))
    renderChat()
    const user = userEvent.setup()

    // Restated allergy → clarification → prefilled picker.
    await user.type(screen.getByRole('textbox'), 'I am allergic to ibuprofen, what can I take?')
    await user.click(screen.getByRole('button', { name: /ask/i }))
    const picker = await screen.findByRole('dialog', { name: /tell us your allergy/i })
    expect(within(picker).getByRole('checkbox', { name: 'Ibuprofen' })).toBeChecked()

    // Confirm with no change — must NOT lock.
    await user.click(within(picker).getByRole('button', { name: 'Use selected allergies' }))
    expect(loadChatSession().unverifiableAllergy).toBe(false)
    expect(screen.queryByText(/isn.t in our list/i)).not.toBeInTheDocument()
    expect(screen.getByRole('textbox')).toBeInTheDocument()
  })

  it('renders the answer-level warning so a name-only allergy check is visible (P1)', async () => {
    // The server appends its unverified-allergen caveat to answer.warnings. If the UI only renders
    // per-drug-card warnings, the caveat is invisible when no card name-matches, and a name-only
    // check reads as a full one (§2-2).
    const caveat = 'The named allergens were checked by name only; a pharmacist must confirm.'
    const answered = JSON.stringify({ ...JSON.parse(validAnswer), warnings: [caveat] })
    streamChatMock.mockReturnValue(completedStream(answered))
    renderChat()
    await ask('What can I take for a headache?')
    expect(await screen.findByText(caveat)).toBeInTheDocument()
  })

  it('restores unverified chips across a reload and sends them on the next request (P0)', async () => {
    // The "reload restores" test above ends in the unverifiable-lock state, so its notice screen
    // hides the chips and never proves they survive a reload. Here the lock is OFF: an unverified
    // chip that vanished on reload would leave its ingredient un-warned — a product containing it
    // could then read as no_match_found (§2-2). This asserts the chip is both restored AND sent.
    sessionStorage.setItem(
      'mermaid.chatSession.v1',
      JSON.stringify({
        schemaVersion: '1.0',
        data: {
          sessionId: 's1',
          messages: [],
          allergies: ['ibuprofen'],
          unverifiedAllergens: ['Yellow dye'],
          unverifiableAllergy: false,
        },
      }),
    )
    serveAllergenOptions()
    streamChatMock.mockReturnValue(completedStream(validAnswer))
    renderChat()
    const user = userEvent.setup()

    // Restored to a normal composer (not the lock notice), carrying the persisted chip.
    await user.type(screen.getByRole('textbox'), 'What can I take for a headache?')
    await user.click(screen.getByRole('button', { name: /ask/i }))
    await screen.findByText('Drink water and rest.')

    expect(streamChatMock).toHaveBeenLastCalledWith(
      expect.anything(),
      undefined,
      {
        mermaid: {
          exclude_ingredients: ['ibuprofen'],
          unverified_allergens: ['Yellow dye'],
        },
      },
    )
  })

  it('new conversation clears verified selections, unverified chips, and the lock', async () => {
    sessionStorage.setItem(
      'mermaid.chatSession.v1',
      JSON.stringify({
        schemaVersion: '1.0',
        data: {
          sessionId: 's1',
          messages: [],
          allergies: ['ibuprofen'],
          unverifiedAllergens: ['Yellow dye'],
          unverifiableAllergy: true,
        },
      }),
    )
    renderChat()
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: /start a new conversation/i }))

    expect(loadChatSession()).toEqual(
      expect.objectContaining({
        allergies: [],
        unverifiedAllergens: [],
        unverifiableAllergy: false,
      }),
    )
    expect(screen.getByRole('textbox')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit allergy list' })).not.toBeInTheDocument()
    expect(screen.getByRole('textbox')).toHaveAttribute(
      'placeholder',
      "I have a sore throat and a fever, and it's 11pm.",
    )
    expect(sessionStorage.getItem('mermaid.chatSession.v1')).toBeNull()
  })

  it('edit re-entry shows both lists and cancel without changes changes nothing', async () => {
    serveAllergenOptions()
    streamChatMock.mockReturnValue(completedStream(clarificationAnswer))
    renderChat()
    const user = await ask('I have an allergy')
    const first = await screen.findByRole('dialog', { name: /tell us your allergy/i })
    await user.click(within(first).getByRole('checkbox', { name: 'Ibuprofen' }))
    await user.type(within(first).getByRole('combobox', { name: 'Allergy name' }), 'Yellow dye')
    await user.click(within(first).getByRole('button', { name: 'Add allergy' }))
    await user.click(within(first).getByRole('button', { name: 'Use selected allergies' }))
    const before = loadChatSession()

    await user.click(screen.getByRole('button', { name: 'Edit allergy list' }))
    const edit = await screen.findByRole('dialog', { name: /tell us your allergy/i })
    expect(within(edit).getByRole('checkbox', { name: 'Ibuprofen' })).toBeChecked()
    expect(within(edit).getByText(/Yellow dye — name-match warnings only/i)).toBeInTheDocument()
    await user.click(within(edit).getByRole('button', { name: 'Cancel' }))

    expect(screen.queryByRole('dialog', { name: /tell us your allergy/i })).not.toBeInTheDocument()
    expect(loadChatSession()).toEqual(before)
  })

  it('makes a request structurally impossible in clarification and edit pending states', async () => {
    serveAllergenOptions()
    streamChatMock.mockReturnValue(completedStream(clarificationAnswer))
    renderChat()
    const user = await ask('I have an allergy')
    const clarification = await screen.findByRole('dialog', { name: /tell us your allergy/i })
    expect(screen.queryByRole('textbox', { name: 'Describe your symptoms' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^ask$/i })).not.toBeInTheDocument()
    expect(streamChatMock).toHaveBeenCalledTimes(1)

    await user.click(within(clarification).getByRole('checkbox', { name: 'Ibuprofen' }))
    await user.click(
      within(clarification).getByRole('button', { name: 'Use selected allergies' }),
    )
    await user.click(screen.getByRole('button', { name: 'Edit allergy list' }))
    await screen.findByRole('dialog', { name: /tell us your allergy/i })
    expect(screen.queryByRole('textbox', { name: 'Describe your symptoms' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^ask$/i })).not.toBeInTheDocument()
    expect(streamChatMock).toHaveBeenCalledTimes(1)
  })
})

const SESSION_COPY =
  "Your messages are sent to answer them, but this conversation is not saved: it lives only in this tab."
