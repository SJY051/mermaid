---
title: DEV-603 final DIAG reconciliation
status: decision evidence — release NO-GO
created: 2026-07-16 KST
owner: SJY051 (윤서진)
main: 3d586695c46815998fa073e4e9d63d51de27fbc5
---

# DEV-603 final DIAG reconciliation

## Decision

The final DIAG report is usable as the authority for the 188 known canonical candidates, but it is
not an exhaustive, receipt-sealed security closure. Semantic validation finished for all 188
candidates and semantic attack-path review finished for all 123 eligible candidates. Physical
adoption into every canonical ledger, final attack-path materialization, and Round 7 saturation did
not finish before the deadline.

All 33 `reportable / P0` findings still survive on current `main`. Thirteen have an unmerged
candidate fix, four are only partially addressed, four require an explicit human clinical/product
decision, and twelve have no complete published candidate. An unmerged candidate is not a closed
finding. The current remediation stack also has later P0/P1 merge blockers, so there is no clean
release-candidate SHA to approve.

**Release decision: NO-GO.** This document reconciles known findings only. It does not claim that no
additional P0 exists.

## Authority and integrity

| Artifact | Authority / result |
|---|---|
| DIAG source revision | commit `654f906e00e81648d1482210b6a9171747dddd75`, tree `a14388f597c0c2a17e0dbcfc2d951a390c877214` |
| Same-tree main-reachable revision | `f4a2b6de89f5e4fa4ef5a81e5dafd54f8255367b`, an ancestor of current `main` |
| Final scan report | `report.md`, SHA-256 `38d0dd1e15ce9e573ec1d15f2baba0a5aca6fcc21b597414326edfc43e48b4ab` |
| Canonical inventory | 188 rows, SHA-256 `274fd61156b7ae3ebeadb1c905258fbe001a5f81380891b9d2491b44652477b8` |
| Semantic authority | SHA-256 `93ef34ade615c54c6069f6c53115576b0090c8574b786f5ebccb63b323605b5a` |
| Durable repository archive | `docs/security/DEV-603-chat-map-review-2026-07-16/`; its report uses rewritten relative links and therefore has SHA-256 `2bf0d1f787962854c8b3210773c13ede84f52e7d77fde22a048b63375024e572` |
| Current code baseline | `origin/main@3d586695c46815998fa073e4e9d63d51de27fbc5` |

The repository archive checksum manifest verifies every archived file. The archive report is the
durable reading copy; the original scan report remains the byte authority. The link rewrite does
not create a second semantic authority.

## What the final DIAG did and did not finish

| Stage | Evidence | Status |
|---|---|---|
| Canonical discovery | 188 candidates and 188 discovery receipts | complete |
| Central semantic validation | five `validation.jsonl` batches, `38 + 38 + 37 + 38 + 37 = 188` rows | complete |
| Final dispositions | 101 reportable, 22 needs review, 65 not reportable | complete |
| Reportable severity split | P0 33, P1 44, P2 21, P3 3 | complete |
| Central validation ledger adoption | only 14 older Round 4 validation receipts exist; the Round 6 188-row suffix was not adopted | **not complete** |
| Semantic attack-path review | five drafts, 123 eligible candidates, independent re-audit | complete |
| Attack-path materialization | final `attack_path.jsonl`, manifest, and 123 canonical receipts were not written | **not complete** |
| Provenance correction | `R05-CAN-004` must use `DrugService.java:214-219`, not `236-253` | hold |
| Saturation | terminal state `capped_by_user_deadline_after_round_06`; Round 7 not run; `saturation_proven=false` | **not complete** |

Accordingly, “all 188 candidates received a semantic disposition” is supported. “All 188 ledgers
were receipt-sealed” and “the scan proved saturation” are not supported.

## Reconciliation method

The 33 reportable P0 rows were divided into chat, provenance/facility, and config/consent groups and
checked independently against the exact current `main` and exact open PR heads. The root then
reconciled the three crosswalks with the final DIAG validation rows and the late end-to-end/mutation
audits. Status terms below mean:

- `candidate_fix_unmerged`: the named PR changes the relevant reachable boundary, but the finding
  remains open on `main` and the PR still needs its dependency and merge gates.
- `partial`: one leg is changed or one unsafe output is blocked, but the finding is not fully closed.
- `human_gate`: implementation would invent a clinical or product contract that SJY051 or a named
  reviewer has not approved.
- `open`: no complete published candidate exists.

## Current-main P0 crosswalk — chat and safety

