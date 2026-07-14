# Worker brief — DEV-560 free-text allergy binding (implement the scaffold)

**For:** a Codex/Sol worker. **Orchestrator/verifier:** 한결. **This is safety-critical (allergy).**
**Base:** branch `feat/DEV-560-allergy-binding-scaffold` (already has the spec + scaffold commits;
do NOT branch off main). **Spec:** `docs/specs/005-medical-profile/spec.md` — read it first.

## Goal

Fill the scaffold so a free-text allergy declaration ("I'm allergic to ibuprofen") actually reaches
the allergy comparison, closing **EX-02**. Today the free-text path detects the allergy *state* but
never captures the allergen *value*, so the avoided set is empty and the check can return
`no_match_found` — which reads as "fine" but means "not checked" (against AGENTS.md 2-2).

The scaffold already drew every seam. Your job is to make the four `@Disabled` tests real and green,
add the two that live elsewhere, and wire the path. Nothing here invents medical judgment — the
server binds only what the user typed, only through signed rows.

## Do exactly these

1. **`AllergenBinder.bind(candidateAllergens, userText)`** (`drug/AllergenBinder.java`)
   - **FR-001 origin binding:** keep only candidates that occur (case-insensitively) in `userText`.
     A model-proposed allergen the user never typed must not inject *or* clear an allergy. This is
     the exact EX-01 principle already used for product names in `SearchTermExtractor`.
   - **FR-002 signed rows:** `normalizer.normalize(name)`; put a key into `avoidedKeys` only when its
     `MatchConfidence` is `EXACT` or `SYNONYM`. `PARTIAL`/`UNKNOWN` go to `unresolved` (warning at
     most, never a block). Set `anyResolved` accordingly.

2. **Pass-1 `allergens` field** (`chat/SearchTermExtractor.java`, seam already marked)
   - Add `allergens` to `SCHEMA_JSON` (array of strings, small `maxItems`), parse it like
     `ingredients`/`productNames`, and origin-bind to the user text. Carry it to
     `DrugContextRetriever` (expand `RetrievalQuery` or add a parallel carrier — your call, keep it
     clean and update all construction sites + `EMPTY`).
   - Update the pass-1 prompt so the model *proposes* candidate allergens; it never has authority.

3. **`DrugContextRetriever`** (seam already marked)
   - Union `AllergenBinder`'s `avoidedKeys` with the existing `exclude_ingredients`-derived set
     before `drugService.retrieve(...)`.
   - **FR-004 fail-closed:** if an allergy is declared (`AllergyDeclaration` fires or
     `exclude_ingredients` non-empty) but the merged avoided set is empty, return the
     `AllergyClarification` answer instead of retrieving. Keep model-proposed ingredients suppressed
     (SA-08). Never a silent `no_match_found`.

4. **`AllergyClarification`** (`chat/AllergyClarification.java`)
   - Implement the factory that returns a `MermAidAnswer` carrying `QUESTION` in
     `clarifyingQuestions[]`, naming no medicine, `drugs` empty, disclaimer intact.
   - **FR-010:** it is server-authored — never model text. A prompt-injected model must not be able
     to suppress or reword it.

5. **FR-006 normalization** — extend only the **reviewed alias/spelling** path (case-fold plus a
   reviewed alias list). No fuzzy/edit-distance matching. Normalization feeds lookup; it does not
   create block authority.

6. **Tests** — un-disable the four in `AllergenBinderScaffoldTest`, and add:
   - the SC-001 test in a `DrugContextRetriever` test (declared + empty avoided → `AllergyClarification.QUESTION`, no `no_match_found`);
   - Each must be **red before your change, green after** — name the mutation for each in your report.

## Out of scope (do NOT touch)
- **`synonyms.tsv` signed/`reviewer` column** — human-only (AGENTS.md 2-6). You may add *unsigned*
  alias rows for normalization, but an unsigned row must never let an allergy `block` (2-12).
- **FR-008 masking** and the **frontend consent UI** (FR-007 client side) — next slice. If the
  server-side profile→AI path already exists and masking is a one-line seam, add a `TODO(DEV-561)`;
  do not build it here.
- **AnswerValidator / AnswerContractTest** (DEV-601) and the **#55 semantic gate** — different work.
- Cross-reactivity (AR-01 clinical boundary) — an unmatched sibling drug stays `no_match_found`
  with correct copy, never a green badge or the word "safe".

## Completion criteria (show the output)
- `cd backend && ./gradlew clean test` → exit 0, all green. Report the count and, for every new/enabled
  test, the mutation that turns it red.
- If you touched `frontend/` at all: `pnpm test && pnpm build` green.
- No declared-allergy input can produce `no_match_found` with an empty avoided set (SC-001).
- A model-proposed allergen absent from the user text has zero effect on allergy state (SC-003).
- Commit on the scaffold branch, English, Conventional Commits, one concern per commit.
- Report: what you filled, each mutation→red proof, and anything you refused as out-of-scope.
