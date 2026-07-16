---
title: DEV-603 post-remediation DIAG delta
status: supporting audit evidence — no saturation claim
created: 2026-07-16 KST
owner: SJY051 (윤서진)
diag-source: 654f906e00e81648d1482210b6a9171747dddd75
validated-code-baseline: 040397f0717398a5a7a50294dc39bd013628e6bc
---

# DEV-603 post-remediation DIAG delta

## Purpose and conclusion

This document records the conservative difference between the frozen DIAG inventory and the
post-remediation `main` code baseline. It is supporting audit evidence for
`DEV-603-post-remediation-decision-2026-07-16.md`; it is not a new exhaustive repository scan.

The frozen DIAG report remains useful as the authority for its 188 known canonical candidates.
It does not prove present-day saturation, and this delta does not convert a static crosswalk into a
dynamic closure certificate.

**Release conclusion: NO-GO.** The recovery closes or makes unreachable important paths, but human
decisions, other-owner P0s, and seventeen survivors in the historical 33-P0 static crosswalk remain.
The current disposition of all 188 rows is unknown until a new exact-main reconciliation and the
unfinished central receipt work are completed.

## Frozen DIAG authority and limits

| Item | Frozen result |
|---|---|
| Source revision | `654f906e00e81648d1482210b6a9171747dddd75` |
| Canonical candidates | 188 |
| Final semantic dispositions | 101 reportable, 22 needs review, 65 not reportable |
| Reportable severities | P0 33, P1 44, P2 21, P3 3 |
| Attack-path eligible | 123 |
| Round 7 | not run |
| Saturation | `saturation_proven=false` |
| Central receipt adoption | incomplete after Round 6 |
| Known correction hold | `R05-CAN-004` provenance correction not materialized |

The statements “188 candidates received a semantic disposition” and “123 eligible candidates
received semantic attack-path review” are supported by the frozen artifacts. The statements “all
188 rows are currently closed,” “all ledgers are receipt-sealed,” and “no additional P0 exists” are
not supported.

## Static P0 delta at `main@040397f`

This section is deliberately limited to the historical set of 33 `reportable / P0` rows. It does
not describe all 188 rows, and it does not assign a new canonical disposition. Each classification
was checked against the current reachable architecture and merged remediation diff.

### Directly closed on the reachable current path — 11

- `R01-CAN-020` — model emergency output is canonicalized to the fixed drug-free 119 state.
- `R01-CAN-027` — only exact client `user` roles reach the provider.
- `R01-CAN-069` — health search-term values are absent from application logs.
- `R01-CAN-105` — hybrid fallback origin is carried rather than restamped live.
- `R01-CAN-109` — permission detail is bound to the requested record.
- `R01-CAN-110` — EasyDrug narrative is bound to the requested record.
- `R01-CAN-113` — hybrid DUR rows are bound to the requested record.
- `R04-CAN-001` — nested and quoted staged `.env` paths are rejected.
- `R04-CAN-002` — placeholder/multi-assignment credential masking is rejected.
- `R05-CAN-023` — drug component retrieval times are preserved and composed.
- `R06-CAN-003` — malformed request JSON is handled without request-body/full-throwable logging.

“Directly closed” here means the named vulnerable reachable behavior is changed on the validated
code baseline. It does not imply that every adjacent parser, fixture, log sink, or cache route was
exhaustively re-scanned.

### Reachable-path mitigated; dormant source remains — 5

- `R01-CAN-018` — unbounded model medical prose and urgency no longer own reachable final answers.
- `R01-CAN-019` — model indication/caution translations no longer become official-looking cards.
- `R01-CAN-021` — public API text no longer reaches the active whole-answer privileged prompt sink.
- `R01-CAN-034` — model `answerId` no longer controls the reachable final allergy UI state.
- `R02-CAN-003` — nullable model urgency no longer controls the reachable final response.

These rows are not labelled “source removed.” The dormant whole-answer Pass 2 implementation still
exists, and #118 owns its incremental removal. Re-enabling that path without equivalent server
boundaries would reopen the findings.

### Survives the static crosswalk — 17

#### Command authority — 3

- `R01-CAN-001`
- `R01-CAN-005`
- `R01-CAN-008`

The attempted lexical parser was not published because dynamic shell variables, `eval`,
interpreter-spawned Git, and Git option forms remained bypassable. Closure requires a structural
authority boundary, not another broad parser.

#### Chat, allergy, and triage — 5

- `R01-CAN-028` — medicine-caused hives expressions still need a human-approved contract.
- `R01-CAN-029` — anaphylaxis/airway swelling and severe abdominal-pain families still need human
  clinical decisions.
- `R01-CAN-030` — normalization and precedence across punctuation/whitespace variants remain open.
- `R01-CAN-046` — unnormalizable exclusions can still be lost before the final allergy state.
- `R05-CAN-002` — raw unverified-allergen text can still shape server safety copy.

PR #120 closes common single-`l` `allergy`/`allergic` misspellings. It does not close causal hives,
negative allergy expressions, or the broader normalization findings above.

#### Frontend and facility truth — 6