| Canonical ID | Current `main` evidence | Candidate or decision gate | Current status |
|---|---|---|---|
| `R01-CAN-018` | model summary, guidance, and urgency remain reachable; prose gate is markup-only | #111 closes non-empty context; #112 closes remaining reachable terminal paths | `candidate_fix_unmerged` |
| `R01-CAN-019` | model indication/caution translations remain on official-looking cards | #111 sets those fields null and uses official source text | `candidate_fix_unmerged` |
| `R01-CAN-020` | emergency validation requires 119 but not empty drugs | #106 rejects emergency+drug, then incorrectly loses emergency state in generic fallback | `partial` — #106 P0 blocked |
| `R01-CAN-021` | public-data narrative is still promoted to an extra `system` message | #111/#112 remove the reachable whole-answer sink; dormant source remains for later cleanup | `candidate_fix_unmerged` |
| `R01-CAN-027` | client `assistant` history still reaches the provider as assistant text | #106 forwards only exact client `user` roles | `candidate_fix_unmerged` |
| `R01-CAN-028` | causal reaction text such as “gives me hives” does not trigger SA-08 | W3-C clinical/product truth table | `human_gate` |
| `R01-CAN-029` | anaphylaxis/airway swelling and severe abdominal-pain families are absent from triage | W3-C C1/C2 clinical decisions | `human_gate` |
| `R01-CAN-030` | punctuation and whitespace variants can miss literal triage regexes | separate normalization scope and clinical sign-off; C1–C4 alone are insufficient | `human_gate` |
| `R01-CAN-034` | model `answerId` still controls the allergy picker transition | #112 makes every reachable final answer server-authored; magic-ID residue remains dormant | `candidate_fix_unmerged` |
| `R01-CAN-046` | unnormalizable exclusions are silently dropped before `no_match_found` | no published candidate | `open` |
| `R02-CAN-003` | JSON `urgency.level=null` can avoid emergency validation and presentation | #112 removes the remaining reachable model urgency path | `candidate_fix_unmerged` |
| `R05-CAN-002` | raw `unverified_allergens` text is interpolated into server safety copy/context | SJY051 product decisions D1/D2 | `human_gate` |
| `R06-CAN-003` | malformed chat JSON falls into the full-Throwable logging branch | #108 adds a value-free 400 branch | `candidate_fix_unmerged` |

## Current-main P0 crosswalk — provenance and facilities

| Canonical ID | Current `main` evidence | Candidate or owner gate | Current status |
|---|---|---|---|
| `R01-CAN-094` | malformed hospital hours become `CLOSED` and may be filtered | BE-2/facility owner; #95/#96 are adjacent but do not close this row | `open` |
| `R01-CAN-095` | malformed pharmacy hours become `CLOSED` and may be filtered | BE-2/facility owner; no exact published candidate | `open` |
| `R01-CAN-098` | merged #99 preserves list retrieval time, but weekly/detail cache hits are still restamped | weekly-hours leg remains open; #95 owns the adjacent detail-cache leg | `partial` |
| `R01-CAN-105` | hybrid fixture rows still receive live/current provenance | #104 carries per-result origin and retrieval time | `candidate_fix_unmerged` |
| `R01-CAN-109` | permission fallback can join the first row under the requested product ID | #104 exact-binds permission detail | `candidate_fix_unmerged` |
| `R01-CAN-110` | EasyDrug fallback can join another product’s narrative | #104 exact-binds EasyDrug detail | `candidate_fix_unmerged` |
| `R01-CAN-113` | hybrid DUR fallback does not bind every row to requested `itemSeq` | #104 fixes the exact hybrid finding | `candidate_fix_unmerged` — adjacent fixture-only P0 blocks #104 |
| `R04-CAN-011` | inline chat forwards `openNow=true`, so unknown-hours facilities disappear | no published candidate; #94 does not cover this general contract | `open` |
| `R05-CAN-023` | cached drug facts are restamped with the current request time | #104 carries and composes immutable component retrieval times | `candidate_fix_unmerged` |
| `R02-CAN-002` | full-screen detail drawer covers the persistent disclaimer/emergency controls | no published candidate; #101 changes only nullable distance | `open` |

The exact canonical `R01-CAN-113` is the hybrid-fallback defect and #104 contains a candidate fix.
The late #104 blocker is a distinct fixture-only sibling: fixture mode ignores requested `itemSeq`
and attaches rows for `200000913`, `197100097`, `196000010`, and `196000011` to requested product
`202005623`. This distinction prevents both false claims: the canonical row has a candidate fix,
but #104 is still unsafe to merge.

## Current-main P0 crosswalk — config, privacy, and consent

| Canonical ID | Current `main` evidence | Candidate or owner gate | Current status |
|---|---|---|---|
| `R01-CAN-001` | lexical command guard misses wrapped/path-qualified/interpreter operations | unpublished parser remained bypassable and was not published | `open` |
| `R01-CAN-005` | Git global options and shell semantics bypass staging/push authority checks | requires a new structural authority design | `open` |
| `R01-CAN-008` | `--no-verify` denial depends on fragile literal Git parsing | #109 is a pre-commit-content guard, not command authority | `open` |
| `R01-CAN-038` | refreshed allergy catalog silently intersects away a stored declaration | FE-1 safety UX decision and implementation | `open` |
| `R01-CAN-069` | exact health search terms remain in INFO logs | #107 changes logs to count-only; #105 adds request correlation | `candidate_fix_unmerged` |
| `R01-CAN-125` | consent withdrawal and allergy add are concurrent non-serialized transactions | profile locking/database invariant decision | `open` |
| `R01-CAN-126` | stale country update can restore withdrawn consent | profile versioning or intent-specific update decision | `open` |
| `R01-CAN-127` | V2 sets consent false but neither removes nor read-gates legacy allergy rows | migration/data decision; production legacy-row presence remains operational evidence to gather | `open` |
| `R04-CAN-001` | current hook misses nested `.env` paths | #109 production hook fixes it, but the test harness still false-greens under a required mutation | `partial` — #109 P1 blocked |
| `R04-CAN-002` | whole-line placeholder suppression can hide a real credential | #109 production hook fixes it, but two inverse/length mutations are not caught | `partial` — #109 P1 blocked |

