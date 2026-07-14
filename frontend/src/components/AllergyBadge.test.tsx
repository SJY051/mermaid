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
    expect(screen.getByRole('alert')).toHaveAttribute('data-allergy-state', 'blocked')
    expect(screen.getByText(/don.t take this one/i)).toBeInTheDocument()
    expect(screen.getByText(/no substitute is suggested here/i)).toBeInTheDocument()
  })

  it('lists every matched ingredient, not just the first', () => {
    render(
      <AllergyBadge check={check({ status: 'blocked', matchedIngredients: ['Ibuprofen', 'Aspirin'] })} />,
    )

    expect(screen.getByText(/Ibuprofen, Aspirin/)).toBeInTheDocument()
  })

  it('warns on a possible match', () => {
    render(
      <AllergyBadge check={check({ status: 'warning', matchedIngredients: ['Aspirin'] })} />,
    )

    expect(screen.getByText(/Possible ingredient match/)).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveAttribute('data-allergy-state', 'warning')
    expect(screen.getByText(/Aspirin/)).toBeInTheDocument()
    expect(screen.getByText(/check with a pharmacist before taking this/i)).toBeInTheDocument()
  })

  it('shows the server’s finding, not just that there is one (P1)', () => {
    // An answer can carry several cards. A badge reading "Possible ingredient match" on each of
    // them tells the reader only what they already knew — that something, somewhere, is wrong. The
    // server writes which ingredient matched what, and that sentence is the finding. Dropping it
    // leaves a name-match warning indistinguishable from a reviewed one, on the wrong medicine.
    const nameMatch = check({
      status: 'warning',
      matchedIngredients: ['Acetaminophen Granules'],
      message:
        'Name match only: Acetaminophen Granules matched the unverified allergen name paracetamol. ' +
        'A pharmacist must confirm this match.',
    })
    const { container } = render(<AllergyBadge check={nameMatch} />)

    expect(screen.getByText(/Name match only/)).toBeInTheDocument()
    expect(screen.getByText(/A pharmacist must confirm this match/)).toBeInTheDocument()
    // The callout names the ingredient (drug-card wording after the DEV-308 merge) and the server's
    // sentence sits under it. Both must be on the card: the first says which medicine is affected,
    // the second says why — a name match, not a reviewed one.
    // It appears twice on purpose: once in the callout that names the affected medicine, once in
    // the server's sentence that says WHY — a name match, not a reviewed one.
    expect(screen.getAllByText(/Acetaminophen Granules/).length).toBeGreaterThanOrEqual(2)
    expect(screen.getByText(/Possible ingredient match/)).toBeInTheDocument()
    expect(container.textContent).not.toMatch(/\bsafe\b/i)
    expect(container.innerHTML).not.toMatch(/green|success/i)
  })

  it('shows the server’s reason when the ingredients could not be read (P1)', () => {
    const unreadable = check({
      status: 'unknown',
      message:
        "We could not read this product's ingredients, so we could not check it against the " +
        'allergens you named. Ask a pharmacist before taking it.',
    })
    render(<AllergyBadge check={unreadable} />)

    expect(screen.getByText(/could not read this product's ingredients/i)).toBeInTheDocument()
    expect(screen.queryByText(/No match/i)).not.toBeInTheDocument()
  })

  it('treats "could not check" as a warning, never as silence', () => {
    const { container } = render(<AllergyBadge check={check({ status: 'unknown' })} />)

    expect(screen.getByText(/could not be checked/i)).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveAttribute('data-allergy-state', 'unknown')
    expect(screen.getByText(/allergies were not compared/i)).toBeInTheDocument()
    expect(screen.getByText(/ask a pharmacist before taking it/i)).toBeInTheDocument()
    expect(container).not.toBeEmptyDOMElement()
  })

  it.each<AllergyCheck['status']>(['blocked', 'warning', 'unknown', 'no_match_found'])(
    'never renders %s with green or success treatment',
    (status) => {
      const { container } = render(
        <AllergyBadge
          check={
            check({
              status,
              matchedIngredients: status === 'blocked' ? ['Ibuprofen'] : [],
            })
          }
        />,
      )

      expect(container.innerHTML).not.toMatch(/green|success/i)
    },
  )

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
      expect(container.querySelector('[class*="badge"]')).not.toBeInTheDocument()
      // Whatever the safety callouts render as, the reassuring state must not resemble one.
      const blocked = render(<AllergyBadge check={check({ status: 'blocked', matchedIngredients: ['X'] })} />)
      expect(container.innerHTML).not.toBe(blocked.container.innerHTML)
    })
  })
})
