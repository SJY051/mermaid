import { useId } from 'react'
import { Badge } from '@astryxdesign/core/Badge'
import { Card } from '@astryxdesign/core/Card'
import type {
  AllergyStatus,
  DrugCard as DrugCardData,
  Ingredient,
  PrescriptionStatus,
  SourceRef,
} from '../lib/types'
import { AllergyBadge } from './AllergyBadge'

const PRESCRIPTION_LABELS: Record<PrescriptionStatus, string> = {
  prescription: 'Prescription only',
  otc: 'OTC',
  unknown: 'Prescription status unknown',
}

const ALLERGY_BORDER_CLASSES: Record<AllergyStatus, string> = {
  blocked: 'border-2 border-[#c62828]',
  warning: '',
  unknown: '',
  no_match_found: '',
}

function hasText(value: string | number | null): value is string | number {
  return value !== null && String(value).trim() !== ''
}

function IngredientLine({ ingredient }: { ingredient: Ingredient }) {
  const fallbackName = ingredient.normalizedKey ?? 'Ingredient name unavailable'
  const quantity = [ingredient.amount, ingredient.unit].filter(hasText).join(' ')

  return (
    <li className="break-words text-sm text-primary">
      {ingredient.nameEn ? <span>{ingredient.nameEn}</span> : null}
      {ingredient.nameKo ? (
        <>
          {ingredient.nameEn ? ' · ' : null}
          <span lang="ko">{ingredient.nameKo}</span>
        </>
      ) : null}
      {!ingredient.nameEn && !ingredient.nameKo ? <span>{fallbackName}</span> : null}
      {quantity ? <span> · {quantity}</span> : null}
    </li>
  )
}

function datePart(retrievedAt: string): string {
  return retrievedAt.split('T', 1)[0] || 'Date unavailable'
}

function containsKorean(value: string): boolean {
  return /[ㄱ-ㆎ가-힣]/.test(value)
}

export function DrugCard({ drug, source }: { drug: DrugCardData; source?: SourceRef }) {
  const titleId = useId()
  const isBlocked = drug.allergyCheck.status === 'blocked'

  if (!source) {
    return (
      <Card variant="muted">
        <p role="status" className="text-sm font-medium text-primary">
          Medicine information could not be displayed because its official source is unavailable.
        </p>
      </Card>
    )
  }

  return (
    <Card padding={0} className={ALLERGY_BORDER_CLASSES[drug.allergyCheck.status]}>
      <article aria-labelledby={titleId} className="flex flex-col gap-3 p-4">
        <header className="flex flex-wrap items-baseline gap-2">
          <h2 id={titleId} lang="ko" className="break-keep text-lg font-bold text-primary">
            {drug.productNameKo}
          </h2>
          <Badge variant="neutral" label={PRESCRIPTION_LABELS[drug.prescriptionStatus]} />
          {drug.productNameEn ? (
            <p className="w-full text-sm text-secondary">{drug.productNameEn}</p>
          ) : null}
        </header>

        <section aria-labelledby={`${titleId}-ingredients`}>
          <h3
            id={`${titleId}-ingredients`}
            className="mb-1 text-xs font-bold uppercase tracking-wide text-secondary"
          >
            Ingredients
          </h3>
          {drug.ingredients.length > 0 ? (
            <ul className="list-disc space-y-1 pl-5">
              {drug.ingredients.map((ingredient, index) => (
                <IngredientLine key={index} ingredient={ingredient} />
              ))}
            </ul>
          ) : (
            <p className="text-sm text-secondary">Ingredient list unavailable.</p>
          )}
        </section>

        <section aria-label="Allergy check">
          <AllergyBadge check={drug.allergyCheck} />
        </section>

        {!isBlocked && drug.indicationSummary ? (
          <section aria-labelledby={`${titleId}-indication`}>
            <h3
              id={`${titleId}-indication`}
              className="text-xs font-bold uppercase tracking-wide text-secondary"
            >
              For
            </h3>
            <p className="mt-1 whitespace-pre-wrap text-sm text-primary">
              {drug.indicationSummary}
            </p>
          </section>
        ) : null}

        {!isBlocked ? (
          <section aria-labelledby={`${titleId}-directions`}>
            <h3
              id={`${titleId}-directions`}
              className="text-xs font-bold uppercase tracking-wide text-secondary"
            >
              Directions
            </h3>
            {drug.directionsSummary ? (
              // This text is 식약처's own 용법용량, written by the server, verbatim (invariant 7). It
              // is deliberately NOT translated: a mistranslated dose is an overdose, and the model
              // once passed a check by reusing the label's "12" — an AGE — as a tablet count. So the
              // number a person acts on is the ministry's, in the ministry's words, and the sentence
              // below says exactly that. `lang="ko"` so a screen reader speaks it as Korean.
              <>
                <p
                  lang="ko"
                  className="mt-1 whitespace-pre-wrap text-sm text-primary"
                  data-testid="official-dosage"
                >
                  {drug.directionsSummary}
                </p>
                <p className="mt-1 text-xs text-secondary">
                  Official dosing from the Ministry of Food and Drug Safety, in Korean. We do not
                  translate doses — show this to the pharmacist and they will read it with you.
                </p>
              </>
            ) : (
              // Silence here reads as "no special dosing", in the exact place a person looks for a
              // number — the same trap as a `no_match_found` allergy read as "safe" (§2-2). So the
              // card says what happened, and points at the two places the real dose is.
              <p className="mt-1 text-sm text-primary">
                We have no official dosing for this medicine. Read the dosing on the package, or ask
                the pharmacist.
              </p>
            )}
          </section>
        ) : null}

        {drug.warnings.length > 0 ? (
          <section aria-labelledby={`${titleId}-warnings`}>
            <h3
              id={`${titleId}-warnings`}
              className="text-xs font-bold uppercase tracking-wide text-secondary"
            >
              Warnings
            </h3>
            <ul className="mt-1 space-y-2">
              {drug.warnings.map((warning, index) => (
                <li
                  key={index}
                  className="flex gap-2 rounded-lg border border-[#e0a800] bg-[#fbf3d9] p-2 text-sm text-[#2a2d33]"
                >
                  <span
                    aria-hidden="true"
                    className="mt-0.5 grid h-4 w-4 shrink-0 place-items-center rounded-full border border-[#8a6a00] text-xs font-bold text-[#6b5200]"
                  >
                    !
                  </span>
                  <span className="whitespace-pre-wrap break-words">{warning}</span>
                </li>
              ))}
            </ul>
          </section>
        ) : null}

        <footer className="flex flex-wrap items-start gap-2 border-t border-primary pt-3">
          {source.dataMode === 'fixture' ? <Badge variant="neutral" label="Sample data" /> : null}
          <div className="flex min-w-0 flex-1 flex-col items-start gap-1">
            <span
              aria-label={`Verified source: ${source.provider}, retrieved ${datePart(source.retrievedAt)}`}
              className="inline-flex max-w-full items-center gap-1 rounded-full border border-primary px-2 py-1 text-xs font-semibold text-primary"
            >
              <span aria-hidden="true">✓</span>
              <span lang={containsKorean(source.provider) ? 'ko' : undefined}>
                {source.provider}
              </span>{' '}
              · <time dateTime={source.retrievedAt}>{datePart(source.retrievedAt)}</time>
            </span>
            <p className="text-xs text-secondary">{source.title}</p>
          </div>
        </footer>
      </article>
    </Card>
  )
}