- `R01-CAN-038` — catalog refresh can silently remove a stored allergy declaration.
- `R01-CAN-094` — malformed hospital hours can become `CLOSED`.
- `R01-CAN-095` — malformed pharmacy hours can become `CLOSED`.
- `R01-CAN-098` — weekly/detail cache retrieval-time truth remains incomplete.
- `R02-CAN-002` — the facility detail overlay can hide persistent safety controls.
- `R04-CAN-011` — inline chat `openNow=true` can exclude unknown-hours facilities.

#95 and #96 are adjacent other-owner P0s and remain release gates. #96 now has open ready PR #125;
this delta does not review it. Neither issue may be described as closing all six canonical rows
above unless its final diff and exact acceptance criteria do so.

#### Profile consent — 3

- `R01-CAN-125` — consent withdrawal and allergy addition are not serialized.
- `R01-CAN-126` — a stale update can restore withdrawn consent state.
- `R01-CAN-127` — legacy allergy rows are not fully removed/read-gated after withdrawal.

### Crosswalk total

| Static classification | Count |
|---|---:|
| Directly closed on reachable current path | 11 |
| Reachable-path mitigated; dormant source remains | 5 |
| Survives | 17 |
| Historical P0 rows considered | 33 |

This `11 + 5 + 17` accounting is only a static delta for the historical 33 P0 rows. It must never
be quoted as “171 of 188 closed,” “16 P0s fixed in the canonical ledger,” or a present-day scan
severity count.

## Post-merge security diff review

A separate security diff review audited the exact historical range
`3d586695c46815998fa073e4e9d63d51de27fbc5..7006d82cd9919d5e06a5c1bc0cf1283625e28e0b`.
All 38 deterministic diff/support rows received review receipts. The sealed decision for that exact
range is **HOLD**, because it found two reportable repository-safety findings:

| Finding | Historical vulnerable behavior | Repository classification | Current closure mapping |
|---|---|---|---|
| `SEC-DIFF-001` | Git C-quoted non-ASCII staged paths could bypass the `.env` filename guard. | P0 under repository secret policy; not a demonstrated remote exploit | #124, independently re-audited and merged |
| `SEC-DIFF-002` | A positive DUR `totalCount` with absent/inconsistent rows could still contribute to a server-authored live card. | P1 product-safety integrity; not an attacker-controlled upstream exploit | #123, independently re-audited and merged |

The original audited range therefore was **not clean**. The later #123/#124 merges are closure
mapping against the current baseline; they do not retroactively change the scan result. The diff
review was scoped to that range and did not prove repository-wide saturation.

## Current release gates represented by this delta

### Human decision gates

- W3-C: anaphylaxis/airway swelling, severe abdominal/stomach pain, causal hives, explicit negative
  allergy expressions, including positive/negative examples and precedence.
- `R05-CAN-002`: server safety-copy ownership for raw unverified allergen names.
- `R01-CAN-001/005/008`: structural command authority rather than lexical shell inspection.

### Existing-owner and product gates

- #95 and #96 remain assigned to `GledoubleN` and were not touched by this wave; #96 has open ready
  PR #125 pending independent review and its owner's merge decision.
- #94 is required before the frontend can claim a complete nearby-ER flow.
- #55/#61 need an explicit rescope or closure decision because the whole-answer source is dormant
  but still present.
- #119 remains the bounded reactive provider-log request-correlation follow-up.
- #118 owns incremental dormant Pass 2 removal only after the current behavior remains locked.

### Release evidence gates

- PM/QA safety-state copy and accessibility review;
- #23, #24, and #25 release artifacts;
- final registered-origin Naver browser pass and approved live API access check;
- full DoD and a new exact-RC security delta review.

## What a future canonical reconciliation must do

1. Start from a new exact release-candidate SHA, not the frozen DIAG source revision.
2. Re-run current code-path discovery before applying this static crosswalk.
3. Correct and adopt the outstanding `R05-CAN-004` provenance receipt.
4. Materialize and adopt the remaining central validation and attack-path receipts.
5. Run the missing blind-first saturation round instead of inferring saturation from passing tests.
6. Re-test every claimed closure dynamically where a safe proof exists.
7. Add post-DIAG findings such as `SEC-DIFF-001/002` as separate history, preserving their original
   audited range and later closure mapping.

Until then, the correct statement is: the tested recovery paths are functional and materially
hardened; the known release gates and DIAG limitations keep the repository release at NO-GO.

## Artifact authority

| Artifact | What it is authoritative for |
|---|---|
| `docs/security/DEV-603-chat-map-review-2026-07-16/report.md` | Findings known at the frozen DIAG source revision |
| This document | Conservative static delta from the historical 33 P0 rows to `main@040397f` |
| `DEV-603-post-remediation-decision-2026-07-16.md` | Current maintainer decision, verification evidence, and resume order |
| `W3-C-clinical-expression-review-packet-2026-07-16.md` | Human clinical/product decisions that are still intentionally unresolved |
| PRs #123 and #124 plus their independent audits | Closure evidence for the two late diff findings |
