import type { AllergyCheck } from '../lib/types'

/**
 * Renders the four allergy states (spec §2-12).
 *
 * The one that matters is `no_match_found`. It means "we did not find a match in the
 * ingredient list we have" — not "this is safe to take". It gets no green badge and no
 * reassuring word, because a green badge is exactly what a worried parent would read as
 * permission. Plain, quiet text instead.
 *
 * `unknown` also gets a visible warning callout: being unable to compare is not a pass.
 *
 * Two things had to be true at once here, and this keeps both.
 *
 * The **callout** is what a person reads at a glance: `blocked` is a `role="alert"` that says, in
 * words, not to take it — and says on purpose that no substitute is offered, because choosing an
 * alternative medicine is the one clinical judgement we refuse to let a model make (SA-08).
 *
 * The **server's sentence** is the finding itself. The server writes which ingredient matched what
 * — "Name match only: Acetaminophen Granules matched the unverified allergen name yellow dye" — and
 * an answer can carry several cards. A callout reading "Possible ingredient match" on each of them
 * tells the reader the one thing they already knew: that something, somewhere, is wrong. So the
 * sentence is rendered under the callout, and the card says which medicine and which ingredient.
 */
export function AllergyBadge({ check }: { check: AllergyCheck }) {
  const matchedIngredients = check.matchedIngredients.join(', ')
  const detail = check.message?.trim()

  switch (check.status) {
    case 'blocked':
      return (
        <div
          role="alert"
          data-allergy-state="blocked"
          className="flex flex-col gap-1 rounded-lg border-2 border-[#c62828] bg-[#fdf1f1] p-3 text-sm"
        >
          <strong className="text-[#8f1d1d]">
            {matchedIngredients
              ? `Contains ${matchedIngredients} — an allergy you listed`
              : 'Contains an ingredient that matches an allergy you listed'}
          </strong>
          <p className="text-[#2a2d33]">
            Don&apos;t take this one. A pharmacist can help you find the right option — no
            substitute is suggested here, on purpose.
          </p>
          {detail && <p className="text-[#2a2d33]">{detail}</p>}
        </div>
      )

    case 'warning':
      return (
        <div
          role="status"
          data-allergy-state="warning"
          className="flex flex-col gap-1 rounded-lg border-2 border-[#e0a800] bg-[#fbf3d9] p-3 text-sm text-[#2a2d33]"
        >
          <p>
            <strong>
              Possible ingredient match{matchedIngredients ? ` — ${matchedIngredients}` : ''}.
            </strong>{' '}
            Check with a pharmacist before taking this.
          </p>
          {detail && <p>{detail}</p>}
        </div>
      )

    case 'unknown':
      return (
        <div
          role="status"
          data-allergy-state="unknown"
          className="flex flex-col gap-1 rounded-lg border-2 border-[#e0a800] bg-[#fbf3d9] p-3 text-sm text-[#2a2d33]"
        >
          <p>
            <strong>Ingredients could not be checked.</strong> The ingredient list did not load, so
            your allergies were not compared. Ask a pharmacist before taking it.
          </p>
          {detail && <p>{detail}</p>}
        </div>
      )

    case 'no_match_found':
      // Deliberately not a callout, and deliberately not the word "safe".
      return (
        <p data-allergy-state="no_match_found" className="text-sm text-secondary">
          {detail ||
            'No match found in the listed ingredients. This is not a guarantee — confirm with a pharmacist.'}
        </p>
      )
  }
}
