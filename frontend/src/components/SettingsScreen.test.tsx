import { StrictMode } from 'react'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { ChatProvider, useChatSession } from '../lib/chatSession'
import {
  loadAllergyMemory,
  loadChatSession,
  loadPreferences,
  saveAllergyMemory,
  saveChatSession,
  subscribeAllergyMemory,
  type ChatSession,
} from '../lib/storage'
import { SettingsScreen } from './SettingsScreen'

const ALLERGY_MEMORY_KEY = 'mermaid.allergyMemory.v1'
const PREFERENCES_KEY = 'mermaid.preferences.v1'

const originalShowModal = HTMLDialogElement.prototype.showModal
const originalClose = HTMLDialogElement.prototype.close

beforeAll(() => {
  HTMLDialogElement.prototype.showModal = function showModal() {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.close = function close() {
    this.removeAttribute('open')
  }
})

afterAll(() => {
  if (originalShowModal) HTMLDialogElement.prototype.showModal = originalShowModal
  else delete (HTMLDialogElement.prototype as Partial<HTMLDialogElement>).showModal
  if (originalClose) HTMLDialogElement.prototype.close = originalClose
  else delete (HTMLDialogElement.prototype as Partial<HTMLDialogElement>).close
})

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  document.documentElement.style.removeProperty('color-scheme')
  document.documentElement.removeAttribute('data-theme')
})

function sessionWith(
  allergies: string[] = [],
  unverifiedAllergens: string[] = [],
): ChatSession {
  return {
    sessionId: 'settings-test-session',
    messages: [],
    allergies,
    unverifiedAllergens,
    unverifiableAllergy: false,
    pendingQuestion: '',
    allergiesConfirmedAt: '',
  }
}

function SessionControls() {
  const { confirmAllergies, newConversation } = useChatSession()
  return (
    <>
      <button
        type="button"
        onClick={() => confirmAllergies(['aspirin'], ['Yellow dye'], false)}
      >
        Replace allergy list for test
      </button>
      <button type="button" onClick={newConversation}>
        Start new conversation for test
      </button>
      <button
        type="button"
        onClick={() => confirmAllergies(['ibuprofen', 'naproxen'], [], false)}
      >
        Confirm concurrent list for test
      </button>
      <button type="button" onClick={() => confirmAllergies([], [], false)}>
        Clear allergy list for test
      </button>
    </>
  )
}

function SettingsTestTree({ withSessionControls = false }: { withSessionControls?: boolean }) {
  return (
    <ChatProvider>
      <SettingsScreen />
      {withSessionControls ? <SessionControls /> : null}
    </ChatProvider>
  )
}

function renderSettings(withSessionControls = false, strict = false) {
  const tree = <SettingsTestTree withSessionControls={withSessionControls} />
  return render(strict ? <StrictMode>{tree}</StrictMode> : tree)
}

