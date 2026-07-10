import OpenAI from 'openai'
import { describe, expect, it } from 'vitest'
import { FALLBACK_DISCLAIMER, openai, parseAnswer } from './openaiClient'

/**
 * The bug this file exists for.
 *
 * `baseURL` was `'/api/v1'`. The backend had 275 passing tests, every one of them driven by curl,
 * and the chat had never once run in a browser. The SDK concatenates — `new URL(baseURL + path)` —
 * and a relative base makes that a relative string, which single-argument `URL` rejects. The
 * constructor is fine; `buildURL` throws on the first request, inside `send()`, which catches it.
 * So the screen said "something went wrong" and the network tab stayed empty.
 *
 * A typecheck cannot see this. Only something that builds the URL can — and it has to build it the
 * way the SDK does. Resolving `new URL('chat/completions', baseURL)` would silently answer
 * `/api/chat/completions`, dropping the `/v1` the Spring route needs, and still not throw.
 */
describe('the openai client points somewhere a browser can actually reach', () => {
  it('has an absolute baseURL on our own origin', () => {
    expect(openai.baseURL).toMatch(/^https?:\/\//)
    expect(new URL(openai.baseURL).origin).toBe(window.location.origin)
  })

  it('resolves a request to the /api/v1 route Spring actually serves', () => {
    const url = new URL(openai.buildURL('/chat/completions', null))

    expect(url.pathname).toBe('/api/v1/chat/completions')
    expect(url.origin).toBe(window.location.origin)
  })

  it('would have caught the original bug', () => {
    const broken = new OpenAI({ baseURL: '/api/v1', apiKey: 'x', dangerouslyAllowBrowser: true })

    // The constructor is happy. That is precisely why this shipped.
    expect(() => broken.buildURL('/chat/completions', null)).toThrow(TypeError)
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
