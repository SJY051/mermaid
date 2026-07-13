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
    directionsSummary: 'Adults: take one tablet every 4–6 hours.',
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
    expect(within(card).getByText('Adults: take one tablet every 4–6 hours.')).toBeInTheDocument()
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
