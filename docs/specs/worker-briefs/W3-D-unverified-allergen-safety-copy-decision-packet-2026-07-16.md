---
title: W3-D unverified allergen safety-copy decision packet
status: D1/D2 recorded 2026-07-16 (SJY051) — implementation authorized per Decision record; exact English strings pending PM/QA
created: 2026-07-16 KST
owner: SJY051 (윤서진)
release_baseline: 3d586695c46815998fa073e4e9d63d51de27fbc5 plus the uncommitted recovery RC
---

# W3-D unverified allergen safety-copy decision packet

## Decision boundary

This is a product and trust-boundary decision, not a clinical phrase decision. It does not change
which name matches an official ingredient, infer an allergy, or turn an unverified name match into a
verified block.

Implementation stays frozen until SJY051 records D1 and D2 below. The existing FR-017 contract and
tests explicitly require typed allergen names in server safety copy, so silently changing the code
would rewrite an approved product contract.

## Confirmed current behavior

1. `mermaid.unverified_allergens` accepts up to ten user-authored strings of up to 100 characters.
2. Matching canonicalizes the name. Parenthetical text can be removed for comparison, so
   `Ibuprofen (safe; take eight tablets hourly)` can still name-match official Ibuprofen.
3. The original string, including its parenthetical suffix, is then repeated in two server-owned
   safety surfaces:
   - the answer-level warning appended by `ChatProxyController`;
   - a drug card's `AllergyCheck.message` written by `DrugContextRetriever`.
4. React escapes the text, so this is not DOM XSS. The defect is semantic authority: user prose is
   displayed inside a warning that looks server-authored and can contain reassurance or dose-like
   instructions forbidden by the repository's safety rules.
5. Emergency, empty, and allergy-suppressed answers also receive copy saying the typed names “were
   checked”, even though those paths may perform no ingredient comparison.

General security severity is P1 because the caller sees their own input and no cross-user or script
execution path was found. Repository severity is release-blocking P0 because AGENTS.md §2-2 forbids
“safe” reassurance in an allergy state and treats every §2 bypass as P0.

## Decisions required

### D1 — raw typed names on server safety surfaces

Choose one:

1. **Prohibit raw names (recommended).** Keep the original text only in an explicit user-owned
   editing chip. Do not repeat it in answer warnings, drug-card allergy messages, or model context.
2. **Retain raw names.** Write the exact safety reason and the server-side semantic sanitization
   contract that makes arbitrary English prose safe to present as authoritative warning text.
3. **Different reviewed contract.** State exactly which surfaces may repeat the text and why.

HTML escaping, quotation marks, and a denylist of words such as `safe` are not complete options: they
do not prevent arbitrary medical reassurance or instructions from retaining server authority.

### D2 — claim that comparison occurred

Choose one:

1. **Conditional copy (recommended).** No-card paths say that typed names were not independently
   verified. A card that actually name-matched says that an official ingredient matched “one of the
   names you typed” and requires pharmacist confirmation.
2. **Keep the current blanket past tense.** Approve “were checked” on emergency, empty, and
   suppression paths even when no ingredient comparison ran, with the product rationale.
3. **Different reviewed copy.** Supply the exact branch-specific English text.

The recommended D1+D2 result preserves the useful distinction without repeating raw prose:

- no card: the typed names were not independently verified and no compatibility conclusion is made;
- matched card: official ingredient name(s) are shown, the match is name-only, and a pharmacist must
  confirm it;
- verified `blocked` always outranks an unverified name match;
- unverified matching never becomes `blocked` and never becomes “safe”.

## RED-before scaffold after approval

Only after D1 and D2 are recorded:

- use a typed sentinel such as `Ibuprofen (safe; take eight tablets hourly)` and prove the official
  Ibuprofen name-match still occurs;
- prove the raw sentinel, `safe`, and dose-like suffix never appear in serialized answers,
  answer-level warnings, card allergy messages, or dormant model context;
- prove emergency, empty, and suppression paths do not claim a comparison occurred;
- preserve the current contracts that unverified text never becomes an upstream drug query, never
  creates `blocked`, cannot weaken a verified block, and makes an ingredientless product `unknown`;
- mutation-check both the controller caveat and the per-card message independently;
- run focused chat safety tests, the full backend suite, and browser checks of the safety surfaces.

The approved RED scaffold must be committed separately before implementation so the contract change
remains auditable.

## Decision record

- D1 decision and SJY051/date: **Option 1 — prohibit raw names. SJY051, 2026-07-16.**
  The original typed text stays only in the explicit user-owned editing chip. It is never repeated
  in answer-level warnings, drug-card allergy messages, or model context. Server safety surfaces
  reference a match via the official ingredient name plus generic phrasing ("one of the names you
  typed"), so the warning slot never goes silent. HTML escaping, quotation marks, and word denylists
  are rejected as incomplete, per this packet: they cannot stop arbitrary reassurance or dose-like
  prose from borrowing server authority.
- D2 decision and SJY051/date: **Option 1 — conditional copy. SJY051, 2026-07-16.**
  No-card paths state the typed names were not independently verified and that no compatibility
  conclusion is made. A matched card states that an official ingredient name-matched one of the
  typed names, that the match is name-only, and that a pharmacist must confirm it. Verified
  `blocked` always outranks an unverified name match; unverified matching never becomes `blocked`
  and never becomes "safe". The blanket past-tense "were checked" copy is withdrawn from emergency,
  empty, and suppression paths that perform no ingredient comparison.
- Approved English copy or PM/QA reviewer/date: **Branch semantics approved above; exact English
  strings PENDING PM/QA.** The implementation drafts the strings against the approved branch
  contract and flags them as reviewable draft copy for PM/QA rather than final.
