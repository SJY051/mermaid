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

This spec defines, in one place: (a) which channel may carry an allergen into the
allergy check, and with what authority, (b) never letting a declared-but-unresolved
allergy become a silent `no_match_found`, (c) normalizing user input before binding,
and (d) an optional opt-in allergy profile. (A fifth goal — a bounded model-to-server
allergen surface — was designed 2026-07-13 and **removed 2026-07-14**; see the
requirements preamble for why.) The **#55 semantic output gate** will later build on
the same "model explains, server owns facts" boundary.

Who it is for: an English-speaking, not-signed-in user describing symptoms in Korea,
who may state an allergy in prose and must never be shown a medicine they react to as
if it were unproblematic.

## Goals / non-goals

- **Goals**
  - An allergen may authorize or constrain retrieval **only** through a channel whose
    completeness the client owns (`mermaid.exclude_ingredients`), each entry resolved
    through an exact canonical key or a human-signed `synonyms.tsv` row.
  - A free-text allergy declaration always fails closed to a **server-authored
    clarifying question** — never a silent `no_match_found`, never a retrieval-backed
    answer built on an unverifiable extraction.
  - User input on the structured path is **normalized** (case-folding, and a reviewed
    alias/typo path for lookup), so "IBUPROFEN" resolves like "ibuprofen" while an
    unsigned variant fails closed to the question.
  - An **opt-in** persisted allergy profile (off by default), local-first, that is
    masked when included in an AI call and can be deleted (satisfies the CRUD D demo).
  - No model-to-server surface for allergen text exists at all (redesigned 2026-07-14):
    the model neither proposes nor influences the avoided set.

- **Non-goals**
  - A cross-reactivity table (e.g. NSAID class). AR-01 is an accepted clinical-review
    boundary; we do not invent cross-reactivity. An unmatched sibling drug stays
    `no_match_found` with correct copy, not a green badge.
  - Letting the model author allergy facts, the avoided set, or the binding table
    (AGENTS.md 2-6). Since 2026-07-14 the model does not even *point at* a candidate:
    it has no allergen surface at all.
  - The #55 semantic output gate (diagnosis/cure/"safe" prose). Separate spec, builds
    on this one.
  - Medical history beyond allergies (conditions, medications, pregnancy state as
    stored profile). Future expansion.

## Requirements

### Allergen channels and their authority (redesigned 2026-07-14)

> **Why the redesign.** The 2026-07-13 design (FR-001/003/009/011/012 below, now
> superseded) let a pass-1 `allergens` extraction, origin-bound and signed-row-bound,
> authorize retrieval under a declared allergy. Review found four independent ways a
> stated allergen could be **lost between the user's text and the gate** — mixed
> resolution screened only "nothing resolved"; the cap clipped the fourth allergen; the
> sanitized list laundered the clipping signal; shape rejection below the cap dropped a
> candidate with no signal at all — and each loss surfaced only after a targeted fix of
> the previous one. The common cause is structural: **the server can never verify that
> an extraction from free text is complete.** So free-text extraction loses gate
> authority entirely, and only a channel whose completeness the client owns may
> authorize retrieval.

- **FR-001**: A free-text allergy declaration in the **current turn**
  (`AllergyDeclaration.presentIn`) MUST always produce the server-authored clarifying
  question (FR-010), never a retrieval-backed answer — regardless of what any
  extraction proposes and regardless of the structured list's content (new free text
  may name an allergen the structured list lacks). No allergen extraction from free
  text exists on the gate path. Supersedes FR-001/FR-011/FR-012 of 2026-07-13.
  Decided 2026-07-14.
- **FR-002**: On the structured path, an `exclude_ingredients` entry MUST resolve
  through an exact canonical key or a **human-signed** row of `synonyms.tsv`
  (`isReviewedBinding`). An unreviewed or unsigned mapping MUST NOT let an allergy
  `block` a medicine; at most it contributes a `warning`, per AGENTS.md 2-6 and 2-12.
- **FR-003**: The model MUST NOT author, propose, or influence the avoided-ingredient
  set. There is no model-facing allergen surface at all; the only allergen input is the
  client-structured `mermaid.exclude_ingredients` field, whose documented semantics are
  "the complete list of ingredients this session must avoid" — completeness is the
  client's, collected from the user by the FR-014 affordance.

### Fail-closed on unresolved allergy
- **FR-004**: When an allergy is declared (current turn, any prior user turn per
  FR-013, or a non-empty `exclude_ingredients`) and the structured avoided list is
  absent, has **any** entry that does not resolve per FR-002, or was **truncated by
  the parser's bounds** (an entry dropped for count or length means the held list is
  not the user's list — the same completeness principle as FR-001), the system MUST
  NOT return a retrieval-backed answer for that turn. It MUST return the clarifying
  question (FR-010), and MUST continue to suppress the model's own drug suggestions
  (SA-08).
