import OpenAI from 'openai'
import type { MermAidAnswer } from './types'

/**
 * The official OpenAI SDK, pointed at our own Spring backend.
 *
 * **`baseURL` must be absolute.** The SDK concatenates rather than resolves — `new URL(baseURL +
 * path)` — so a relative `'/api/v1'` yields the relative string `'/api/v1/chat/completions'`, and
 * single-argument `URL` rejects it with `TypeError: Invalid URL`. The constructor accepts the bad
 * base without complaint; the throw lands on the first request, inside `send()`, which catches it.
 * The screen said "something went wrong", the network tab stayed empty, and the chat had never once
 * run in a browser while every backend test passed against curl.
 *
 * Because it concatenates, `baseURL` must carry the full `/api/v1` prefix — resolving it instead,
 * with `new URL(path, baseURL)`, would quietly answer `/api/chat/completions` and bypass the Spring
 * route. A trailing slash is harmless; the SDK drops the duplicate.
 *
 * Building it from `location.origin` keeps the request same-origin, which is the whole point:
 * Vite proxies `/api` to Spring in dev, and the `openai` SDK attaches `Authorization` plus
 * `x-stainless-*` headers that would otherwise trigger a CORS preflight (spec §2-3).
 *
 * The real provider key lives on the server (NFR-03) — the browser sends a placeholder the proxy
 * ignores. It cannot be empty: the constructor throws without a key.
 *
 * Why not the Vercel AI SDK? `useChat` validates each SSE line against its own `UIMessageChunk`
 * schema and rejects OpenAI's `choices[].delta` shape. Making the backend more standards-compliant
 * moves it further away, not closer.
 */
export const openai = new OpenAI({
  baseURL: new URL('/api/v1', window.location.origin).toString(),
  apiKey: 'not-needed',
  dangerouslyAllowBrowser: true,
})

/**
 * Streams an assistant turn, yielding the text assembled so far.
 *
 * The backend answers `stream: true` with a **single** validated chunk — it cannot run its
 * post-processing invariants on a partial JSON, and an unvalidated drug recommendation must
 * not reach this file (spec §5-4). So expect one yield, then completion. Do not rebuild
 * token-by-token rendering without a server that buffers and validates first.
 *
 * Never bind a partial to a medical card regardless. Wait for the stream to finish.
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

export interface SendFailure {
  message: string
  /** The backend's own verdict on whether trying again can help (types.ts `ApiError`). */
  retryable: boolean
  /** For bug reports — the log line is found by this id. */
  requestId: string | null
}

/**
 * Reads the proxy's error envelope out of whatever `streamChat` threw.
 *
 * The proxy answers failures with the `ApiError` envelope, and the SDK surfaces the envelope's
 * inner `error` object as `APIError.error` — `retryable` is the contract for whether a
 * "Try again" button is honest, so the screen must consult it rather than always offering one.
 *
 * A transport failure (server down, non-JSON body) carries no verdict. Retrying is the safe
 * default there: the backend says `retryable: false` explicitly whenever a retry cannot help.
 */
export function describeSendFailure(e: unknown): SendFailure {
  const inner = (e as { error?: unknown } | null)?.error
  if (inner && typeof inner === 'object' && typeof (inner as { retryable?: unknown }).retryable === 'boolean') {
    const body = inner as { message?: unknown; retryable: boolean; request_id?: unknown }
    return {
      message:
        typeof body.message === 'string' && body.message ? body.message : (e as Error).message,
      retryable: body.retryable,
      requestId: typeof body.request_id === 'string' ? body.request_id : null,
    }
  }
  return {
    message: e instanceof Error ? e.message : String(e),
    retryable: true,
    requestId: null,
  }
}

export const FALLBACK_DISCLAIMER =
  'This is general information, not medical advice or a diagnosis. ' +
  'Consult a licensed pharmacist or doctor. In an emergency, call 119.'

/**
 * Coerces whatever the model produced into a renderable answer (NFR-04, TC-03).
 *
 * The backend already validated it — schema plus the post-processing invariants of spec §2-15,
 * which is where a fabricated product name gets rejected. This pass is not a second gate; it
 * only guarantees the component tree gets a fully-populated object no matter what arrives.
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
