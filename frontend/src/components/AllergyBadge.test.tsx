import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { AllergyBadge } from './AllergyBadge'
import type { AllergyCheck } from '../lib/types'

const check = (over: Partial<AllergyCheck>): AllergyCheck => ({
  status: 'no_match_found',
  matchedIngredients: [],
  message: '',
  ...over,
})

/**
 * Four states, not a nullable warning (spec §2-12).
 *
 * `no_match_found` means "we did not find a match in the ingredient list we have". It is the
 * state a user allergic to ibuprofen gets when shown naproxen, because we hold no NSAID
 * cross-reactivity table and may not invent one (AR-01). It must never read as reassurance.
 */
describe('AllergyBadge', () => {
  it('names the ingredient when the drug is blocked', () => {
    render(<AllergyBadge check={check({ status: 'blocked', matchedIngredients: ['Ibuprofen'] })} />)

    expect(screen.getByText(/Ibuprofen/)).toBeInTheDocument()
    expect(screen.getByText(/Contains/)).toBeInTheDocument()
  })

  it('lists every matched ingredient, not just the first', () => {
    render(
      <AllergyBadge check={check({ status: 'blocked', matchedIngredients: ['Ibuprofen', 'Aspirin'] })} />,
    )

    expect(screen.getByText(/Ibuprofen, Aspirin/)).toBeInTheDocument()
  })

  it('warns on a possible match', () => {
    render(<AllergyBadge check={check({ status: 'warning' })} />)

    expect(screen.getByText(/Possible ingredient match/)).toBeInTheDocument()
  })

  it('treats "could not check" as a warning, never as silence', () => {
    const { container } = render(<AllergyBadge check={check({ status: 'unknown' })} />)

    expect(screen.getByText(/could not be checked/i)).toBeInTheDocument()
    expect(container).not.toBeEmptyDOMElement()
  })

  describe('no_match_found is not a clean bill of health', () => {
    it('never says the word "safe"', () => {
      const { container } = render(<AllergyBadge check={check({ status: 'no_match_found' })} />)

      expect(container.textContent).not.toMatch(/\bsafe\b/i)
      expect(container.textContent).not.toMatch(/\bno (allergens|allergies)\b/i)
    })

    it('says out loud that it is not a guarantee', () => {
      render(<AllergyBadge check={check({ status: 'no_match_found' })} />)

      expect(screen.getByText(/not a guarantee/i)).toBeInTheDocument()
      expect(screen.getByText(/confirm with a\s+pharmacist/i)).toBeInTheDocument()
    })

    it('is plain text, not a badge — a green pill reads as permission', () => {
      const { container } = render(<AllergyBadge check={check({ status: 'no_match_found' })} />)

      expect(container.querySelector('p')).toBeInTheDocument()
      // Whatever astryx renders a Badge as, the reassuring states must not use it.
      const blocked = render(<AllergyBadge check={check({ status: 'blocked', matchedIngredients: ['X'] })} />)
      expect(container.innerHTML).not.toBe(blocked.container.innerHTML)
    })
  })
})
