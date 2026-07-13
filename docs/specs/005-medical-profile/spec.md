---
title: Medical profile and free-text allergy safety
status: approved
created: 2026-07-13
owner: 윤서진
tags: [safety, allergy, DEV-603-follow, EX-02]
---

# Medical profile and free-text allergy safety

## Context & problem

An allergy reaches the server two ways today (`DrugContextRetriever.retrieve`):
the `mermaid.exclude_ingredients` extension, or the person's own words
(`AllergyDeclaration.presentIn` matches "allergy/allergic/anaphylaxis/intolerant").

The free-text path detects the allergy **state** but never captures the allergen
**value**. So when someone types "I am allergic to ibuprofen" without the extension,
`allergyDeclared` is true, the model's proposed ingredients are suppressed, but the
avoided-ingredient set passed to retrieval is **empty**. The allergy comparison then
has nothing to avoid and can return `no_match_found` — which reads as "nothing to
worry about" but means "we did not check." This is finding **EX-02** from the DEV-603
audit ([`docs/security/DEV-603-injection-review.md`](../../security/DEV-603-injection-review.md)),
and it sits directly against invariant **AGENTS.md 2-2** ("`no_match_found` is not safe").

The app also has no first-class medical profile: allergy is ad-hoc per request. The
hybrid-storage design already reserves an opt-in server-side allergy profile
(`user_profile` / `allergy_ingredient`, gated by `remember_allergies`, off by default),
but nothing populates or reads it safely yet.

This spec defines, in one place: (a) safely turning a free-text allergen into a
canonical ingredient the allergy check can use, (b) never letting a declared-but-
unresolved allergy become a silent `no_match_found`, (c) normalizing user input before
binding, (d) an optional opt-in allergy profile, and (e) a bounded, injection-safe way
for the model to hand extracted allergen text to the server. The **#55 semantic output
gate** will later build on the same "model explains, server owns facts" boundary.

Who it is for: an English-speaking, not-signed-in user describing symptoms in Korea,
who may state an allergy in prose and must never be shown a medicine they react to as
if it were unproblematic.

## Goals / non-goals

- **Goals**
  - A free-text allergen name binds to a canonical ingredient **only** through the
    reviewed, human-signed synonym table (`synonyms.tsv`), and **only** when the name
    actually occurs in the user's own text (origin binding, the EX-01 principle).
  - A declared allergy whose allergen cannot be resolved to a signed ingredient never
    silently produces `no_match_found`: it asks a **clarifying question** or fails closed.
  - User input is **normalized** (case-folding, and a reviewed alias/typo path) before
    binding, so "Ibuprofin"/"IBUPROFEN" resolve like "ibuprofen".
  - An **opt-in** persisted allergy profile (off by default), local-first, that is
    masked when included in an AI call and can be deleted (satisfies the CRUD D demo).
  - Any new model-to-server surface for allergen text stays inside the DEV-603 threat
    model: it accepts only server-validated ingredient tokens, never raw model prose.

- **Non-goals**
  - A cross-reactivity table (e.g. NSAID class). AR-01 is an accepted clinical-review
    boundary; we do not invent cross-reactivity. An unmatched sibling drug stays
    `no_match_found` with correct copy, not a green badge.
  - Letting the model author allergy facts, the avoided set, or the binding table
    (AGENTS.md 2-6). The model may *point at* a candidate; the server decides.
  - The #55 semantic output gate (diagnosis/cure/"safe" prose). Separate spec, builds
    on this one.
  - Medical history beyond allergies (conditions, medications, pregnancy state as
    stored profile). Future expansion.

## Requirements

### Allergen extraction and binding
- **FR-001**: When an allergy is declared in free text, the system MUST extract
  candidate allergen name(s) via a pass-1 `allergens` field (the model proposes, bound
  exactly like product names) and bind each to a canonical ingredient via `synonyms.tsv`.
  A candidate that does not occur literally (case-insensitively) in the **user's own
  text** MUST be discarded (origin binding, EX-01). Decided 2026-07-13.
- **FR-002**: Binding MUST use only **human-signed** rows of `synonyms.tsv`. An
  unreviewed or unsigned mapping MUST NOT let an allergy `block` a medicine; at most it
  contributes a `warning`, per AGENTS.md 2-6 and 2-12.
- **FR-003**: The model MUST NOT author the avoided-ingredient set. If a new
  model-facing surface (see FR-009) proposes an allergen, the server MUST re-validate it
  against FR-001 and FR-002 before it affects any allergy state.

### Fail-closed on unresolved allergy
- **FR-004**: When an allergy is declared but **no** allergen resolves to a signed
  ingredient, the system MUST NOT return `no_match_found` for that turn. It MUST instead
  return a **clarifying question** asking the user to name or confirm the ingredient,
  and MUST continue to suppress the model's own drug suggestions (SA-08).
- **FR-005**: When an allergy is declared and at least one allergen resolves, the
  existing block/warning/no_match_found/unknown states apply unchanged, computed against
  the resolved avoided set.

