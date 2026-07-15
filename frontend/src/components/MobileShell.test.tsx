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
  it('renders four real tab buttons with decorative Lucide icons and marks Chat as current', () => {
    render(<MobileShell />)

    const expectedIcons = {
      Chat: 'lucide-message-circle',
      Map: 'lucide-map-pin',
      Saved: 'lucide-bookmark',
      Settings: 'lucide-settings',
    }
    const tabs = screen.getAllByRole('button', { name: /^(Chat|Map|Saved|Settings)$/ })
    expect(tabs).toHaveLength(4)
    for (const [label, iconClass] of Object.entries(expectedIcons)) {
      const tab = screen.getByRole('button', { name: label })
      expect(tab.tagName).toBe('BUTTON')
      expect(tab).toHaveAttribute('type', 'button')
      const icon = tab.querySelector('svg')
      expect(icon).toHaveClass(iconClass)
      expect(tab.firstElementChild).toBe(icon)
      expect(icon).toHaveAttribute('aria-hidden', 'true')
      expect(icon).toHaveAttribute('width', '19')
      expect(icon).toHaveAttribute('height', '19')
    }
    expect(screen.getByRole('button', { name: 'Chat' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('button', { name: 'Chat' })).toHaveClass(
      'font-bold',
      'text-primary',
    )
    expect(screen.getByRole('button', { name: 'Map' })).not.toHaveAttribute('aria-current')
    expect(screen.getByRole('button', { name: 'Map' })).toHaveClass('text-secondary')
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
          expect(screen.getByRole('button', { name }))
            .toHaveAttribute('aria-current', 'page')
          expect(screen.getByRole('button', { name })).toHaveClass('font-bold', 'text-primary')
        } else {
          expect(section).toHaveAttribute('hidden')
          expect(screen.getByRole('button', { name })).not.toHaveAttribute('aria-current')
          expect(screen.getByRole('button', { name })).toHaveClass('text-secondary')
        }
      }
    }

    expectActive('Chat')

    await user.click(screen.getByRole('button', { name: 'Map' }))
    expect(screen.getByRole('heading', { name: 'Map' })).toBeVisible()
    expect(
      screen.queryByText('Nearby pharmacies and hospitals will appear here.'),
    ).not.toBeInTheDocument()
    expectActive('Map')

    await user.click(screen.getByRole('button', { name: 'Saved' }))
    expect(screen.getByRole('heading', { name: 'Saved' })).toBeVisible()
    // The storage/transmission line is what the Saved tab must never get wrong (it says the places
    // go to the profile and the device keeps a display copy), so the shell test keeps asserting it.
    expect(
      screen.getByText(
        'Saved places are stored in your anonymous profile. This device keeps a display copy.',
      ),
    ).toBeVisible()
    expectActive('Saved')

    await user.click(screen.getByRole('button', { name: 'Settings' }))
    expect(screen.getByRole('heading', { name: 'Settings' })).toBeVisible()
    expectActive('Settings')
  })

  it('does not load the saved-places profile until the tab is opened', async () => {
    // Every screen stays mounted so the chat survives a tab switch — which means SavedScreen exists
    // from the first paint, and a profile request would go out before anyone had asked for one. The
    // `active` prop is what stops it. Nothing guarded that until this test: flipping it to a constant
    // `true` left the whole suite green, so the shell could have started fetching on load again
    // without anyone noticing.
    // Answer by URL. Every screen is mounted in this shell, so a stub that returns the profile shape
    // to everyone hands MapScreen an object where it expects an array of facilities — which surfaces
    // as an unhandled rejection and a runner exit code of 1 while every test still reports green.
    const fetchMock = vi.fn(async (url: unknown) =>
      String(url).includes('/profiles/')
        ? { ok: true, status: 200, json: async () => ({ favorites: [] }) }
        : { ok: true, status: 200, json: async () => [] },
    )
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    render(<MobileShell />)

    const profileCalls = () =>
      fetchMock.mock.calls.filter((call) => String(call[0]).includes('/profiles/'))
    expect(profileCalls()).toHaveLength(0)

    await user.click(screen.getByRole('button', { name: 'Saved' }))
    await waitFor(() => expect(profileCalls().length).toBeGreaterThan(0))
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

  it('gives every tab its own scroll box, not one shared with the others (P1)', () => {
    // A single scroll container on the shell wrapper would carry one tab's scroll position over to
    // the next: read a long answer, open Map, and land halfway down a map. #78 introduced exactly
    // that while adding the width bound, and its comment still promised per-tab scroll. Each section
    // owns its box; the wrapper owns none.
    render(<MobileShell />)

    const shell = screen.getByTestId('app-shell')
    const wrapper = shell.querySelector('.min-h-0.flex-1')!
    expect(wrapper.className).not.toMatch(/overflow-y-auto/)
    for (const label of ['Chat screen', 'Map screen', 'Saved screen', 'Settings screen']) {
      expect(screen.getByLabelText(label).className).toMatch(/overflow-y-auto/)
    }
  })

  it('keeps exactly one disclaimer visible on every tab', async () => {
    const user = userEvent.setup()
    render(<MobileShell />)
    const copy = 'General information, not medical advice · Emergency? Call 119'

    for (const tab of ['Chat', 'Map', 'Saved', 'Settings']) {
      await user.click(screen.getByRole('button', { name: tab }))
      const disclaimers = screen.getAllByText(copy)
      expect(disclaimers).toHaveLength(1)
      expect(disclaimers[0]).toBeVisible()
    }
  })

  it('puts the Chat disclaimer before the composer and keeps both before the tab bar', () => {
    render(<MobileShell />)

    const disclaimer = screen.getByText(
      'General information, not medical advice · Emergency? Call 119',
    )
    const textbox = screen.getByRole('textbox', { name: 'Describe your symptoms' })
    const tabBar = screen.getByRole('navigation', { name: 'Main' })

    expect(disclaimer.compareDocumentPosition(textbox)).toBe(
      Node.DOCUMENT_POSITION_FOLLOWING,
    )
    expect(textbox.compareDocumentPosition(tabBar)).toBe(
      Node.DOCUMENT_POSITION_FOLLOWING,
    )
  })

  it('keeps each screen scroll position independent', async () => {
    const user = userEvent.setup()
    const { container } = render(<MobileShell />)
    const chat = container.querySelector<HTMLElement>('section[aria-label="Chat screen"]')!
    const saved = container.querySelector<HTMLElement>('section[aria-label="Saved screen"]')!

    expect(chat).toHaveClass('h-full', 'overflow-y-auto')
    expect(saved).toHaveClass('h-full', 'overflow-y-auto')
    expect(chat.parentElement).not.toHaveClass('overflow-y-auto')

    chat.scrollTop = 320
    await user.click(screen.getByRole('button', { name: 'Saved' }))
    expect(saved.scrollTop).toBe(0)

    saved.scrollTop = 48
    await user.click(screen.getByRole('button', { name: 'Chat' }))
    expect(chat.scrollTop).toBe(320)

    await user.click(screen.getByRole('button', { name: 'Saved' }))
    expect(saved.scrollTop).toBe(48)
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
    const callLink = within(alert).getByRole('link', { name: 'Call 119' })
    expect(callLink).toHaveAttribute('href', 'tel:119')
    const phoneIcon = callLink.querySelector('svg')
    expect(phoneIcon).toHaveClass('lucide-phone')
    expect(phoneIcon).toHaveAttribute('aria-hidden', 'true')
    expect(phoneIcon).toHaveAttribute('width', '16')
    expect(phoneIcon).toHaveAttribute('height', '16')

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
