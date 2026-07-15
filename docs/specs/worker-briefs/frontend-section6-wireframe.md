# Worker brief — §6 frontend, wireframe-following UI (overnight, throwaway-if-bad)

**For:** a Sol Ultra worker (ASQi dispatches). **Reviewer:** 한결 + ASQi on wake.
**Disposition:** exploratory — we keep it if the review passes, discard it if not. Branch off
`main` (`be68c88`), own branch `feat/DEV-308-drug-card` (or similar per item).

## What to build

The remaining wireframe-following pieces of the mobile UI (spec `docs/specs/002-mobile-ui/spec.md`
§6), following the design in `docs/specs/002-mobile-ui/handoff/` (the Claude Design wireframe):

1. **Drug card (DEV-308, FE-1)** — render a `MermAidAnswer` drug card in the chat answer: product
   name, ingredients, official guidance, prescription status, source/provenance, allergy state.
2. **Saved screen** — the saved-places list (reads from `lib/storage.ts`).
3. **Settings screen shell** — the settings surface (layout + the non-safety toggles).

Use the existing astryx design system and match the surrounding components. This is FE-1/FE-2 (our)
lane — do **not** touch `FacilityMap.tsx` / `MapScreen.tsx` (open PR #58 owns those).

## Non-negotiable safety guardrails (this is the point of the brief)

The wireframe is *design*, not the safety invariants. These override the wireframe on any conflict:

- **§2-2 allergy states.** The drug card MUST render `no_match_found` as neutral — **no green
  badge, never the word "safe"**, no reassurance. `blocked` / `warning` / `no_match_found` /
  `unknown` are four distinct states. The existing `frontend/src/components/AllergyBadge.test.tsx`
  MUST stay green — extend it, never weaken it.
- **§2-9 provenance.** Fixture data is labelled wherever shown; never presented as live. A card with
  fixture provenance says so.
- **§2-1 disclaimer** stays visible; the card never diagnoses.
- **§2-5 storage.** `frontend/src/lib/storage.test.ts` MUST stay green. Chat stays in
  sessionStorage; saved places / settings in localStorage per the hybrid model.
- **Settings allergy opt-in is OUT OF SCOPE.** The `remember_allergies` consent + allergy profile is
  a separate feature (spec 005, medical profile). In Settings, leave a **stub/placeholder with a
  `TODO(DEV-56x)`** — do NOT implement consent or write any allergy data. Guessing here is worse
  than leaving it.
- **When a safety state is ambiguous, do NOT guess — leave a `TODO` and a comment.** A gap we can
  fill on review is fine; a confidently-wrong safety state is not.

## Completion criteria (show output)
- `cd frontend && pnpm test` green (existing safety tests included), `pnpm build` green.
- New logic has tests; for each safety-state test, break it once and confirm it goes red.
- Report: what you built, which wireframe screens, any safety state you left as TODO and why.
- Commit on the branch, English, Conventional Commits, one screen per commit where practical.

## Explicitly allowed to be imperfect
This is throwaway-if-bad. Prefer honest TODOs over invented safety behaviour. We review on wake and
either keep it or drop it — a partial, correct, well-marked result beats a complete, guessed one.