### Normalization
- **FR-006**: User-supplied allergen text MUST be normalized before binding:
  case-folding plus a **reviewed alias/spelling list** for common variants (no fuzzy /
  edit-distance matching — a wrong bind is a missed or false allergy). Normalization
  MUST NOT introduce a binding that no signed row supports (it feeds lookup, it does not
  create authority). Decided 2026-07-13.

### Opt-in profile
- **FR-007**: An allergy profile MUST be **opt-in and off by default**
  (`remember_allergies`). When off, both read and write paths drop the list
  (AGENTS.md 2-5). When on, it persists in the **server DB** (`user_profile` /
  `allergy_ingredient`, per the ERD) and is deletable (CRUD D). Decided 2026-07-13.
- **FR-008**: When a stored profile is included in an AI call, sensitive values MUST be
  **masked** so raw allergen/identity text is not sent to the provider; only the
  server-side allergy comparison uses the unmasked canonical ingredients.

### Model-to-server surface (the "AI tool surface")
- **FR-009**: The model-facing surface is the pass-1 `allergens` field (FR-001), not a
  free tool call (the assistant does not call tools, spec 2-1). It MUST be added to the
  **DEV-603 threat model**, MUST accept only values that pass FR-001/FR-002 server-side,
  and MUST fail closed on anything else. Decided 2026-07-13.
- **FR-010**: The FR-004 clarifying question is safety-critical and MUST be
  **server-authored** (like the emergency code path), not model-authored — a
  prompt-injected model must not be able to suppress or reword it. It reuses the
  `clarifyingQuestions[]` shape but with server-controlled content when an allergy is
  unresolved. Decided 2026-07-13.

## User scenarios

### Free-text allergen resolves (P1)
- **Given** the user is not signed in and `remember_allergies` is off
- **When** they type "I'm allergic to ibuprofen, what can I take for a headache?"
- **Then** the server extracts "ibuprofen", confirms it occurs in the text, binds it via
  a signed `synonyms.tsv` row, and any ibuprofen-containing retrieved product is
  `blocked` — never `no_match_found`, never a green badge, never the word "safe".

### Declared allergy, unresolved allergen (P1)
- **Given** an allergy is declared but the allergen is misspelled beyond the reviewed
  alias path, or names something not in the signed table
- **When** the turn is processed
- **Then** the system returns a clarifying question ("Which ingredient are you allergic
  to?") rather than `no_match_found`, and the model's own drug picks stay suppressed.

### Typo / case variant (P2)
- **Given** the user types "allergic to Ibuprofin"
- **When** normalization runs
- **Then** the reviewed alias path resolves it to the canonical ingredient and it binds
  as in the first scenario; an unreviewable variant falls through to the clarifying
  question, never to a silent pass.

### Opt-in profile round-trip (P2)
- **Given** the user explicitly turns on `remember_allergies`
- **When** they save an allergy, use the app across turns, then delete it
- **Then** the profile persists only while opted in, is masked in AI calls, and is fully
  removed on delete (and on opting out).

## Success criteria

- **SC-001**: No input that declares an allergy can produce `no_match_found` with an
  empty avoided set. Enforced by a test that is red before FR-004 and green after.
- **SC-002**: An allergen bound to a medicine comes only from a signed `synonyms.tsv`
  row; an unsigned row yields at most `warning`. Mutation: unsign the row → block must
  disappear.
- **SC-003**: A model-proposed allergen that does not occur in the user text has no
  effect on allergy state. Mutation: drop the origin check → a regression turns red.
- **SC-004**: With `remember_allergies` off, no allergy value is written or read; with
  it on, an AI call carries masked values only. Enforced by storage/masking tests
  (extends `AllergyBadge.test.tsx` / `storage.test.ts`).
- **SC-005**: `./gradlew test` and `pnpm test` pass; every new safety check has a
  named mutation that turns it red.

## Decisions (resolved 2026-07-13)

- **Allergen extraction** → pass-1 `allergens` field: the model proposes, the server
  re-validates via origin binding (FR-001) and signed rows (FR-002). Honors the team's
  "AI-facing surface" decision while keeping the server the sole authority (FR-003/009).
- **FR-009 surface** → the pass-1 `allergens` field above; no free tool call, no new
  `uiAction`. Added to the DEV-603 threat model.
- **Normalization** → reviewed alias/spelling list only; no fuzzy/edit-distance
  matching (FR-006).
- **Profile storage** → server DB (`user_profile` / `allergy_ingredient`), opt-in and
  off by default; gives the graded CRUD demo (FR-007).
- **Clarifying-question transport** → server-authored, reusing `clarifyingQuestions[]`
  shape; never model-authored for the unresolved-allergy prompt (FR-010).

## Open questions

- None blocking scaffold. Remaining detail (exact `allergen`→ingredient alias-list
  format, and the `remember_allergies` consent UI copy) resolves during scaffold.

## Future expansion

If this grows past a single slice, add `plan.md` (HOW: extraction component, binding
service, masking, storage) and `tasks.md` (DO: DEV-56x WBS) alongside this file. The
#55 semantic output gate is a sibling spec that shares this spec's "server owns safety
facts" boundary; sequence #55 after this. Medical history beyond allergies (conditions,
pregnancy) would extend the same profile model.