- **FR-005**: Retrieval proceeds under a declared allergy ONLY when a non-empty
  structured `exclude_ingredients` list is present, **every** entry resolves per
  FR-002, and the current turn adds no new free-text declaration (FR-001 wins over
  this rule). The existing block/warning/no_match_found/unknown states then apply,
  computed against the resolved avoided set.
- **FR-013 (history scan)**: The free-text declaration scan MUST cover **every user
  message in the request**, not only the newest turn. Otherwise the bare reply to the
  clarifying question ("ibuprofen") carries no allergy keyword and proceeds unguarded —
  the person is shown the very ingredient they just declared. **The first-party client
  MUST therefore send every user turn of the session in each request**
  (`chatSession.send`), or the scan has nothing to see — the server-side scan and the
  client-side transport are one requirement, not two. Scope of the guarantee, honestly
  stated: the scan sees only what the request carries. A declaration in the **newest
  turn** always clarifies (FR-001); one in an **earlier turn** clarifies while no
  fully-resolved structured list is present. Once a complete structured list arrives
  it governs by design (the loop-closing path), so a forged earlier-turn declaration
  alongside one changes nothing — and a client that **trims** the declaration turn is
  indistinguishable from a conversation in which it never happened. Both reduce to
  the same fact: a client can always under-declare its own requests. That residual
  risk sits with any client violating the send-every-turn obligation; server-owned
  pending-allergy state is deliberately out of scope (§2-5 keeps transcripts off the
  server).
- **FR-014 (structured-reply affordance, refined 2026-07-14 PM)**: The frontend MUST
  treat the clarification answer (`answerId "allergy-clarification"`) as the signal to
  collect allergens **structurally**, as an explicit overlay above the composer (a
  hint buried in the composer is too easy to miss), offering ONLY the bounded option
  list from FR-015 plus a "My allergy isn't listed" escape that keeps the
  see-a-pharmacist copy. A bounded picker beats a free-text input because a selection
  always resolves — the typo → clarify → typo loop cannot form. Selected keys ride as
  `mermaid.exclude_ingredients` on every subsequent request in the session
  (sessionStorage, §2-5). v1 flow is **manual re-ask**: after picking, the user asks
  their question again (product names in their own words keep working); v2 MAY
  auto-resend with fixed neutral copy, relying on the FR-013 history transport for
  symptom context — never by resending the original text, whose allergy keywords
  would re-trigger FR-001. This affordance is the provision that closes the FR-001
  restriction's loop; until it ships, the degraded state is the safe one (clarify,
  name no medicine) — never the unguarded one. The scan stays conversation-wide on
  purpose: narrowing it to the newest message was considered (2026-07-14) and
  rejected — it reopens the bare-reply hole; the picker, not a narrower scan, is
  what ends the clarification loop.
- **FR-015 (bounded allergen options)**: The server MUST publish the selectable
  allergen list (`GET /api/v1/ingredients/allergen-options`, `[{key, label}]`),
  derived from the ingredient dictionary's canonical keys — never hard-coded in the
  client, which would drift from the dictionary. **Every offered option MUST resolve
  through `isReviewedBinding`** (an option that cannot bind would reintroduce the
  clarify loop the picker exists to end; enforced by test). The FR-007 opt-in profile
  MUST store allergens as the same canonical keys, so a stored profile fills
  `exclude_ingredients` directly and a profile-holding user skips the clarification
  entirely — the gate already proceeds on a fully-resolved structured list.

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
- **FR-009**: ~~The model-facing surface is the pass-1 `allergens` field.~~
  **Superseded 2026-07-14**: the pass-1 `allergens` field is removed. There is no
  model-facing allergen surface (FR-003); the DEV-603 threat-model entry for it closes
  as "surface removed". The extractor still emits `ingredients` / `productNames` only,
  and those retain their existing caps and shape gates.
- **FR-010**: The FR-004 clarifying question is safety-critical and MUST be
  **server-authored** (like the emergency code path), not model-authored — a
  prompt-injected model must not be able to suppress or reword it. It reuses the
  `clarifyingQuestions[]` shape but with server-controlled content when an allergy is
  unresolved. Decided 2026-07-13.

## User scenarios

