import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type {
  AllergyStatus,
  DrugCard as DrugCardData,
  PrescriptionStatus,
  SourceRef,
} from '../lib/types'
import { DrugCard } from './DrugCard'

const source: SourceRef = {
  id: 'src:mfds:123',
  provider: '식품의약품안전처 의약품 제품 허가정보',
  recordId: '123',
  retrievedAt: '2026-07-11T05:00:00Z',
  dataMode: 'live',
  title: 'MFDS — drug product licence information',
}

function drug(overrides: Partial<DrugCardData> = {}): DrugCardData {
  return {
    id: 'drug:mfds:123',
    productNameKo: '타이레놀정500밀리그람',
    productNameEn: 'Tylenol 500 mg',
    ingredients: [
      {
        nameKo: '아세트아미노펜',
        nameEn: 'Acetaminophen',
        normalizedKey: 'acetaminophen',
        amount: 500,
        unit: 'mg',
      },
      {
        nameKo: '카페인무수물',
        nameEn: null,
        normalizedKey: 'caffeine',
        amount: '32',
        unit: 'mg',
      },
    ],
    indicationSummary: 'For headache, fever, and mild aches.',
    // Server-written: 식약처's own 용법용량, verbatim and untranslated (invariant 7).
    directionsSummary: '만 12세 이상 소아 및 성인: 1회 1~2정씩 1일 3~4회 필요시 복용합니다.',
    labelCautions: 'Ask a doctor before use if you drink alcohol daily.',
    warnings: [
      'Do not combine with other acetaminophen products.',
      'Swallow whole — do not crush or split this tablet.',
    ],
    prescriptionStatus: 'otc',
    allergyCheck: {
      status: 'no_match_found',
      matchedIngredients: [],
      message: 'No match found.',
    },
    sourceRefId: source.id,
    ...overrides,
  }
}

