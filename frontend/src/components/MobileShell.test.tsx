import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { MobileShell } from './MobileShell'

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
    expect(screen.getByText('Your saved places will appear here. They stay on this device.')).toBeVisible()
    expectActive('Saved')

    await user.click(screen.getByRole('button', { name: 'Settings' }))
    expect(screen.getByText('Dark mode follows your device for now.')).toBeVisible()
    expectActive('Settings')
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
})
