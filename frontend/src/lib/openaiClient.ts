import OpenAI from 'openai'
import type { MermAidAnswer } from './types'

/**
 * The official OpenAI SDK, pointed at our own Spring backend.
 *
 * `baseURL` sends every call to `/api/v1`, which Vite proxies to Spring in dev.
 * The real provider key lives on the server (NFR-03) — the browser sends a placeholder
 * the proxy ignores. It cannot be empty: the constructor throws without a key, and there
 * is no `process.env` here to fall back on.
 *
 * Why not the Vercel AI SDK? `useChat` validates each SSE line against its own
 * `UIMessageChunk` schema and rejects OpenAI's `choices[].delta` shape. Making the backend
 * more standards-compliant moves it further away, not closer. Spec §2-3.
 */
export const openai = new OpenAI({
  baseURL: '/api/v1',
  apiKey: 'not-needed',
  dangerouslyAllowBrowser: true,
})

/**
 * Streams an assistant turn, yielding the text assembled so far.
 *
 * Never bind a partial to a medical card. Mid-stream the JSON is truncated and
 * `parseAnswer` will fall back to prose — which is correct for a progress indicator
 * and wrong for a drug recommendation. Wait for the stream to finish. Spec §5-4.
 */
export async function* streamChat(
  messages: OpenAI.ChatCompletionMessageParam[],
  signal?: AbortSignal,
): AsyncGenerator<string> {
  const stream = await openai.chat.completions.create(
    // The backend pins the real model; whatever we send here is overwritten.
    { model: 'mermaid-default', messages, stream: true },
    { signal },
  )

  let assembled = ''
  for await (const chunk of stream) {
    assembled += chunk.choices[0]?.delta?.content ?? ''
    yield assembled
  }
}

export const FALLBACK_DISCLAIMER =
  'This is general information, not medical advice or a diagnosis. ' +
  'Consult a licensed pharmacist or doctor. In an emergency, call 119.'

/**
 * Coerces whatever the model produced into a renderable answer (NFR-04, TC-03).
 *
 * The backend validates too — schema plus the post-processing invariants of spec §2-15,
 * which is where a fabricated product name gets rejected. This client-side pass exists
 * because streaming deltas cannot be validated server-side: the JSON only becomes
 * parseable once the last chunk lands.
 *
 * A parse failure here is not an error to show. Prose is still useful to a sick person.
 */
export function parseAnswer(raw: string): MermAidAnswer {
  const candidate = stripMarkdownFence(raw.trim())
  try {
    const parsed = JSON.parse(candidate) as Partial<MermAidAnswer>
    return withGuarantees(parsed, raw)
  } catch {
    return safeAnswer(raw)
  }
}

function withGuarantees(parsed: Partial<MermAidAnswer>, raw: string): MermAidAnswer {
  if (!parsed.summary && !parsed.drugs?.length) {
    return safeAnswer(raw)
  }
  return {
    schemaVersion: '1.0',
    answerId: parsed.answerId ?? 'local',
    language: 'en',
    dataStatus: parsed.dataStatus ?? 'unavailable',
    urgency: parsed.urgency ?? {
      level: 'unknown',
      title: 'More information is needed',
      message: 'I could not determine urgency from what you told me.',
      reasonCodes: [],
      actions: [],
    },
    summary: parsed.summary ?? raw,
    clarifyingQuestions: parsed.clarifyingQuestions ?? [],
    guidance: parsed.guidance ?? [],
    drugs: parsed.drugs ?? [],
    uiActions: parsed.uiActions ?? [],
    sourceRefs: parsed.sourceRefs ?? [],
    warnings: parsed.warnings ?? [],
    disclaimer: parsed.disclaimer || FALLBACK_DISCLAIMER,
  }
}

/** No drugs, no actions, no claims — just the text and the disclaimer. */
function safeAnswer(reply: string): MermAidAnswer {
  return {
    schemaVersion: '1.0',
    answerId: 'local-fallback',
    language: 'en',
    dataStatus: 'unavailable',
    urgency: {
      level: 'unknown',
      title: 'More information is needed',
      message: '',
      reasonCodes: [],
      actions: [],
    },
    summary: reply,
    clarifyingQuestions: [],
    guidance: [],
    drugs: [],
    uiActions: [],
    sourceRefs: [],
    warnings: [],
    disclaimer: FALLBACK_DISCLAIMER,
  }
}

/** Models love answering a JSON request with ```json … ``` wrapped around it. */
function stripMarkdownFence(s: string): string {
  if (!s.startsWith('```')) return s
  const firstNewline = s.indexOf('\n')
  const closing = s.lastIndexOf('```')
  if (firstNewline < 0 || closing <= firstNewline) return s
  return s.slice(firstNewline + 1, closing).trim()
}