## Exact published candidates and late blockers

| PR head | Canonical contribution | Current hold |
|---|---|---|
| #104 `0e295821c1295b73a4a6bde1047fce937ba6b334` | `R01-CAN-105/109/110/113`, `R05-CAN-023` | P0 fixture-only DUR cross-product binding |
| #106 `00a524949617f21b597fc3c22c2a3e3b44a6708f` | `R01-CAN-027`; partial `R01-CAN-020` | P0 generic fallback removes canonical emergency/119 state |
| #107 `96270e4a7589d04f4501d510cd875998c761f210` | `R01-CAN-069` | independent candidate; fresh maintainer review/CI before merge |
| #108 `f16cdd40ff3db6b5914b787ff415199fe9ea771f` | `R06-CAN-003` | stacked on #104; extract/retarget only after dependency decision |
| #109 `57306f077351e813da9f5e1245f1eb961ae8ec62` | production candidates for `R04-CAN-001/002` | P1 mutation-insensitive inverse-order and long-placeholder tests |
| #110 `b7b48e464ea90573b74c286f0c8aa550fd187160` | enables the server-answer chain | P1 usability is decided before user-product authority binding |
| #111 `408bfc083700cdc0cc140578f2c45119328391e6` | `R01-CAN-019`; non-empty legs of `018/021/034` | depends on corrected #104/#106/#110; currently amplifies #104’s wrong fixture warning |
| #112 `34d9a0181a9435871c8f03316ac7c30e008d4563` | remaining reachable legs of `018/021/034` and `R02-CAN-003` | P1 user-product official-zero is misclassified as SA-08 suppression |

PRs #101, #102, #105, and #107 remain independently reviewable candidates. #103 follows #102 and
must preserve both CI contracts. These mechanically independent PRs do not make the full release
safe, and GitHub `CLEAN/MERGEABLE` is not a semantic approval.

## No current release-candidate SHA

The old integrated RC is historical evidence only. It is behind current `main`, contains tracked and
untracked changes, and was never published as the review path. The actual changes were split into
small PRs at SJY051’s request, then later audits found the #104/#106/#109/#110/#112 blockers above.
Consequently, no clean committed tree currently combines the corrected PR heads, and no final RC
SHA can be named or release-tested.

## Required decisions and resume order

1. SJY051 decides whether to authorize protected fixture and fixture-README changes for #104.
2. Amend #104 with exact `(itemSeq, kind)` fixture binding, product-specific captured zero responses,
   fail-closed unknown keys, all-row binding, and a cache namespace bump; prove each mutation red.
3. Amend #106 to replace any model emergency result with a server-authored emergency answer,
   `drugs=[]`, and the Korean 119 action across JSON and SSE.
4. Amend #110 so both attempts share a user-text-aware bound inspection; then restack and verify
   #111 and #112, including the user-product official-zero terminal state.
5. Repair #109’s two false-green harness cases before treating its production hook fixes as closed.
6. Record W3-C clinical decisions and D1/D2 product decisions before implementing
   `R01-CAN-028/029/030` or `R05-CAN-002`.
7. Coordinate the facility, profile/consent, FE safety-overlay, allergy-refresh, and inline-chat
   unknown-hours findings with their exact owners; do not absorb #95/#96 work.
8. Merge only after each exact head is independently reviewed and receives fresh target-`main` CI.
9. Build one clean committed RC, run the full backend/frontend DoD and real-browser chat/map safety
   matrix, then perform a security diff review on that exact SHA.
10. Separately resume DIAG sealing in a clean scan workspace: correct `R05-CAN-004`, adopt central
    validation receipts, materialize/adopt attack paths, then run Round 7 blind-first discovery.

Only after steps 1–9 may the functional release decision be reconsidered. Step 10 is required before
claiming canonical receipt closure or exhaustive saturation; it must not be retroactively inferred
from passing application tests.

## Maintainer decision summary

- **Can the final DIAG report be used now?** Yes, for the 188 known candidates and their semantic
  dispositions, with the receipt and saturation limitations stated above.
- **Are the 33 reportable P0 findings fixed on current `main`?** No. All 33 survive.
- **Do published candidate fixes exist?** Yes, for 13; four more are only partial.
- **Can the current chat stack merge as published?** No. #104 and #106 have P0 blockers; #110 and
  #112 have P1 blockers; #111 depends on them.
- **Can the current release be approved?** No. There is no clean corrected RC, human decisions and
  other-owner P0s remain, and the canonical scan did not prove saturation.
