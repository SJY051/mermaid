import { render, screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import { SettingsScreen } from './SettingsScreen'

beforeEach(() => {
  localStorage.clear()
})

describe('SettingsScreen', () => {
  it('keeps appearance disabled until the design system supports dark tokens', () => {
    render(<SettingsScreen />)

    const appearance = screen.getByRole('switch', { name: 'Appearance' })
    expect(appearance).toHaveAttribute('aria-disabled', 'true')
    expect(appearance).not.toBeChecked()
    expect(screen.getByText(/follows your device/i)).toBeInTheDocument()
    expect(
      screen.getByText('Follows your device. Manual appearance controls are not available yet.'),
    ).toBeInTheDocument()
  })

  it('shows English as the current language and marks Korean as future work', () => {
    render(<SettingsScreen />)

    const languageSection = screen.getByRole('heading', { name: 'Language' }).closest('section')
    expect(languageSection).not.toBeNull()
    expect(within(languageSection!).getByText('English')).toBeInTheDocument()
    expect(within(languageSection!).getByText('한국어')).toHaveAttribute('lang', 'ko')
    expect(within(languageSection!).getByText(/coming later/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /language/i })).not.toBeInTheDocument()
  })

  it('leaves allergy profile as a noninteractive placeholder without writing preferences', () => {
    render(<SettingsScreen />)

    expect(screen.getByRole('heading', { name: 'Allergy profile' })).toBeInTheDocument()
    expect(screen.getByText(/consent controls are not available yet/i)).toBeInTheDocument()
    expect(screen.queryByRole('switch', { name: /allerg/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /allerg/i })).not.toBeInTheDocument()
    expect(localStorage.getItem('mermaid.preferences.v1')).toBeNull()
  })

  it('names the official sources and states that the app never diagnoses', () => {
    render(<SettingsScreen />)

    for (const agency of ['식약처', '심평원', '국립중앙의료원']) {
      expect(screen.getByText(agency)).toHaveAttribute('lang', 'ko')
    }
    expect(screen.getByText(/this app informs — it never diagnoses/i)).toBeInTheDocument()
  })
})
