import { act, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { setAppearancePreference } from './lib/storage'

const PREFERENCES_KEY = 'mermaid.preferences.v1'

// This boundary test owns main.tsx's Theme wiring, not the shell's layout observers or API clients.
vi.mock('./components/MobileShell', () => ({
  MobileShell: () => <div>App shell</div>,
}))

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  document.documentElement.removeAttribute('data-theme')
  document.documentElement.removeAttribute('data-astryx-theme')
  document.documentElement.style.removeProperty('color-scheme')
  document.body.innerHTML = '<div id="root"></div>'
})

describe('MermaidApp appearance boundary', () => {
  it('passes stored appearance to both the root color scheme and Astryx Theme mode', async () => {
    localStorage.setItem(
      PREFERENCES_KEY,
      JSON.stringify({
        schemaVersion: '1.0',
        data: { defaultRadiusM: 1000, manualLocation: null, appearance: 'dark' },
      }),
    )

    await import('./main')

    await waitFor(() => {
      expect(document.documentElement.style.colorScheme).toBe('dark')
      expect(document.documentElement).toHaveAttribute('data-theme', 'dark')
      expect(document.querySelector('[data-astryx-theme]')).toHaveAttribute('data-theme', 'dark')
    })

    act(() => setAppearancePreference('light'))

    await waitFor(() => {
      expect(document.documentElement.style.colorScheme).toBe('light')
      expect(document.documentElement).toHaveAttribute('data-theme', 'light')
      expect(document.querySelector('[data-astryx-theme]')).toHaveAttribute('data-theme', 'light')
    })

    act(() => setAppearancePreference('device'))

    await waitFor(() => {
      expect(document.documentElement.style.colorScheme).toBe('light dark')
      expect(document.documentElement).not.toHaveAttribute('data-theme')
      expect(document.querySelector('[data-astryx-theme]')).not.toHaveAttribute('data-theme')
    })
  })
})