describe('SettingsScreen', () => {
  it('offers three appearance states and changes the computed root color scheme (SC-001)', async () => {
    const user = userEvent.setup()
    renderSettings()

    const appearance = screen.getByRole('radiogroup', { name: 'Appearance' })
    expect(within(appearance).getByRole('radio', { name: 'Light' })).toBeInTheDocument()
    expect(within(appearance).getByRole('radio', { name: 'Dark' })).toBeInTheDocument()
    expect(within(appearance).getByRole('radio', { name: 'Follow my device' })).toHaveAttribute(
      'aria-checked',
      'true',
    )

    await user.click(within(appearance).getByRole('radio', { name: 'Dark' }))
    expect(getComputedStyle(document.documentElement).colorScheme).toBe('dark')
    expect(loadPreferences().appearance).toBe('dark')

    await user.click(within(appearance).getByRole('radio', { name: 'Light' }))
    expect(getComputedStyle(document.documentElement).colorScheme).toBe('light')
    expect(loadPreferences().appearance).toBe('light')

    await user.click(within(appearance).getByRole('radio', { name: 'Follow my device' }))
    expect(getComputedStyle(document.documentElement).colorScheme).toBe('light dark')
    expect(loadPreferences().appearance).toBe('device')
  })

  it('defaults allergy memory to off without creating its storage key (SC-002)', () => {
    renderSettings()

    const memorySwitch = screen.getByRole('switch', { name: 'Remember my allergies' })
    expect(memorySwitch).not.toBeChecked()
    expect(memorySwitch).toHaveAccessibleDescription(
      expect.stringMatching(/Off — your allergy list.*sent with each question/),
    )
    expect(screen.getByText(/^Off — your allergy list/)).toBeInTheDocument()
    expect(localStorage.getItem(ALLERGY_MEMORY_KEY)).toBeNull()
  })

  it('records an explicit opt-in even when the current reviewed list is empty', async () => {
    const user = userEvent.setup()
    renderSettings()

    await user.click(screen.getByRole('switch', { name: 'Remember my allergies' }))

    expect(screen.getByRole('switch', { name: 'Remember my allergies' })).toBeChecked()
    expect(loadAllergyMemory()).toEqual([])
    expect(localStorage.getItem(ALLERGY_MEMORY_KEY)).not.toBeNull()
  })

  it('persists reviewed ingredient keys while typed names stay name-match-only in this session', async () => {
    saveChatSession(sessionWith(['ibuprofen', 'aspirin'], ['Yellow dye']))
    const user = userEvent.setup()
    renderSettings()
    const savedSnapshots: Array<string[] | null> = []
    const unsubscribe = subscribeAllergyMemory(() => savedSnapshots.push(loadAllergyMemory()))

    expect(screen.getByLabelText('Selected allergy ingredients')).toHaveTextContent(
      'ibuprofen · aspirin (this session only)',
    )
    const nameMatchOnly = screen.getByLabelText('Name-match-only allergies')
    expect(nameMatchOnly).toHaveTextContent(
      'Yellow dye — name-match warnings only — a pharmacist can fully check this one (this session only)',
    )
    expect(nameMatchOnly).not.toHaveTextContent(/avoided/i)

    await user.click(screen.getByRole('switch', { name: 'Remember my allergies' }))
    unsubscribe()

    expect(savedSnapshots).toEqual([['ibuprofen', 'aspirin']])
    expect(loadAllergyMemory()).toEqual(['ibuprofen', 'aspirin'])
    expect(localStorage.getItem(ALLERGY_MEMORY_KEY)).not.toContain('Yellow dye')
    expect(screen.getByText(/^On — your allergy list/)).toBeInTheDocument()
    expect(screen.getByLabelText('Name-match-only allergies')).toHaveTextContent(
      '(this session only)',
    )
  })

  it('cancels forgetting without moving the switch or changing storage (SC-003)', async () => {
    saveChatSession(sessionWith(['ibuprofen', 'aspirin']))
    const user = userEvent.setup()
    renderSettings()
    const memorySwitch = screen.getByRole('switch', { name: 'Remember my allergies' })

    await user.click(memorySwitch)
    const storedBefore = localStorage.getItem(ALLERGY_MEMORY_KEY)
    await user.click(memorySwitch)

    const dialog = screen.getByRole('alertdialog', { name: 'Forget your allergy list?' })
    expect(memorySwitch).toBeChecked()
    expect(dialog).toHaveTextContent(
      'We will delete ibuprofen and aspirin from this device. You can tell us again at any time.',
    )

    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }))

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(memorySwitch).toBeChecked()
    expect(localStorage.getItem(ALLERGY_MEMORY_KEY)).toBe(storedBefore)
  })

  it('confirms forgetting by deleting only allergy memory before settling off (SC-003)', async () => {
    saveChatSession({
      ...sessionWith(['ibuprofen', 'aspirin'], ['Yellow dye']),
      messages: [
        { id: 'm1', role: 'user', content: 'headache', createdAt: '2026-07-15T00:00:00Z' },
      ],
      unverifiableAllergy: true,
      pendingQuestion: 'my throat is swelling',
      allergiesConfirmedAt: '2026-07-15T00:01:00Z',
    })
    const sessionBefore = sessionStorage.getItem('mermaid.chatSession.v2')
    const user = userEvent.setup()
    renderSettings()
    const memorySwitch = screen.getByRole('switch', { name: 'Remember my allergies' })

    await user.click(screen.getByRole('radio', { name: 'Dark' }))
    await user.click(memorySwitch)
    await user.click(memorySwitch)
    await user.click(
      within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Confirm' }),
    )

    expect(localStorage.getItem(ALLERGY_MEMORY_KEY)).toBeNull()
    expect(localStorage.getItem(PREFERENCES_KEY)).not.toBeNull()
    expect(loadPreferences().appearance).toBe('dark')
    expect(memorySwitch).not.toBeChecked()
    expect(loadChatSession().allergies).toEqual(['ibuprofen', 'aspirin'])
    expect(sessionStorage.getItem('mermaid.chatSession.v2')).toBe(sessionBefore)
    expect(screen.getByLabelText('Selected allergy ingredients')).toHaveTextContent(
      '(this session only)',
    )
  })

  it('restores only remembered ingredient keys into a fresh tab', async () => {
    saveAllergyMemory(['ibuprofen'])
    renderSettings()

    await waitFor(() => expect(loadChatSession().allergies).toEqual(['ibuprofen']))
    expect(loadChatSession().unverifiedAllergens).toEqual([])
    expect(loadChatSession().allergiesConfirmedAt).toBe('')
    expect(screen.getByRole('switch', { name: 'Remember my allergies' })).toBeChecked()
  })

  it('does not let StrictMode overwrite remembered keys with the pre-restore empty state', async () => {
    saveAllergyMemory(['ibuprofen'])
    const setItem = vi.spyOn(localStorage, 'setItem')
    renderSettings(false, true)

    await waitFor(() => expect(loadChatSession().allergies).toEqual(['ibuprofen']))
    expect(loadAllergyMemory()).toEqual(['ibuprofen'])
    const allergyWrites = setItem.mock.calls
      .filter(([key]) => key === ALLERGY_MEMORY_KEY)
      .map(([, value]) => value)
    expect(allergyWrites).not.toContain(
      JSON.stringify({ schemaVersion: '1.0', data: [] }),
    )
  })

  it('merges a list enabled elsewhere into a previously open tab without persisting typed names', async () => {
    saveChatSession(sessionWith([], ['Yellow dye']))
    renderSettings()

    saveAllergyMemory(['ibuprofen'])

    await waitFor(() => {
      expect(screen.getByRole('switch', { name: 'Remember my allergies' })).toBeChecked()
    })
    expect(loadAllergyMemory()).toEqual(['ibuprofen'])
    expect(loadChatSession().allergies).toEqual(['ibuprofen'])
    expect(loadChatSession().unverifiedAllergens).toEqual(['Yellow dye'])
    expect(screen.getByLabelText('Selected allergy ingredients')).toHaveTextContent('ibuprofen')
    expect(localStorage.getItem(ALLERGY_MEMORY_KEY)).not.toContain('Yellow dye')
  })

  it('merges reviewed keys when another tab changes an already enabled list', async () => {
    saveAllergyMemory([])
    saveChatSession(sessionWith(['aspirin'], ['Yellow dye']))
    const firstRender = renderSettings()

    await waitFor(() => expect(loadChatSession().allergies).toEqual(['aspirin']))
    expect(loadAllergyMemory()).toEqual([])
    localStorage.setItem(
      ALLERGY_MEMORY_KEY,
      JSON.stringify({ schemaVersion: '1.0', data: ['ibuprofen'] }),
    )
    window.dispatchEvent(
      new StorageEvent('storage', { key: ALLERGY_MEMORY_KEY, storageArea: localStorage }),
    )

    await waitFor(() => {
      expect(loadChatSession().allergies).toEqual(['ibuprofen', 'aspirin'])
    })
    expect(loadChatSession().unverifiedAllergens).toEqual(['Yellow dye'])
    expect(loadAllergyMemory()).toEqual(['ibuprofen'])
    expect(localStorage.getItem(ALLERGY_MEMORY_KEY)).not.toContain('Yellow dye')
    expect(screen.getByLabelText('Selected allergy ingredients')).toHaveTextContent(
      'ibuprofen · aspirin (this session only)',
    )

    firstRender.unmount()
    sessionStorage.clear()
    renderSettings()

    await waitFor(() => expect(loadChatSession().allergies).toEqual(['ibuprofen']))
    expect(loadChatSession().unverifiedAllergens).toEqual([])
    expect(screen.getByLabelText('Selected allergy ingredients')).toHaveTextContent('ibuprofen')
    expect(screen.getByLabelText('Selected allergy ingredients')).not.toHaveTextContent(
      'this session only',
    )
  })

  it('preserves a remote addition when its storage event arrives after a local confirmation', async () => {
    saveAllergyMemory(['ibuprofen'])
    saveChatSession(sessionWith(['ibuprofen']))
    const user = userEvent.setup()
    renderSettings(true)

    // Another tab has written aspirin, but its storage event is still queued in this tab.
    localStorage.setItem(
      ALLERGY_MEMORY_KEY,
      JSON.stringify({ schemaVersion: '1.0', data: ['ibuprofen', 'aspirin'] }),
    )
    await user.click(screen.getByRole('button', { name: 'Confirm concurrent list for test' }))

    await waitFor(() => {
      expect(loadAllergyMemory()).toEqual(['ibuprofen', 'naproxen', 'aspirin'])
    })
    expect(loadChatSession().allergies).toEqual(['ibuprofen', 'naproxen', 'aspirin'])

    window.dispatchEvent(
      new StorageEvent('storage', { key: ALLERGY_MEMORY_KEY, storageArea: localStorage }),
    )
    expect(loadAllergyMemory()).toEqual(['ibuprofen', 'naproxen', 'aspirin'])
  })

  it('does not resurrect a reviewed key removed by another tab, including after reload', async () => {
    saveAllergyMemory(['ibuprofen'])
    saveChatSession(sessionWith(['ibuprofen']))
    const firstRender = renderSettings()

    localStorage.setItem(
      ALLERGY_MEMORY_KEY,
      JSON.stringify({ schemaVersion: '1.0', data: [] }),
    )
    window.dispatchEvent(
      new StorageEvent('storage', { key: ALLERGY_MEMORY_KEY, storageArea: localStorage }),
    )

    await waitFor(() => expect(loadAllergyMemory()).toEqual([]))
    expect(loadChatSession().allergies).toEqual(['ibuprofen'])
    expect(screen.getByRole('switch', { name: 'Remember my allergies' })).toBeChecked()

    firstRender.unmount()
    renderSettings()

    await waitFor(() => expect(loadChatSession().allergies).toEqual(['ibuprofen']))
    expect(loadAllergyMemory()).toEqual([])
  })

  it('merges an existing session on mount without overwriting the current device copy', async () => {
    saveAllergyMemory(['ibuprofen'])
    saveChatSession(sessionWith(['aspirin']))
    renderSettings()

    await waitFor(() => {
      expect(loadChatSession().allergies).toEqual(['ibuprofen', 'aspirin'])
    })
    expect(loadAllergyMemory()).toEqual(['ibuprofen'])
  })

  it('keeps the device copy aligned with chat edits and restores it after a new conversation', async () => {
    saveAllergyMemory(['ibuprofen'])
    saveChatSession(sessionWith(['ibuprofen']))
    const user = userEvent.setup()
    renderSettings(true)

    await user.click(screen.getByRole('button', { name: 'Replace allergy list for test' }))
    await waitFor(() => expect(loadAllergyMemory()).toEqual(['aspirin']))
    expect(localStorage.getItem(ALLERGY_MEMORY_KEY)).not.toContain('Yellow dye')

    await user.click(screen.getByRole('button', { name: 'Start new conversation for test' }))
    await waitFor(() => expect(loadChatSession().allergies).toEqual(['aspirin']))
    expect(loadChatSession().unverifiedAllergens).toEqual([])
  })

  it('lets a local chat edit remove the last remembered reviewed key', async () => {
    saveAllergyMemory(['ibuprofen'])
    saveChatSession(sessionWith(['ibuprofen']))
    const user = userEvent.setup()
    renderSettings(true)

    await user.click(screen.getByRole('button', { name: 'Clear allergy list for test' }))

    await waitFor(() => expect(loadAllergyMemory()).toEqual([]))
    expect(loadChatSession().allergies).toEqual([])
  })

  it('never uses the word safe in default, remembered, or confirmation states (SC-004)', async () => {
    saveChatSession(sessionWith(['ibuprofen'], ['Yellow dye']))
    const user = userEvent.setup()
    const { container } = renderSettings()

    expect(container).not.toHaveTextContent(/\bsafe\b/i)
    const memorySwitch = screen.getByRole('switch', { name: 'Remember my allergies' })
    await user.click(memorySwitch)
    expect(container).not.toHaveTextContent(/\bsafe\b/i)
    await user.click(memorySwitch)
    expect(screen.getByRole('alertdialog')).not.toHaveTextContent(/\bsafe\b/i)
  })

  it('keeps language future work and retains the official sources and non-diagnosis copy', () => {
    renderSettings()

    const languageSection = screen.getByRole('heading', { name: 'Language' }).closest('section')
    expect(languageSection).not.toBeNull()
    expect(within(languageSection!).getByText('English')).toBeInTheDocument()
    expect(within(languageSection!).getByText('한국어')).toHaveAttribute('lang', 'ko')
    expect(within(languageSection!).getByText(/coming later/i)).toBeInTheDocument()

    for (const agency of ['식약처', '심평원', '국립중앙의료원']) {
      expect(screen.getByText(agency)).toHaveAttribute('lang', 'ko')
    }
    expect(screen.getByText(/this app informs — it never diagnoses/i)).toBeInTheDocument()
  })
})
