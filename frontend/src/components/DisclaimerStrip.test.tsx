import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { DisclaimerStrip } from './DisclaimerStrip'

/**
 * What must never regress: changing or removing the canonical copy, or rendering it conditionally,
 * must turn this test red.
 */
describe('DisclaimerStrip', () => {
  it('renders the exact canonical safety text', () => {
    render(<DisclaimerStrip />)

    expect(
      screen.getByText('General information, not medical advice · Emergency? Call 119'),
    ).toBeVisible()
  })
})
