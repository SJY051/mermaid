---
title: Worker B — 008 onboarding
status: ready
created: 2026-07-15
owner: 윤서진
spec: docs/specs/008-onboarding/spec.md
verification: docs/specs/worker-briefs/VERIFICATION.md
base: main, after worker A's appearance work has merged (you style with A's tokens, not before them)
branch: feat/DEV-008-onboarding
---

# Worker B — three screens, before anyone is unwell

Read [`docs/specs/008-onboarding/spec.md`](../008-onboarding/spec.md). Implement it.

**Every row of [VERIFICATION.md](VERIFICATION.md) applies to you and is part of "done".**

---

## Why this is not a welcome screen

The missing welcome screen is the small half. The expensive half is the location permission.

`MapScreen` calls `resolveLocation()` on mount, so the **browser's own permission dialog is the first
explanation anyone gets**. Someone who has just landed, is ill, and cannot read the signs is being
asked by a grey system box to share where they are. They press Deny — and **the denial is permanent
for the origin.** No code of ours can undo it, and the browser will not ask again. We get one chance
and we currently spend it silently.

After you: we ask, with our own button, under a sentence that says why. A Deny then means the person
read it and said no, and we respect it — the Map tab keeps working exactly as it does now (manual pin,
address search, honest `fallback` notice). **It must never nag.**

## What you are building

Three screens, **skippable at any point**, shown **once**.

1. **What this is** — medicine information verified against Korean government data, explained in
   English. It never diagnoses.
2. **The disclaimer, full size** — the sentence on its own screen, once, while the reader is well.
   Plus: this conversation is not saved and dies with the tab.
3. **Why we would like your location**, then the button that asks.

Skip lands on Chat in **one tap**, from any screen. Someone in a hurry because they feel awful must
not have to read three screens to reach the box.

Seen-flag in `localStorage` (`mermaid.onboarding.v1`), not `sessionStorage` — a tab-scoped flag would
ask a returning user every time. It stores a boolean; §2-5 is about consultation content.

## The hard constraints (each is a P0 if you break it)

- **No allergy input. None.** Not a picker, not a text field, not a "do you have any allergies?"
  question. Decided 2026-07-15. The allergy list has exactly two places that write it — the chat's
  picker and Settings — and every extra state we have added around it has cost us a P0. A third would
  be a third.
- **The disclaimer strip stays on every screen**, including during onboarding (§2-1). Screen 2 is *in
  addition to* it, never instead of it.
- **Onboarding stores nothing but the seen-flag.** No name, no age, no symptoms. SA-04 tells people not
  to type identifying details into the chat box; onboarding must not ask for them itself.
- **`getCurrentPosition` must not be called on Map mount any more.** Move the call to the button.
  SC-002 asserts this by spying on it — the test goes red if `MapScreen` calls it on mount again.
- **Screen 3 must not imply that location is required.** The app works without it and says so.

## What you may not invent

The copy for these screens is not yours to write freely — a person reads it while unwell, in a second
language. Use the spec's own sentences where it gives them. Where it does not, keep it to one plain
sentence, and **list every line you had to author** in your report (VERIFICATION §3). Do not add a
tooltip, a sub-heading, an encouragement, or an empty-state line that nothing asked for.

## Boundaries

- New files, plus the minimum edits to `MobileShell` (to show onboarding) and `MapScreen` (to stop
  prompting on mount). Nothing else.
- Do not touch chat, allergy, drug, or facility logic.
- Do not add a dependency. Icons come from `lucide-react`, which worker A has already added.
- Styling uses A's tokens. Zero hard-coded hex, and `pnpm contrast` stays at 0 failures (light + dark).

## Done means

The five commands in [VERIFICATION.md §1](VERIFICATION.md), plus SC-001…SC-006 from the spec, each
asserted by a test — and a browser, driven: first run → three screens → grant, and first run → skip →
chat in one tap, and second run → straight to chat. At 320 / 390 / 768 / 1600, light and dark.
