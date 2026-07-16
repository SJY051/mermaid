---
title: W3-C emergency and allergy expression review packet
status: decisions recorded 2026-07-16 (SJY051 product sign-off; no clinical reviewer available) — implementation authorized per Reviewer sign-off; new English copy remains PM/QA-reviewable draft
created: 2026-07-16 KST
owner: SJY051 (윤서진)
release_baseline: 4358efec58d7e18c6bdc1615886185f76d606c08 plus the uncommitted recovery RC
---

# W3-C clinical expression review packet

## Decision boundary

This packet does not diagnose, approve a clinical threshold, or authorize a regex. It records the
current behavior, the confirmed misses, the official public-health evidence, and the exact decisions
a human must make before code changes.

The implementation stays frozen until the reviewer writes a decision for all four expression groups.
No model may choose these thresholds.

## Current code mechanics

- `EmergencyTriage` screens all user-role text in the stateless request before retrieval or any model
  call. A match returns the fixed server-owned 119 response with `drugs=[]`.
- Its current red flags cover chest pain, breathing difficulty, stroke, severe bleeding,
  unconsciousness, and self-harm. They do not cover anaphylaxis or abdominal pain.
- `AllergyDeclaration` scans the whole user transcript for `allergy/allergic`,
  `anaphylaxis/anaphylactic`, or `intolerant/intolerance`.
- An allergy declaration suppresses AI-selected medicine. It does not diagnose an allergy and does
  not infer cross-reactivity.
- Both scanners currently see historical user turns as well as the newest turn. A new pattern that
  ignores tense or negation can therefore turn a past history into a current emergency, or let an old
  `no allergies` statement mask a later positive reaction.

## Confirmed gaps

| Expression | Current result | Safety/product gap |
|---|---|---|
| `anaphylactic reaction` plus `throat swelling` | routine allergy clarification; no 119 | a current airway emergency can miss pre-model triage |
| `severe stomach pain` / `severe abdominal pain` | ordinary retrieval path | a potentially urgent complaint can miss pre-model triage |
| `ibuprofen gives me hives` | not recognized as an allergy declaration | AI-selected medicines are not suppressed despite an explicit medicine-reaction statement |
| `no allergies` | recognized as an allergy declaration because `allergies` matches | an explicit negative statement unnecessarily opens the allergy gate |

## External evidence, not automatic code rules

- NHS describes anaphylaxis as life-threatening and lists sudden throat/tongue swelling,
  difficulty breathing, and difficulty swallowing among the emergency signs:
  <https://www.nhs.uk/conditions/anaphylaxis/>.
- NHS says a stomach ache that is sudden or severe requires immediate emergency assessment, while
  also listing additional red flags such as tenderness, blood, inability to breathe, chest pain, or
  collapse: <https://www.nhs.uk/symptoms/stomach-ache/>.
- MedlinePlus lists hives among common drug-allergy symptoms, but also notes that hives can have
  other causes. A causal phrase such as `ibuprofen gives me hives` can justify suppressing
  AI-selected medicine without the service claiming a diagnosis:
  <https://medlineplus.gov/ency/article/000819.htm> and
  <https://medlineplus.gov/hives.html>.

These sources describe care thresholds. They do not settle English parsing, temporal scope, or the
false-positive budget for this stateless application.

## Decisions required

### C1 — anaphylaxis and airway swelling

Choose one contract and record the accepted false-positive trade-off:

1. **High sensitivity:** explicit `anaphylaxis` / `anaphylactic reaction`, or sudden swelling of the
   lips, mouth, throat, or tongue, triggers the server 119 answer.
2. **Compound signal:** require an explicit anaphylaxis term or airway swelling together with a
   breathing/swallowing/tight-throat signal.
3. **Different reviewed contract:** write the exact included and excluded phrases.

Also decide whether a historical statement such as `I had an anaphylactic reaction five years ago`
should trigger the current emergency answer. The current whole-transcript scanner cannot infer time
reliably; a tense exclusion must be explicitly reviewed rather than improvised.

### C2 — severe abdominal or stomach pain

Choose one contract:

1. **High sensitivity:** `sudden` or `severe` abdominal/stomach pain triggers the server 119 answer.
2. **Compound signal:** require severe/sudden pain plus one reviewed modifier, such as collapse,
   blood, breathing difficulty, or marked tenderness.
3. **Urgent but not 119:** define a separate server-owned urgent-care response and its action.
4. **Different reviewed contract:** write the exact included and excluded phrases.

The product currently has only routine and fixed 119 paths; option 3 is a contract expansion, not a
regex-only change.

