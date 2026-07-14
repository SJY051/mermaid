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

/**
 * The English name, and nothing else. `nameKo`, `amount` and `unit` are deliberately not rendered.
 *
 * We hold ingredient names **in English** and nothing else — `Drug.ingredientsEn`, parsed from
 * 허가정보's `ITEM_INGR_NAME`, which is English. There is no Korean ingredient name in the record, no
 * amount and no unit. So three of the five fields on this row had no source at all, and the validator
 * compares normalized ENGLISH keys, which means a row could read
 *
 *     Acetaminophen · 이부프로펜 · 5000 mg
 *
 * and pass every invariant: the English name is the retrieved one, the Korean beside it is a
 * different drug, and the strength is ten times the licensed dose — under a footer naming 식약처.
 *
 * The server strips them (invariant 8), and the render paths go with them: a value we cannot source
 * must not have a way onto the screen, or the next one that leaks in prints itself under the verified
 * footer exactly as these did. The Korean a person needs in a pharmacy is the PRODUCT name, which the
 * card already shows and the server already owns (타이레놀정500밀리그람) — it is what they point at.
 */
function IngredientLine({ ingredient }: { ingredient: Ingredient }) {
  const fallbackName = ingredient.normalizedKey ?? 'Ingredient name unavailable'

  return (
    <li className="break-words text-sm text-primary">
      {ingredient.nameEn ? <span>{ingredient.nameEn}</span> : <span>{fallbackName}</span>}
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
              // is deliberately NOT translated: a mistranslated dose is an overdose, and a check
              // that merely compared numbers passed "Take 12 tablets once daily" because the label's
              // 만 12세 contains a 12. So the number a person acts on is the ministry's, in the
              // ministry's words. `lang="ko"` so a screen reader speaks it as Korean.
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
              // number — the same trap as a `no_match_found` allergy read as "safe" (§2-2).
              <p className="mt-1 text-sm text-primary">
                We have no official dosing for this medicine. Read the dosing on the package, or ask
                the pharmacist.
              </p>
            )}
          </section>
        ) : null}

        {!isBlocked ? (
          <section aria-labelledby={`${titleId}-cautions`}>
            <h3
              id={`${titleId}-cautions`}
              className="text-xs font-bold uppercase tracking-wide text-secondary"
            >
              Cautions from the label
            </h3>
            {drug.labelCautions ? (
              <p className="mt-1 whitespace-pre-wrap text-sm text-primary">{drug.labelCautions}</p>
            ) : (
              // Either the ministry gave us no 주의사항 for this medicine, or the summary the model
              // wrote of it could not be traced back to the ministry's own words (invariant 8).
              // Rendering nothing would leave a blank where the cautions belong, and a card with no
              // cautions on it reads as a medicine with none — the same trap as a `no_match_found`
              // allergy read as "safe" (§2-2).
              <p className="mt-1 text-sm text-primary">
                We are not showing this medicine&apos;s cautions. Read them on the package, or ask
                the pharmacist — they can read the Korean label with you.
              </p>
            )}
          </section>
        ) : null}

        {/*
         * The line between what the server wrote and what the assistant wrote, said out loud.
         *
         * Everything else on this card is a server fact: the product, the ingredient names, the
         * dosing (식약처's own Korean, verbatim), the contraindications (rendered by the server from
         * the DUR record), the prescription status, the source. Two sections are not — "For" and
         * "Cautions from the label" are the assistant's English of the ministry's Korean prose,
         * because a Korean-only label is unreadable to the person this app is for, and translating
         * it is the job we gave the model (AGENTS §1).
         *
         * We cannot check the *words* of a translation. A plausible, fluent, wrong sentence carrying
         * no number is not machine-detectable, and that is OUT-02 in the security review — open, and
         * open by design. What we CAN do is stop the verified footer from implying otherwise. A
         * government source chip above an unlabelled English paragraph says "the ministry said this".
         * The ministry said the Korean it was summarised from.
         *
         * So the chip keeps meaning what it always meant — these facts came from that agency — and
         * this line says which sentences are a summary. It is the cheapest honest thing available,
         * and the only one that does not turn a service for English readers into a Korean label
         * viewer.
         */}
        {!isBlocked && (drug.indicationSummary || drug.labelCautions) ? (
          <p role="note" className="text-xs text-secondary" data-testid="summary-caveat">
            &ldquo;For&rdquo; and &ldquo;Cautions&rdquo; are the assistant&apos;s English summary of
            the official Korean document — not a word-for-word translation. The dosing above and the
            contraindications below come from the ministry itself. If anything matters to your
            decision, ask the pharmacist.
          </p>
        ) : null}

        <section aria-labelledby={`${titleId}-warnings`}>
          <h3
            id={`${titleId}-warnings`}
            className="text-xs font-bold uppercase tracking-wide text-secondary"
          >
            Official contraindications
          </h3>
          {drug.warnings.length > 0 ? (
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
          ) : (
            // An empty list is the server's statement that 식약처 has published no DUR record for this
            // product — not that nothing about it is worth knowing. This section used to vanish
            // entirely, and a card with no Warnings heading reads as a medicine with no warnings.
            <p className="mt-1 text-sm text-primary">
              <span lang="ko">식약처</span> publishes no contraindications for this medicine. That is
              not a clearance to take it — ask the pharmacist.
            </p>
          )}
        </section>

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