### Free-text allergy, then the structured round-trip (P1)
- **Given** the user is not signed in and `remember_allergies` is off
- **When** they type "I'm allergic to ibuprofen, what can I take for a headache?"
- **Then** the server returns the clarifying question (FR-001); the frontend renders
  the allergen affordance (FR-014); the user enters "ibuprofen"; the next request
  carries `exclude_ingredients: ["ibuprofen"]`; it resolves via a signed row, retrieval
  proceeds with it avoided, and any ibuprofen-containing retrieved product is
  `blocked` — never `no_match_found`, never a green badge, never the word "safe".

### Bare reply to the clarifying question (P1)
- **Given** the previous turn declared an allergy in free text and got the question
- **When** the user replies just "ibuprofen" with no structured list (e.g. an older or
  third-party client without the FR-014 affordance)
- **Then** the history scan (FR-013) keeps the allergy context: the turn returns the
  clarifying question again, never an unguarded retrieval that could show them an
  ibuprofen product.

### Unresolvable structured entry (P2)
- **Given** the structured list contains a misspelling beyond the reviewed alias path,
  or something not in the signed table (e.g. "Ibuprofin" — the in-code spelling alias
  feeds lookup but grants no blocking authority, AGENTS.md 2-6)
- **When** the turn is processed
- **Then** the system returns the clarifying question rather than proceeding with a
  partial avoided set — an unsigned variant never silently passes, and never silently
  blocks either.

### Opt-in profile round-trip (P2)
- **Given** the user explicitly turns on `remember_allergies`
- **When** they save an allergy, use the app across turns, then delete it
- **Then** the profile persists only while opted in, is masked in AI calls, and is fully
  removed on delete (and on opting out).

## Success criteria

- **SC-001**: No turn with an allergy declared — in the current text, in any prior user
  turn, or via the structured field — proceeds to retrieval unless a non-empty
  structured list fully resolves and the current turn adds no new free-text
  declaration. Mutation: scan only the last user message → the bare-reply test turns
  red.
- **SC-002**: An allergen blocks a medicine only through an exact canonical key or a
  signed `synonyms.tsv` row on the structured path; an unsigned row yields at most
  `warning`. Mutation: unsign the row → block must disappear.
- **SC-003**: No model output influences the allergy state in any way. The extractor's
  schema has no allergen field; mutation: reintroduce one and wire it to the avoided
  set → a regression turns red.
- **SC-004**: With `remember_allergies` off, no allergy value is written or read; with
  it on, an AI call carries masked values only. Enforced by storage/masking tests
  (extends `AllergyBadge.test.tsx` / `storage.test.ts`).
- **SC-005**: `./gradlew test` and `pnpm test` pass; every new safety check has a
  named mutation that turns it red.

## Decisions

**2026-07-14 (supersedes the extraction design below):**
- **Free-text allergen extraction has no gate authority** → any free-text declaration
  clarifies; only the client-complete `exclude_ingredients` may authorize retrieval
  (FR-001/003/005). Grounds: four sequential review P0s, each a distinct loss path in
  the extraction pipeline; the class of bug is unfixable because extraction
  completeness is unverifiable server-side.
- **History scan** → `AllergyDeclaration` runs over every user message in the request
  (FR-013), closing the bare-reply hole.
- **Structured-reply affordance** → frontend collects allergens on the clarification
  answer and sends `exclude_ingredients` thereafter (FR-014, follow-up slice).

**2026-07-13 (original, superseded where struck):**
- ~~**Allergen extraction** → pass-1 `allergens` field, origin-bound, signed rows.~~
- ~~**FR-009 surface** → the pass-1 `allergens` field; added to the DEV-603 threat
  model.~~ (Entry closes as "surface removed".)
- **Normalization** → reviewed alias/spelling list only; no fuzzy/edit-distance
  matching (FR-006). Unchanged; now applies to the structured path.
- **Profile storage** → server DB (`user_profile` / `allergy_ingredient`), opt-in and
  off by default; gives the graded CRUD demo (FR-007). Unchanged.
- **Clarifying-question transport** → server-authored, reusing `clarifyingQuestions[]`
  shape; never model-authored (FR-010). Unchanged.

## Open questions

- None blocking scaffold. Remaining detail (exact `allergen`→ingredient alias-list
  format, and the `remember_allergies` consent UI copy) resolves during scaffold.

## Future expansion

If this grows past a single slice, add `plan.md` (HOW: extraction component, binding
service, masking, storage) and `tasks.md` (DO: DEV-56x WBS) alongside this file. The
#55 semantic output gate is a sibling spec that shares this spec's "server owns safety
facts" boundary; sequence #55 after this. Medical history beyond allergies (conditions,
pregnancy) would extend the same profile model.
