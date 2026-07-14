import { Badge } from '@astryxdesign/core/Badge'
import type { AllergyCheck } from '../lib/types'

/**
 * Renders the four allergy states (spec §2-12).
 *
 * The one that matters is `no_match_found`. It means "we did not find a match in the
 * ingredient list we have" — not "this is safe to take". It gets no green badge and no
 * reassuring word, because a green badge is exactly what a worried parent would read as
 * permission. Plain, quiet text instead.
 *
 * `unknown` also gets a visible badge: being unable to compare is a warning, not a pass.
 *
 * The badge is a summary, never the whole finding. The server writes a message saying which
 * ingredient matched what — "Name match only: Acetaminophen Granules matched the unverified
 * allergen name yellow dye" — and an answer can carry several cards. A badge reading "Possible
 * ingredient match" on each of them tells the reader that something is wrong somewhere, which is
 * the one thing they already knew. The server's sentence is the finding, so it is shown.
 */
export function AllergyBadge({ check }: { check: AllergyCheck }) {
  const detail = check.message?.trim()

  switch (check.status) {
    case 'blocked':
      return (
        <div className="flex flex-col gap-1">
          <Badge variant="error" label={`Contains ${check.matchedIngredients.join(', ')}`} />
          {detail && <p className="text-sm text-primary">{detail}</p>}
        </div>
      )

    case 'warning':
      return (
        <div className="flex flex-col gap-1">
          <Badge
            variant="warning"
            label={
              check.matchedIngredients.length > 0
                ? `Possible match: ${check.matchedIngredients.join(', ')}`
                : 'Possible ingredient match'
            }
          />
          {detail && <p className="text-sm text-primary">{detail}</p>}
        </div>
      )

    case 'unknown':
      return (
        <div className="flex flex-col gap-1">
          <Badge variant="warning" label="Ingredients could not be checked" />
          {detail && <p className="text-sm text-primary">{detail}</p>}
        </div>
      )

    case 'no_match_found':
      // Deliberately not a badge, and deliberately not the word "safe".
      return (
        <p className="text-sm text-secondary">
          {detail ||
            'No match found in the listed ingredients. This is not a guarantee — confirm with a pharmacist.'}
        </p>
      )
  }
}
