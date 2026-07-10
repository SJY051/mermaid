import { useState } from 'react'
import type OpenAI from 'openai'
import { Banner } from '@astryxdesign/core/Banner'
import { Button } from '@astryxdesign/core/Button'
import { TextArea } from '@astryxdesign/core/TextArea'
import { Card } from '@astryxdesign/core/Card'
import { parseAnswer, streamChat } from './lib/openaiClient'
import type { MermAidAnswer } from './lib/types'
import { getDeviceId } from './lib/storage'
import { AllergyBadge } from './components/AllergyBadge'
import { NearbyFacilities } from './components/NearbyFacilities'

/**
 * Walking skeleton for UI-01.
 *
 * It proves the thing that had to be proven end to end: the official `openai` SDK talks to
 * our Spring proxy with nothing but a changed `baseURL`, and a model that ignores the
 * response schema still renders (TC-01, TC-03).
 *
 * The medication cards, the map overlay (UI-02), and the detail drawer (UI-03) are yours
 * to build. See docs/specs/001-foundation/tasks.md for who owns what.
 */
export default function App() {
  const [input, setInput] = useState('')
  const [streaming, setStreaming] = useState(false)
  const [answer, setAnswer] = useState<MermAidAnswer | null>(null)

  // Reserved so the profile endpoints have an identity to attach to (FR-04).
  void getDeviceId()

  async function send() {
    if (!input.trim() || streaming) return

    setStreaming(true)
    setAnswer(null)

    const messages: OpenAI.ChatCompletionMessageParam[] = [{ role: 'user', content: input }]

    try {
      let latest = ''
      for await (const partial of streamChat(messages)) {
        latest = partial
      }
      // Only parse once the stream has finished. A truncated JSON object must never
      // reach a medication card — see spec §5-4 and openaiClient.streamChat.
      setAnswer(parseAnswer(latest))
    } catch (e) {
      setAnswer(parseAnswer(`Sorry — something went wrong. ${(e as Error).message}`))
    } finally {
      setStreaming(false)
    }
  }

  const emergency = answer?.urgency.level === 'emergency'

  return (
    <main className="mx-auto flex h-full max-w-2xl flex-col gap-4 p-6">
      <header>
        <h1 className="text-2xl font-semibold text-primary">mermAid</h1>
        <p className="text-sm text-secondary">
          Find care and understand Korean medicines — in English, without signing in.
        </p>
      </header>

      {/* SA-02: the disclaimer is always on screen, not tucked inside a response. */}
      <Banner
        status="warning"
        title="This is general information, not medical advice."
        description="Consult a licensed pharmacist or doctor. In an emergency, call 119."
      />

      {/* SA-04: nudge people away from typing identifying details into a chat box. */}
      <TextArea
        label="Describe your symptoms"
        description="Please do not enter your passport number or date of birth."
        placeholder="I have a sore throat and a fever, and it's 11pm."
        rows={4}
        value={input}
        onChange={(value) => setInput(value)}
      />

      <Button
        label={streaming ? 'Thinking…' : 'Ask'}
        variant="primary"
        isLoading={streaming}
        isDisabled={!input.trim()}
        onClick={send}
      />

      {answer && (
        <section className="flex flex-col gap-3">
          {emergency && (
            <Banner
              status="error"
              title={answer.urgency.title}
              description={answer.urgency.message}
            />
          )}

          <p className="whitespace-pre-wrap text-primary">{answer.summary}</p>

          {/* TODO(team): proper medication cards with dosage pictograms — DEV-308 */}
          {answer.drugs.map((drug) => (
            <Card key={drug.id}>
              <div className="flex flex-col gap-2 p-4">
                <h2 className="text-lg font-medium text-primary">{drug.productNameKo}</h2>
                {drug.productNameEn && (
                  <p className="text-sm text-secondary">{drug.productNameEn}</p>
                )}
                <AllergyBadge check={drug.allergyCheck} />
                {drug.warnings.map((w, i) => (
                  <p key={i} className="text-sm text-secondary">
                    {w}
                  </p>
                ))}
              </div>
            </Card>
          ))}

          {/* The assistant asks for the map through `uiActions`; it never calls a tool (spec §2-1). */}
          {answer.uiActions.map((action, i) =>
            action.type === 'OPEN_FACILITY_MAP' ? (
              <NearbyFacilities
                key={i}
                types={action.payload.types}
                radiusM={action.payload.radiusM}
                openNow={action.payload.openNow}
              />
            ) : null,
          )}

          {/* Provenance. Never present fixture data as live (spec §2-14). */}
          {answer.dataStatus === 'fixture' && (
            <p className="text-xs text-secondary">
              Showing sample data — the live government data source was unavailable.
            </p>
          )}
        </section>
      )}
    </main>
  )
}
