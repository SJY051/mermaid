import OpenAI from 'openai'
import type { MedicalResponse } from './types'

/**
 * The official OpenAI SDK, pointed at our own Spring backend.
 *
 * `baseURL` sends every call to `/api/v1`, which Vite proxies to Spring in dev.
 * The real provider key lives on the server (NFR-03) — the browser sends a placeholder
 * that the proxy ignores. It cannot be empty: the constructor throws without a key,
 * and there is no `process.env` here to fall back on.
 *
 * Why not the Vercel AI SDK? `useChat` validates each SSE line against its own
 * `UIMessageChunk` schema and rejects OpenAI's `choices[].delta` shape. Making the
 * backend more standards-compliant moves it further away, not closer. Spec §2-3.
 */
export const openai = new OpenAI({
  baseURL: '/api/v1',
  apiKey: 'not-needed',
  dangerouslyAllowBrowser: true,
})

/**
 * Streams an assistant turn, yielding the assembled text so far.
 *
 * The model is supposed to emit JSON matching `MedicalResponse`, but a free model on an
 * OpenAI-compatible endpoint may not honour `response_format` and will happily stream prose.
 * So we yield raw text while it arrives and only try to parse at the end — see `parseReply`.
 */
export async function* streamChat(
  messages: OpenAI.ChatCompletionMessageParam[],
  signal?: AbortSignal,
): AsyncGenerator<string> {
  const stream = await openai.chat.completions.create(
    // The backend pins the real model; whatever we send here is overwritten.
    { model: 'server-pinned', messages, stream: true },
    { signal },
  )

  let assembled = ''
  for await (const chunk of stream) {
    assembled += chunk.choices[0]?.delta?.content ?? ''
    yield assembled
  }
}

const FALLBACK_DISCLAIMER =
  'This is general information, not medical advice or a diagnosis. ' +
  'Consult a licensed pharmacist or doctor. In an emergency, call 119.'

/**
 * Coerces whatever the model streamed into a renderable response (NFR-04, TC-03).
 *
 * The backend does this too, for non-streaming calls. Streaming deltas cannot be validated
 * server-side — the JSON only becomes parseable once the last chunk lands — so the guarantee
 * has to be repeated here. Never let a malformed answer reach the render tree.
 */
export function parseReply(raw: string): MedicalResponse {
  const candidate = stripMarkdownFence(raw.trim())
  try {
    const parsed = JSON.parse(candidate) as Partial<MedicalResponse>
    return {
      reply: parsed.reply ?? '',
      urgency: parsed.urgency ?? 'see_pharmacist',
      medications: parsed.medications ?? [],
      map: parsed.map ?? null,
      disclaimer: parsed.disclaimer || FALLBACK_DISCLAIMER,
    }
  } catch {
    // Prose is still useful to a sick person. Show it rather than an error.
    return {
      reply: raw,
      urgency: 'see_pharmacist',
      medications: [],
      map: null,
      disclaimer: FALLBACK_DISCLAIMER,
    }
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
