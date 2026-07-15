# Worker brief — DEV-561 allergen picker overlay (FR-014 v1)

**Repo/worktree:** `/private/tmp/mermaid-dev561` (branch `feat/DEV-561-allergen-picker`, already
carries the backend endpoint and the revised spec — read `docs/specs/005-medical-profile/spec.md`
FR-014/FR-015 first). **Frontend only**; the backend endpoint exists and is tested.

## Goal and why

When the chat answer is the server-authored allergy clarification (`answerId ===
"allergy-clarification"`), the user must be able to state their allergy **by selection, not by
typing**: a free-text reply loops back to the same clarification (the server refuses to trust
free-text allergens by design — four review P0s proved extraction completeness unverifiable). The
picker is the loop-closer: selected canonical keys ride on every subsequent request as
`mermaid.exclude_ingredients`, which is the one channel the server lets authorize retrieval.

## What to build

1. **`frontend/src/components/AllergenPicker.tsx`** (new, self-contained — keep the ChatScreen
   diff minimal: one conditional render + one callback; a parallel session is editing ChatScreen's
   composer, so the less you touch there the better).
   - Fetches `GET /api/v1/ingredients/allergen-options` → `[{key, label}]`. Options come ONLY
     from the server — never hard-code an ingredient list (it would drift from the dictionary).
   - Multi-select (checkbox list or chips; match astryx components used nearby), a confirm
     button, and a **"My allergy isn't listed"** escape that dismisses the picker and leaves the
     existing see-a-pharmacist copy visible. The escape sends nothing.
   - Renders as an explicit overlay/panel directly above the composer with a heading like
     "Tell us your allergy — pick the exact ingredient". Wording rules: never the word "safe",
     no green/success styling anywhere near allergy state (AGENTS.md §2-2), plain English a sick
     reader can follow.
   - Fetch failure → the picker simply does not render (the clarification copy already tells the
     user what to do); no invented fallback options.
2. **Wiring** in `frontend/src/lib/chatSession.tsx` (and the request path in
   `frontend/src/lib/openaiClient.ts` if needed):
   - Show the picker when the LATEST answer's `answerId === "allergy-clarification"` and the
     session has no confirmed selection yet; hide after confirm or escape.
   - On confirm: store the selected keys in **sessionStorage** (follow the existing
     `lib/storage.ts` patterns and its schema-versioning; NEVER localStorage — AGENTS.md §2-5,
     chat data dies with the tab) and include `mermaid: { exclude_ingredients: [...] }` as a
     top-level body field on EVERY subsequent chat request (the `openai` JS SDK forwards unknown
     top-level body keys; see `MermaidRequestExtension` javadoc in the backend).
   - v1 is **manual re-ask**: after confirm, focus the composer with placeholder/hint copy like
     "Ask your question again — answers will avoid your selected ingredients." Do NOT auto-resend
     anything (v2 will; out of scope).
   - Allow re-opening the picker later (e.g. a small "Edit allergy list" affordance near the
     composer when a selection exists) pre-filled with the stored keys.
3. **Tests** (vitest + testing-library, alongside the existing `ChatScreen.test.tsx` patterns —
   note existing tests `user.clear()` the composer before a second question; keep doing that):
   - picker appears exactly on the clarification answer, not on ordinary answers;
   - confirm stores keys in sessionStorage (not localStorage) and the NEXT `streamChat` request
     body carries `mermaid.exclude_ingredients` with exactly the selected keys;
   - the escape sends nothing and no `mermaid` field appears on the next request;
   - options render from the mocked fetch; no option text is invented client-side;
   - existing safety tests (`AllergyBadge.test.tsx`, `storage.test.ts`) stay green — extend,
     never weaken.
   - For each new safety-relevant test, break the code once and confirm it goes red, then
     restore. **Restore with `cp` backups, never `git checkout`** (a checkout reverts to the
     commit and destroys your own uncommitted work).

## Out of scope
- FR-007 opt-in profile persistence (server DB) — later slice.
- v2 auto-resend.
- Any backend change. Any FacilityMap/Map/Saved/Settings file.

## Completion criteria (show the output)
- `cd frontend && pnpm test` exit 0 (existing + new), `pnpm build` exit 0.
- Report: files touched, each new test's name, which mutation you ran per safety test and that it
  went red. Judged criteria: failure means non-zero exit, no output, or zero files — warnings are
  not failure.
- Commit on this branch, Conventional Commits, English (e.g. `feat(web): show the allergen picker
  on the allergy clarification (DEV-561)`), one concern per commit. Do not push.
