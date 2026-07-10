import { describe, expect, it } from 'vitest'
import { FALLBACK_DISCLAIMER, openai, parseAnswer } from './openaiClient'

/**
 * The bug this file exists for.
 *
 * `baseURL` was `'/api/v1'`. The backend had 275 passing tests, every one of them driven by curl,
 * and the chat had never once run in a browser. The SDK builds each request with
 * `new URL(path, baseURL)`; a relative base throws there, inside `send()`, which catches it — so
 * the screen said "something went wrong" and the network tab stayed empty.
 *
 * A typecheck cannot see this. Only something that constructs the URL can.
 */
describe('the openai client points somewhere a browser can actually reach', () => {
  it('has an absolute baseURL', () => {
    expect(() => new URL(openai.baseURL)).not.toThrow()
    expect(openai.baseURL).toMatch(/^https?:\/\//)
  })

  it('can build a request URL the way the SDK does', () => {
    // This is the exact call that threw. `chat/completions` is relative to the base.
    expect(() => new URL('chat/completions', openai.baseURL)).not.toThrow()
  })

  it('stays on /api/v1 so the Vite proxy and the same-origin rule still hold', () => {
    expect(new URL(openai.baseURL).pathname).toBe('/api/v1')
    expect(new URL(openai.baseURL).origin).toBe(window.location.origin)
  })

  it('would have caught the original bug', () => {
    // Left in deliberately: it documents why the line above is written the way it is.
    expect(() => new URL('chat/completions', '/api/v1')).toThrow()
  })
})

/**
 * `parseAnswer` is the last thing between a model's output and a medication card.
 * It is not a safety gate — the backend already validated (spec §5-4) — but it must never
 * hand the component tree a half-populated object, and it must never drop the disclaimer.
 */
describe('parseAnswer', () => {
  const valid = JSON.stringify({
    schemaVersion: '1.0',
    answerId: 'a1',
    language: 'en',
    dataStatus: 'live',
    urgency: { level: 'routine', title: 'T', message: 'M', reasonCodes: [], actions: [] },
    summary: 'Take paracetamol.',
    clarifyingQuestions: [],
    guidance: [],
    drugs: [],
    uiActions: [],
    sourceRefs: [],
    warnings: [],
    disclaimer: 'Server disclaimer.',
  })

  it('reads a plain JSON answer', () => {
    const answer = parseAnswer(valid)
    expect(answer.summary).toBe('Take paracetamol.')
    expect(answer.dataStatus).toBe('live')
    expect(answer.disclaimer).toBe('Server disclaimer.')
  })

  it('unwraps the ```json fence models keep adding', () => {
    expect(parseAnswer('```json\n' + valid + '\n```').summary).toBe('Take paracetamol.')
    expect(parseAnswer('```\n' + valid + '\n```').summary).toBe('Take paracetamol.')
  })

  it('keeps prose readable when the model ignored the schema entirely', () => {
    const answer = parseAnswer('Drink water and rest.')
    expect(answer.summary).toBe('Drink water and rest.')
    expect(answer.drugs).toEqual([])
    expect(answer.uiActions).toEqual([])
    expect(answer.urgency.level).toBe('unknown')
  })

  it('never emits a drug card it did not receive', () => {
    // Truncated JSON: the object opens, names a product, and dies mid-stream.
    const truncated = '{"summary":"You could take","drugs":[{"productNameKo":"타이레놀"'
    const answer = parseAnswer(truncated)
    expect(answer.drugs).toEqual([])
    expect(answer.summary).toBe(truncated)
  })

  it('treats an answer with neither summary nor drugs as a failure, not an empty success', () => {
    const answer = parseAnswer('{"dataStatus":"live"}')
    expect(answer.answerId).toBe('local-fallback')
    expect(answer.dataStatus).toBe('unavailable')
  })

  it('always carries a disclaimer (SA-02)', () => {
    for (const raw of ['', 'not json', '{}', '{"summary":"hi","disclaimer":""}', valid]) {
      expect(parseAnswer(raw).disclaimer.length).toBeGreaterThan(0)
    }
    expect(parseAnswer('{"summary":"hi","disclaimer":""}').disclaimer).toBe(FALLBACK_DISCLAIMER)
  })

  it('does not claim data is live when it could not parse the answer', () => {
    expect(parseAnswer('garbage').dataStatus).toBe('unavailable')
  })
})
