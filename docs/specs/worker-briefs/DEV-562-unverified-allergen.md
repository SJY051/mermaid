# Worker brief — DEV-562 unverified allergen warnings (DEV-561 follow-up)

**Worktree:** `/private/tmp/mermaid-dev562` (branch `feat/DEV-562-unverified-allergen`, off main
with #65 merged). **Read first:** `docs/specs/005-medical-profile/spec.md` — the new FR-016/017/018
block ("Unverified allergens") is the contract; AGENTS.md §2, §8–10 apply. Backend + frontend.

## Goal and why

DEV-561's picker covers allergens the dictionary knows. "My allergy isn't listed" currently ends
drug lookup for the conversation — safe, but the person gets nothing. This slice lets the user add
an unlisted allergen as free text; the server then warns on **name matches** against retrieved
products' ingredients. A name match has no clinical authority, so it warns and never blocks, and
the session carries a server-authored caveat so no answer reads as verified. Strictly better than
ignoring the allergen or refusing to look.

## Backend

1. `MermaidRequestExtension`: parse `mermaid.unverified_allergens` alongside
   `exclude_ingredients`, applying THE SAME completeness rules (entry/length bounds, blank
   droppable, non-textual or wrong-shaped at any level → `incomplete`). Extend the returned
   record; keep one rule at every level.
2. `DrugContextRetriever` gate: non-empty unverified list sets `allergyDeclared` (SA-08
   suppression applies; only user-named products are retrieved). Unverified entries do NOT fail
   closed by themselves and are NOT added to `avoidedKeys`.
   **They must never reach an upstream API query — grep the query path and prove it in a test.**
3. `AllergyChecker` (or the retrieval mapping): for each retrieved product, compare each
   unverified allergen against the product's `ingredientsEn` with the existing case-folded
   word/substring machinery (`IngredientNormalizer.compare` PARTIAL). Match → `AllergyCheck`
   status `warning` with copy naming the matched text as a NAME match only + pharmacist. If the
   verified path already yielded `blocked`, blocked wins.
4. Server-authored session caveat: when unverified allergens are present, the server appends one
   warning to the final answer in post-processing (where provenance/`source_refs` are grounded —
   the model must not be able to omit it): the named allergens were checked by name only; a
   pharmacist must confirm. Never the word "safe" anywhere.

## Frontend

5. `AllergenPicker`: add a free-text input with autocomplete against the fetched options. Text
   resolving to an option → that option becomes checked (verified path). No match → an
   **unverified chip**, visually distinct (no green/success anywhere, §2-2), labeled
   "name-match warnings only — a pharmacist can fully check this one". Chips removable.
6. Wiring (`chatSession` / `openaiClient`): persist `unverifiedAllergens: string[]` in
   `ChatSession` (sessionStorage, §2-5, exactly like `allergies`); send as
   `mermaid.unverified_allergens` on every request when non-empty; clear on new conversation.
7. Dismiss semantics per FR-018: a clarification-opened picker closed WITHOUT covering the
   declaration (no new selection or chip) keeps the existing `unverifiableAllergy` lock; an
   Edit-mode close with no changes is a plain cancel.

## The state-space checklist (test ALL of these — this is the point)

Safety-invariant UI must be designed against the whole state space, not the happy path. Each of
these earned a real P0 in review when missed; write a test for each:

- [ ] Second free-text declaration after chips/selections exist → picker re-opens, pre-filled
      with BOTH lists.
- [ ] While the picker is open there is no composer and no Ask (it REPLACES the composer — keep).
- [ ] Reload/remount restores selections, chips, and the lock (persisted, not view state).
- [ ] New conversation clears selections, chips, and the lock.
- [ ] Edit re-entry shows both lists; cancel without changes changes nothing.
- [ ] A request during any pending-picker state is impossible (structural, not a disabled check).
- [ ] Backend: unverified text appears in NO upstream query (test proves it); wrong-shaped or
      out-of-bounds unverified field → clarify; match → warning never blocked; blocked-by-verified
      stays blocked; session caveat present whenever unverified allergens exist.

## Out of scope
- FR-007 profile persistence (server DB). v2 auto-resend. Any facility/map/saved file.

## Completion criteria (show output)
- `cd backend && ./gradlew test` exit 0; `cd frontend && pnpm test` exit 0; `pnpm build` exit 0.
- For each safety test: name the mutation you ran and show it red, then restored
  (**restore with `cp` backups, never `git checkout`**).
- Report files touched + new test names. Warnings are not failure; failure = non-zero exit, no
  output, or zero files.
- Commit on this branch, Conventional Commits, English; do not push. Spec is already amended —
  do not edit `docs/` except to fix a factual mismatch you introduce.