describe('DrugCard', () => {
  it('renders every available drug fact and its provenance', () => {
    render(<DrugCard drug={drug()} source={source} />)

    const card = screen.getByRole('article', { name: '타이레놀정500밀리그람' })
    expect(within(card).getByRole('heading', { name: '타이레놀정500밀리그람' })).toHaveAttribute(
      'lang',
      'ko',
    )
    expect(within(card).getByText('Tylenol 500 mg')).toBeInTheDocument()
    const ingredients = within(card).getByRole('heading', { name: 'Ingredients' }).parentElement!
    const ingredientRows = within(ingredients).getAllByRole('listitem')
    expect(ingredientRows).toHaveLength(2)
    expect(ingredientRows[0]).toHaveTextContent('Acetaminophen · 아세트아미노펜 · 500 mg')
    expect(within(ingredientRows[0]).getByText('아세트아미노펜')).toHaveAttribute('lang', 'ko')
    expect(ingredientRows[1]).toHaveTextContent('카페인무수물 · 32 mg')
    expect(within(ingredientRows[1]).getByText('카페인무수물')).toHaveAttribute('lang', 'ko')
    expect(within(card).getByText('For headache, fever, and mild aches.')).toBeInTheDocument()
    expect(within(card).getByTestId('official-dosage')).toHaveTextContent('1회 1~2정씩 1일 3~4회')
    expect(
      within(card).getByText('Do not combine with other acetaminophen products.'),
    ).toBeInTheDocument()
    expect(
      within(card).getByText('Swallow whole — do not crush or split this tablet.'),
    ).toBeInTheDocument()
    expect(within(card).getByText(source.provider)).toHaveAttribute('lang', 'ko')
    expect(within(card).getByText(source.title)).toBeInTheDocument()
    expect(
      within(card).getByLabelText(`Verified source: ${source.provider}, retrieved 2026-07-11`),
    ).toHaveTextContent(`${source.provider} · 2026-07-11`)
    expect(within(card).getByText('2026-07-11')).toHaveAttribute(
      'datetime',
      source.retrievedAt,
    )
  })

  it.each<[PrescriptionStatus, string]>([
    ['prescription', 'Prescription only'],
    ['otc', 'OTC'],
    ['unknown', 'Prescription status unknown'],
  ])('labels %s prescription status explicitly', (prescriptionStatus, label) => {
    render(<DrugCard drug={drug({ prescriptionStatus })} source={source} />)

    expect(screen.getByText(label)).toBeInTheDocument()
  })

  it.each<[AllergyStatus, string, string]>([
    ['blocked', 'Contains Ibuprofen', 'border-[#c62828]'],
    ['warning', 'Possible ingredient match', ''],
    ['unknown', 'Ingredients could not be checked', ''],
    ['no_match_found', 'This is not a guarantee', ''],
  ])('renders the %s allergy state without green reassurance', (status, copy, borderClass) => {
    const matchedIngredients = status === 'blocked' ? ['Ibuprofen'] : []
    const { container } = render(
      <DrugCard
        drug={drug({ allergyCheck: { status, matchedIngredients, message: '' } })}
        source={source}
      />,
    )

    expect(screen.getByText(new RegExp(copy, 'i'))).toBeInTheDocument()
    const card = screen.getByRole('article').parentElement
    if (borderClass) expect(card).toHaveClass(borderClass)
    if (status !== 'blocked') expect(card).not.toHaveClass('border-error', 'border-warning')
    expect(container.innerHTML).not.toMatch(/green|success/)
    if (status === 'no_match_found') {
      expect(container.textContent).not.toMatch(/\bsafe\b/i)
      expect(card).not.toHaveClass('border-warning', 'border-error')
    }
  })

  it('labels fixture provenance on the card itself', () => {
    render(<DrugCard drug={drug()} source={{ ...source, dataMode: 'fixture' }} />)

    const card = screen.getByRole('article')
    expect(within(card).getByText('Sample data')).toBeInTheDocument()
  })

  it('does not label a live source as sample data', () => {
    render(<DrugCard drug={drug()} source={source} />)

    expect(screen.queryByText('Sample data')).not.toBeInTheDocument()
  })

  it('states when the official ingredient list is unavailable', () => {
    render(<DrugCard drug={drug({ ingredients: [] })} source={source} />)

    expect(screen.getByText('Ingredient list unavailable.')).toBeInTheDocument()
  })

  it("shows the ministry's dose verbatim, in Korean, and says it is not translated (P0)", () => {
    // The dose a person acts on is the ministry's, in the ministry's words. The model does not write
    // this field — a mistranslated dose is an overdose, and a check that merely compared numbers
    // passed "Take 12 tablets once daily" because the label's 만 12세 contains a 12.
    render(<DrugCard drug={drug()} source={source} />)

    const dose = screen.getByTestId('official-dosage')
    expect(dose).toHaveTextContent('1회 1~2정씩 1일 3~4회')
    expect(dose).toHaveAttribute('lang', 'ko')
    expect(screen.getByText(/we do not translate doses/i)).toBeInTheDocument()
    expect(screen.getByText(/show this to the pharmacist/i)).toBeInTheDocument()
  })

  it('says a dose is missing rather than leaving the card silent (P0)', () => {
    // `null` means the ministry published no 용법용량 — never "this medicine has no particular
    // dosing". Rendering nothing would let the second reading through, in the exact place a person
    // looks for a number. The same trap as a no_match_found allergy read as reassurance (§2-2).
    render(<DrugCard drug={drug({ directionsSummary: null })} source={source} />)

    expect(screen.getByRole('heading', { name: 'Directions' })).toBeInTheDocument()
    expect(screen.getByText(/no official dosing for this medicine/i)).toBeInTheDocument()
    expect(screen.getAllByText(/ask the pharmacist/i).length).toBeGreaterThan(0)
  })

  it('says the cautions are missing rather than leaving the card silent (P0)', () => {
    // `null` means the server would not stand behind the summary the model wrote of 식약처's
    // 주의사항 — or holds none to check it against (invariant 8). It never means "this medicine has
    // nothing to be careful about", and a card with no Cautions heading says exactly that.
    render(<DrugCard drug={drug({ labelCautions: null })} source={source} />)

    expect(screen.getByRole('heading', { name: 'Cautions from the label' })).toBeInTheDocument()
    expect(screen.getByText(/not showing this medicine's cautions/i)).toBeInTheDocument()
    expect(screen.getByText(/ask\s+the pharmacist/i)).toBeInTheDocument()
  })

  it('states an empty contraindication list rather than hiding the section (P0)', () => {
    // The server writes this array from 식약처's DUR record now (invariant 8), so empty is a claim:
    // the ministry publishes no contraindication for this product. The section used to disappear,
    // and a card with no warnings on it reads as a medicine with none — §2-2's trap, one field over.
    render(<DrugCard drug={drug({ warnings: [] })} source={source} />)

    expect(screen.getByRole('heading', { name: 'Official contraindications' })).toBeInTheDocument()
    expect(screen.getByText(/publishes no contraindications/i)).toBeInTheDocument()
    expect(screen.getByText(/not a clearance to take it/i)).toBeInTheDocument()
    expect(screen.queryByText(/\bsafe\b/i)).not.toBeInTheDocument()
  })

  it('never shows dosing or indication guidance for a blocked medicine', () => {
    render(
      <DrugCard
        drug={drug({
          allergyCheck: {
            status: 'blocked',
            matchedIngredients: ['Ibuprofen'],
            message: '',
          },
        })}
        source={source}
      />,
    )

    expect(screen.getByText(/don.t take this one/i)).toBeInTheDocument()
    expect(screen.getByText(/no substitute is suggested here/i)).toBeInTheDocument()
    expect(screen.queryByText(drug().directionsSummary!)).not.toBeInTheDocument()
    expect(screen.queryByText(drug().indicationSummary!)).not.toBeInTheDocument()
  })

  it('suppresses medicine facts when the referenced official source is missing', () => {
    render(<DrugCard drug={drug()} />)

    expect(
      screen.getByText(
        'Medicine information could not be displayed because its official source is unavailable.',
      ),
    ).toBeInTheDocument()
    expect(screen.queryByText(drug().productNameKo)).not.toBeInTheDocument()
    expect(screen.queryByText(drug().indicationSummary!)).not.toBeInTheDocument()
    expect(screen.queryByText('Sample data')).not.toBeInTheDocument()
  })
})
