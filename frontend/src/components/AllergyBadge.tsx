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
 */
export function AllergyBadge({ check }: { check: AllergyCheck }) {
  switch (check.status) {
    case 'blocked':
      return (
        <Badge
          variant="error"
          label={`Contains ${check.matchedIngredients.join(', ')}`}
        />
      )

    case 'warning':
      return <Badge variant="warning" label="Possible ingredient match" />

    case 'unknown':
      return <Badge variant="warning" label="Ingredients could not be checked" />

    case 'no_match_found':
      // Deliberately not a badge, and deliberately not the word "safe".
      return (
        <p className="text-sm text-secondary">
          No match found in the listed ingredients. This is not a guarantee — confirm with a
          pharmacist.
        </p>
      )
  }
}