### C3 — medicine causes hives

Decide whether the following causal forms declare an allergy state and suppress all AI-selected
medicine without diagnosing the mechanism:

- `<medicine> gives/causes me hives`
- `I get hives from/after taking <medicine>`
- `I break out in hives when I take <medicine>`

Recommended product boundary: hives alone does not identify a medicine allergy; the reaction must be
causally tied to taking or naming a medicine. Throat/tongue swelling or breathing difficulty remains
the C1 emergency decision, not merely an allergy gate.

### C4 — explicit negative allergy statements

Approve the exact negative phrases, for example `no allergies`, `no known allergies`, `no known drug
allergies`, and `NKDA`.

Recommended precedence contract:

1. a positive allergy/reaction statement anywhere in the request wins over a negative statement;
2. `no allergies except <medicine>` is positive, not negative;
3. uncertainty such as `I am not sure if I have allergies` is not an explicit negative;
4. negation only removes its own matched phrase and must not mask a later positive turn.

This is primarily a product/NLP decision, but it must be reviewed together with C3 because both
change the same safety gate.

## RED-before scaffold after approval

Only after the written decisions above:

- add one positive and one near-miss test per approved emergency phrase group in
  `EmergencyTriageTest`;
- prove the approved emergency phrases return the canonical 119 action and `drugs=[]`, with retrieval
  and both model passes at zero calls;
- change the existing `ibuprofen gives me hives` gap test from the current expected miss to the
  approved positive contract, plus a non-causal hives near-miss;
- add the approved negative-allergy phrases and conflict/`except` precedence tests;
- mutation-check each group by removing its production matcher and observing only its owning tests
  turn red;
- run focused chat safety tests, the full backend suite, and browser JSON/SSE checks.

The existing gap assertions must not be silently rewritten in the implementation commit. Commit the
approved RED scaffold separately first so the behavioral change remains auditable.

## Reviewer sign-off

Record decisions in this section before implementation.

> **Basis of this sign-off.** These are product decisions by SJY051 (2026-07-16), informed by the
> NHS/MedlinePlus sources cited above. No clinical reviewer is available to this team; this record
> is a product sign-off, not a clinical review, and says so on purpose. Conservative high-sensitivity
> defaults were chosen because in this information service overtriage (an unnecessary fixed 119
> answer) is the bounded failure, while undertriage (routine drug information for an airway
> emergency) is the unbounded one.

- C1 decision and reviewer/date: **Option 1 — high sensitivity. SJY051, 2026-07-16.**
  Explicit `anaphylaxis` / `anaphylactic` terms, OR sudden swelling of the lips, mouth, throat, or
  tongue, each alone, trigger the server-owned 119 answer. Historical statements (e.g. `I had an
  anaphylactic reaction five years ago`) DO trigger it — an accepted false positive, recorded as a
  trade-off: the whole-transcript scanner cannot infer tense reliably, and an improvised tense
  exclusion is exactly what this packet forbids. No tense or negation exclusion is authorized for C1
  in this round.
- C2 decision and reviewer/date: **Option 1 — high sensitivity. SJY051, 2026-07-16.**
  `sudden` or `severe` abdominal/stomach pain triggers the server-owned 119 answer, matching the NHS
  threshold. Option 3 (a separate server-owned urgent-care tier) is recorded as a future contract
  expansion and is NOT part of this approval.
- C3 decision and reviewer/date: **Approved as recommended. SJY051, 2026-07-16.**
  The causal forms (`<medicine> gives/causes me hives`, `I get hives from/after taking <medicine>`,
  `I break out in hives when I take <medicine>`) declare an allergy state and suppress all
  AI-selected medicine (SA-08), without diagnosing a mechanism. Hives without a causal tie to a
  medicine is not a declaration. Throat/tongue swelling or breathing difficulty remains the C1
  emergency contract, not merely an allergy gate.
- C4 decision and SJY051/date: **Approved as recommended. SJY051, 2026-07-16.**
  Negative phrases: `no allergies`, `no known allergies`, `no known drug allergies`, `NKDA`, with
  the four precedence rules exactly as written in this packet: a positive statement anywhere wins;
  `no allergies except <medicine>` is positive; uncertainty is not an explicit negative; negation
  removes only its own matched phrase and never masks a later positive turn.
- Approved English emergency/allergy copy changes, if any: **None pre-approved.** Any new
  user-facing English copy the implementation introduces must be flagged as reviewable draft copy
  for PM/QA, not shipped as final (existing fixed-copy convention).
