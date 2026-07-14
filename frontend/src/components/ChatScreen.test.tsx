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
    expect(error).toHaveTextContent(/sending the same question again will not fix/i)
    expect(screen.queryByRole('button', { name: /try again/i })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /ask/i })).toBeDisabled()
    // The id that finds the server log, for the bug report.
    expect(error).toHaveTextContent('req-42')

    // "Non-retryable" is about the unchanged request — editing the question is the recovery
    // path (INPUT_TOO_LARGE: shorten it), so the block must lift on edit.
    await user.type(screen.getByRole('textbox'), ' since yesterday')
    expect(screen.getByRole('button', { name: /ask/i })).toBeEnabled()
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

  it('stores confirmed keys in this tab and sends exactly those keys on the next request', async () => {
    serveAllergenOptions()
    streamChatMock
      .mockReturnValueOnce(completedStream(clarificationAnswer))
      .mockReturnValueOnce(completedStream(validAnswer))
    renderChat()
    const user = await ask('I am allergic to pain medicine')
    const picker = await screen.findByRole('dialog', { name: /tell us your allergy/i })

    await user.click(within(picker).getByRole('checkbox', { name: 'Ibuprofen' }))
    await user.click(
      within(picker).getByRole('checkbox', { name: 'Aspirin (acetylsalicylic acid)' }),
    )
    await user.click(within(picker).getByRole('button', { name: 'Use selected allergies' }))

    const selectedKeys = ['ibuprofen', 'acetylsalicylic-acid']
    expect(loadChatSession().allergies).toEqual(selectedKeys)
    expect(Object.values(localStorage).join('\n')).not.toContain('ibuprofen')
    expect(screen.getByRole('textbox')).toHaveAttribute(
      'placeholder',
      'Ask your question again — answers will avoid your selected ingredients.',
    )

    await user.click(screen.getByRole('button', { name: 'Edit allergy list' }))
    const reopened = await screen.findByRole('dialog', { name: /tell us your allergy/i })
    expect(within(reopened).getByRole('checkbox', { name: 'Ibuprofen' })).toBeChecked()
    expect(
      within(reopened).getByRole('checkbox', { name: 'Aspirin (acetylsalicylic acid)' }),
    ).toBeChecked()
    // Confirm (not dismiss) to close the re-opened picker — dismiss now ends drug lookup.
    await user.click(within(reopened).getByRole('button', { name: 'Use selected allergies' }))

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
      { mermaid: { exclude_ingredients: selectedKeys } },
    )
  })

  it('re-opens the picker for a second, later allergy declaration and adds to the list (P0)', async () => {
    // A user who already selected one allergy then declares a second in free text. The backend
    // returns the clarification again (FR-001), but the old guard suppressed the picker once
    // `allergies.length > 0`, leaving the stale ["ibuprofen"] list in place — the next request
    // would carry only that, and the backend, seeing a non-empty resolved list, would retrieve
    // as if the second allergen were never named. The picker must re-open, pre-filled.
    serveAllergenOptions()
    streamChatMock
      .mockReturnValueOnce(completedStream(clarificationAnswer))
      .mockReturnValueOnce(completedStream(clarificationAnswer))
      .mockReturnValueOnce(completedStream(validAnswer))
    renderChat()
    const user = await ask('I am allergic to ibuprofen')

    const first = await screen.findByRole('dialog', { name: /tell us your allergy/i })
    await user.click(within(first).getByRole('checkbox', { name: 'Ibuprofen' }))
    await user.click(within(first).getByRole('button', { name: 'Use selected allergies' }))
    expect(loadChatSession().allergies).toEqual(['ibuprofen'])
    // Confirming answered THIS clarification, so the picker closes (it is still the latest answer).
    expect(screen.queryByRole('dialog', { name: /tell us your allergy/i })).not.toBeInTheDocument()

    await user.clear(screen.getByRole('textbox'))
    await user.type(screen.getByRole('textbox'), 'I am also allergic to aspirin')
    await user.click(screen.getByRole('button', { name: /ask/i }))

    const reopened = await screen.findByRole('dialog', { name: /tell us your allergy/i })
    expect(within(reopened).getByRole('checkbox', { name: 'Ibuprofen' })).toBeChecked()
    await user.click(
      within(reopened).getByRole('checkbox', { name: 'Aspirin (acetylsalicylic acid)' }),
    )
    await user.click(within(reopened).getByRole('button', { name: 'Use selected allergies' }))

    const bothKeys = ['ibuprofen', 'acetylsalicylic-acid']
    expect(loadChatSession().allergies).toEqual(bothKeys)

    await user.clear(screen.getByRole('textbox'))
    await user.type(screen.getByRole('textbox'), 'What can I take?')
    await user.click(screen.getByRole('button', { name: /ask/i }))
    await screen.findByText('Drink water and rest.')

    expect(streamChatMock).toHaveBeenLastCalledWith(expect.anything(), undefined, {
      mermaid: { exclude_ingredients: bothKeys },
    })
  })

  it('replaces the composer with the picker, so no request can be sent before selecting (P0)', async () => {
    // Re-opening the picker is not enough on its own: a user could ignore an overlay, type a
    // fresh keyword-free question, and press Ask, sending the STALE exclude_ingredients. The
    // picker takes the composer's PLACE — with no composer there is no Ask, so the stale-send
    // path does not exist while a selection is pending.
    serveAllergenOptions()
    streamChatMock.mockReturnValue(completedStream(clarificationAnswer))
    renderChat()
    await ask('I am allergic to a pain medicine')
    await screen.findByRole('dialog', { name: /tell us your allergy/i })
    expect(streamChatMock).toHaveBeenCalledTimes(1)

    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^ask$/i })).not.toBeInTheDocument()
  })

  it('ends drug lookup for the conversation when the declared allergy is unlisted (P0)', async () => {
    // "My allergy isn't listed" is a declaration of an allergen we cannot bind. Keeping the old
    // selected keys and letting the next question through would treat the unlisted allergen as
    // absent and could show a product containing it as no_match_found. So drug lookup ends: the
    // composer is replaced by a pharmacist notice, and only a new conversation resets it.
    serveAllergenOptions()
    streamChatMock.mockReturnValue(completedStream(clarificationAnswer))
    renderChat()
    const user = await ask('I am allergic to something not in the list')
    const picker = await screen.findByRole('dialog', { name: /tell us your allergy/i })

    await user.click(within(picker).getByRole('button', { name: "My allergy isn't listed" }))

    // No composer to send a stale list from; a pharmacist notice and a reset are the only paths.
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(screen.getByText(/isn.t in our list/i)).toBeInTheDocument()
    expect(streamChatMock).toHaveBeenCalledTimes(1)

    // A new conversation clears the block and restores the composer.
    await user.click(screen.getByRole('button', { name: /start a new conversation/i }))
    expect(screen.getByRole('textbox')).toBeInTheDocument()
    expect(screen.queryByText(/isn.t in our list/i)).not.toBeInTheDocument()
  })
})

const SESSION_COPY =
  "Your messages are sent to answer them, but this conversation is not saved: it lives only in this tab."
