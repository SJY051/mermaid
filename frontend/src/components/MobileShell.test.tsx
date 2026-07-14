import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MobileShell } from './MobileShell'

// jsdom has no layout engine, so astryx's scroll-following ResizeObserver needs a no-op browser
// boundary in component tests. The observer's behavior belongs to the kit; these tests own shell.
vi.stubGlobal(
  'ResizeObserver',
  class {
    observe() {}
    unobserve() {}
    disconnect() {}
  },
)

const streamChatMock = vi.hoisted(() => vi.fn())
vi.mock('../lib/openaiClient', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../lib/openaiClient')>()
  return { ...actual, streamChat: streamChatMock }
})

function pendingStream() {
  let release!: (text: string) => void
  const gate = new Promise<string>((resolve) => {
    release = resolve
  })
  async function* stream() {
    yield await gate
  }
  return { stream, release }
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

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
})

afterEach(() => {
  streamChatMock.mockReset()
})

/**
 * What must never regress: native tab controls preserve every mounted screen, and the canonical
 * safety disclaimer remains visible regardless of navigation.
 */
describe('MobileShell', () => {
  it('renders four real tab buttons and marks Chat as the current page', () => {
    render(<MobileShell />)

    const tabs = screen.getAllByRole('button', { name: /^(Chat|Map|Saved|Settings)$/ })
    expect(tabs).toHaveLength(4)
    tabs.forEach((tab) => {
      expect(tab.tagName).toBe('BUTTON')
      expect(tab).toHaveAttribute('type', 'button')
    })
    expect(screen.getByRole('button', { name: 'Chat' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('button', { name: 'Map' })).not.toHaveAttribute('aria-current')
  })

  it('defaults to Chat and switches screens by hiding rather than unmounting them', async () => {
    const user = userEvent.setup()
    const { container } = render(<MobileShell />)

    const chat = container.querySelector('section[aria-label="Chat screen"]')
    const map = container.querySelector('section[aria-label="Map screen"]')
    const saved = container.querySelector('section[aria-label="Saved screen"]')
    const settings = container.querySelector('section[aria-label="Settings screen"]')
    const sections = { Chat: chat, Map: map, Saved: saved, Settings: settings }

    function expectActive(label: keyof typeof sections) {
      for (const [name, section] of Object.entries(sections)) {
        if (name === label) {
          expect(section).not.toHaveAttribute('hidden')
          expect(screen.getByRole('button', { name })).toHaveAttribute('aria-current', 'page')
        } else {
          expect(section).toHaveAttribute('hidden')
          expect(screen.getByRole('button', { name })).not.toHaveAttribute('aria-current')
        }
      }
    }

    expectActive('Chat')

    await user.click(screen.getByRole('button', { name: 'Map' }))
    expect(screen.getByText('Nearby pharmacies and hospitals will appear here.')).toBeVisible()
    expectActive('Map')

    await user.click(screen.getByRole('button', { name: 'Saved' }))
    expect(screen.getByText('Saved places are stored in your anonymous profile. This device keeps a display copy.')).toBeVisible()
    expectActive('Saved')

    await user.click(screen.getByRole('button', { name: 'Settings' }))
    expect(screen.getByText('Dark mode follows your device for now.')).toBeVisible()
    expectActive('Settings')
  })

  it('bounds the shell to a handheld width, tab bar and disclaimer included', () => {
    // jsdom has no layout engine, so this asserts the bound EXISTS rather than measuring it — the
    // widths themselves are checked in a browser (spec 007 SC-001: 320, 390, 768, 1600). What it
    // catches is the bound being dropped, which is how a phone UI quietly becomes a stretched web
    // page again. It lives on the shell so no screen — nor the tab bar, nor the disclaimer strip —
    // can opt out of it by accident.
    render(<MobileShell />)
    const shell = screen.getByTestId('app-shell')

    // The bound is asserted by NAME, not by the substring `max-w-`: `max-w-full` and `max-w-none`
    // both match that substring and both put the app back across a 1600px monitor. The class is the
    // contract (SC-001 measures the pixels in a browser).
    expect(shell.className).toMatch(/\bmax-w-3xl\b/)
    expect(shell.className).not.toMatch(/\bmax-w-(full|none)\b/)
    expect(shell.parentElement?.className).toMatch(/justify-center/)
    expect(shell).toContainElement(screen.getByRole('button', { name: 'Chat' }))
    expect(shell.textContent).toContain('General information, not medical advice')
  })

  it('keeps the disclaimer visible on every tab', async () => {
    const user = userEvent.setup()
    render(<MobileShell />)
    const copy = 'General information, not medical advice · Emergency? Call 119'

    for (const tab of ['Chat', 'Map', 'Saved', 'Settings']) {
      await user.click(screen.getByRole('button', { name: tab }))
      expect(screen.getByText(copy)).toBeVisible()
    }
  })

  it('marks the shell content as English', () => {
    const { container } = render(<MobileShell />)

    expect(container.firstElementChild).toHaveAttribute('lang', 'en')
  })

  it("marks the Chat tab as busy while an answer is in progress", async () => {
    const pending = pendingStream()
    streamChatMock.mockReturnValue(pending.stream())
    const user = userEvent.setup()
    render(<MobileShell />)

    await user.type(screen.getByRole('textbox'), 'I have a headache')
    await user.click(screen.getByRole('button', { name: 'Ask' }))

    expect(
      screen.getByRole('button', { name: 'Chat — answer in progress' }),
    ).toBeInTheDocument()

    pending.release(validAnswer)
    await waitFor(() =>
      expect(
        screen.queryByRole('button', { name: 'Chat — answer in progress' }),
      ).not.toBeInTheDocument(),
    )
  })

  it('surfaces an emergency answer over another tab and offers one tap back to Chat', async () => {
    const emergency = JSON.parse(validAnswer)
    emergency.urgency = {
      level: 'emergency',
      title: 'Call 119 now',
      message: 'Your symptoms need immediate care.',
      reasonCodes: [],
      actions: [],
    }
    const pending = pendingStream()
    streamChatMock.mockReturnValue(pending.stream())
    const user = userEvent.setup()
    render(<MobileShell />)

    await user.type(screen.getByRole('textbox'), 'crushing chest pain')
    await user.click(screen.getByRole('button', { name: 'Ask' }))
    await user.click(screen.getByRole('button', { name: 'Map' }))
    pending.release(JSON.stringify(emergency))

    const alert = await screen.findByTestId('shell-emergency-alert')
    expect(alert).toHaveAttribute('role', 'alert')
    expect(alert).toHaveTextContent('Call 119 now')
    expect(within(alert).getByRole('link', { name: 'Call 119' })).toHaveAttribute('href', 'tel:119')

    await user.click(within(alert).getByRole('button', { name: 'Open Chat' }))
    expect(screen.getByRole('button', { name: 'Chat' })).toHaveAttribute('aria-current', 'page')
    expect(screen.queryByTestId('shell-emergency-alert')).not.toBeInTheDocument()
  })

  it('keeps the canonical disclaimer visible with an answered conversation (SA-02)', async () => {
    streamChatMock.mockReturnValue(completedStream(validAnswer))
    const user = userEvent.setup()
    render(<MobileShell />)

    await user.type(screen.getByRole('textbox'), 'I have a headache')
    await user.click(screen.getByRole('button', { name: 'Ask' }))

    expect(await screen.findByText('Drink water and rest.')).toBeInTheDocument()
    expect(
      screen.getByText('General information, not medical advice · Emergency? Call 119'),
    ).toBeVisible()
  })
})
