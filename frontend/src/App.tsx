import { useState } from 'react'
import type OpenAI from 'openai'
import { parseReply, streamChat } from './lib/openaiClient'
import type { MedicalResponse } from './lib/types'
import { getDeviceId } from './lib/deviceId'

/**
 * Walking skeleton for UI-01.
 *
 * It proves the one thing that had to be proven end to end: the official `openai` SDK talks
 * to our Spring proxy with nothing but a changed `baseURL`, and a model that ignores the
 * response schema still renders (TC-01, TC-03).
 *
 * Everything visual is deliberately plain. The medication cards (UI-01), the map overlay
 * (UI-02), and the detail drawer (UI-03) are yours to build.
 */
export default function App() {
  const [input, setInput] = useState('')
  const [streaming, setStreaming] = useState(false)
  const [response, setResponse] = useState<MedicalResponse | null>(null)

  // Reserved so the profile endpoints have an identity to attach to (FR-04).
  void getDeviceId()

  async function send() {
    if (!input.trim() || streaming) return

    setStreaming(true)
    setResponse(null)

    const messages: OpenAI.ChatCompletionMessageParam[] = [{ role: 'user', content: input }]

    try {
      let latest = ''
      for await (const partial of streamChat(messages)) {
        latest = partial
        // While the JSON is still arriving it will not parse; show the raw text meanwhile.
        setResponse(parseReply(latest))
      }
    } catch (e) {
      setResponse(parseReply(`Sorry — something went wrong. ${(e as Error).message}`))
    } finally {
      setStreaming(false)
    }
  }

  return (
    <main className="mx-auto flex h-full max-w-2xl flex-col gap-4 p-6">
      <header>
        <h1 className="text-2xl font-semibold">mermAid</h1>
        <p className="text-sm opacity-70">
          Find care and understand Korean medicines — in English, without signing in.
        </p>
      </header>

      {/* SA-02: the disclaimer is always on screen, not tucked inside a response. */}
      <p className="rounded border border-amber-400/50 bg-amber-50 p-3 text-xs text-amber-900">
        This is general information, not medical advice or a diagnosis. Consult a licensed
        pharmacist or doctor. <strong>In an emergency, call 119.</strong>
      </p>

      {/* SA-04: nudge people away from typing identifying details into a chat box. */}
      <label className="flex flex-col gap-2">
        <span className="text-sm">
          Describe your symptoms. Please do not enter your passport number or date of birth.
        </span>
        <textarea
          className="min-h-24 rounded border p-2"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="I have a sore throat and a fever, and it's 11pm."
        />
      </label>

      <button
        className="self-start rounded bg-slate-900 px-4 py-2 text-white disabled:opacity-40"
        onClick={send}
        disabled={streaming || !input.trim()}
      >
        {streaming ? 'Thinking…' : 'Ask'}
      </button>

      {response && (
        <section className="flex flex-col gap-3">
          <p className="whitespace-pre-wrap">{response.reply}</p>

          {response.urgency === 'emergency' && (
            <p className="rounded bg-red-600 p-3 font-semibold text-white">
              This may be an emergency. Call 119 now.
            </p>
          )}

          {/* TODO(team): render medication cards with dosage pictograms (UI-01). */}
          {response.medications.length > 0 && (
            <pre className="overflow-x-auto rounded bg-slate-100 p-3 text-xs">
              {JSON.stringify(response.medications, null, 2)}
            </pre>
          )}

          {/* TODO(team): swap this for the Naver map (UI-02) — see hooks/useNaverMap.ts.
              `response.map` carries the query for GET /api/v1/facilities. */}
          {response.map && (
            <p className="rounded border border-dashed p-3 text-sm">
              Map would show <strong>{response.map.type}</strong> within{' '}
              {response.map.radiusMeters}m
              {response.map.openNow ? ', open now' : ''} — {response.map.reason}
            </p>
          )}
        </section>
      )}
    </main>
  )
}
