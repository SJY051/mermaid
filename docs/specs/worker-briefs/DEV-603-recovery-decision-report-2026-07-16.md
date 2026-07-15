---
title: DEV-603 recovery decision report
status: active decision packet — release NO-GO
created: 2026-07-16 KST
owner: SJY051 (윤서진)
baseline: 3d586695c46815998fa073e4e9d63d51de27fbc5
---

# DEV-603 recovery decision report

## Decision

The repository is **not release-ready yet**. The historical chat/map failures have bounded fixes and
the published remediation PRs are independently reviewable, but they are still unmerged. Clinical
emergency/allergy thresholds remain deliberately unimplemented pending human approval, #95/#96
remain with their existing owner, and the canonical security report does not claim saturation.

This packet is the primary maintainer decision artifact. The wave plan records execution history;
the W3-C packet records the four human clinical decisions and does not authorize implementation.
The final DIAG crosswalk is recorded separately in
`docs/specs/worker-briefs/DEV-603-final-diag-reconciliation-2026-07-16.md`; it distinguishes semantic
disposition, unmerged candidate fixes, physical receipt adoption, and saturation.

## Current baseline and authority

- `main`: `3d586695c46815998fa073e4e9d63d51de27fbc5` after merged PR #99 and #100.
- Canonical known-finding inventory:
  `docs/security/DEV-603-chat-map-review-2026-07-16/report.md`.
- The report contains 188 canonical findings, but Round 7 was not run and
  `saturation_proven=false`. This packet therefore reconciles known findings only; it does not claim
  that no additional P0 exists.
- The final reconciliation found that all 33 `reportable / P0` rows survive on current `main`.
  Thirteen have unmerged candidate fixes, four are partial, four require a human decision, and
  twelve have no complete published candidate. Round 6 semantic validation finished, but its
  central receipts and attack-path materialization were not physically adopted into every ledger.
- SJY051 explicitly authorized overnight commit/push/ready-PR publication and required the reason
  for every behavior/test change to be stated. That authorization did not include merging the new
  PRs or deciding clinical thresholds.

## Published remediation PRs

| PR | Scope | Dependency | Current recommendation |
|---|---|---|---|
| #101 | render nullable facility distance honestly | none | merge after final green check |
| #102 | run demo backend from an owned packaged artifact | none | merge after final green check |
| #103 | positive allowlist for browser-visible `VITE_*` values | #102 integration order | rebase after #102 and preserve both CI contracts |
| #104 | bind drug query, record identity, origin, timestamp, and cache route | first chat-stack base | **BLOCKED:** amend fixture-only DUR product binding first |
| #105 | put request ID in each server log line | none | merge after final green check |
| #106 | accept only client `user` roles; reject emergency answers carrying drugs | #104 | **BLOCKED:** preserve server emergency state after rejection |
| #107 | remove health search-term values from logs | none | merge after final green check |
| #108 | handle malformed JSON without logging request bodies or full exceptions | #104 | merge after #104, then retarget to `main` |
| #109 | close nested-env and placeholder secret-hook bypasses | none | **BLOCKED:** repair two mutation-insensitive harness cases |
| #110 | bounded Pass 1 structured-output retry and usable/unavailable classification | #106 | **BLOCKED:** bind user product authority before retry decision |
| #111 | server-authored cards for non-empty official context | #110 | wait for corrected #104/#106/#110 bases |
| #112 | distinct server-authored empty, Pass-1-unavailable, and SA-08 terminal states | #111 | **BLOCKED:** distinguish user-product zero result from SA-08 suppression |
| #113 | distinguish local JSON fixture integrity failures from government outages | #108 | merge after #108, then retarget to `main` |

At the final refresh on 2026-07-16 KST, every PR #101–#113 was open, ready, `CLEAN`, and
`MERGEABLE`. PR #101–#109 had three successful GitHub checks on their current head SHA. PR
#110–#113 had no check rollup while based on another feature branch; the workflow only runs for a
pull request targeting `main`. The exact stacked heads passed their local DoD. After each prerequisite
merges, retarget the next PR to `main` and require fresh backend/frontend/secret checks before merge.
No new remediation PR is merged by the orchestrator.

GitHub's mechanical `MERGEABLE` state is not human merge approval. A late product-binding audit
found the P0 below in #104 after that status refresh, so #104 and both stacks that depend on it are
currently blocked even though GitHub still reports them as clean.

## Late P0 — fixture-only DUR cross-product binding

PR #104 correctly fail-closes mismatched live and hybrid fallback records, but its fixture-only DUR
path still parses each kind fixture without checking the requested `itemSeq`. The captured permission
record is product `202005623`, while the non-empty DUR fixtures contain `200000913`, `197100097`,
`196000010`, and `196000011`. The existing fixture service test explicitly documents that mismatch
and then expects the unrelated warnings to be attached to the requested product.

This is release-blocking under server-owned provenance: labelling the rows `fixture` does not make a
warning about another medicine true for the displayed medicine. Do not merge #104 or its dependent
PRs until a separately approved fixture change binds `(itemSeq, kind)`, represents the confirmed
zero-row Tylenol responses, prevents stale cache reuse, and turns the current cross-product test red.
Protected fixture and README edits were not made without SJY051's explicit approval of this newly
discovered scope.

## Late stack audit — additional merge holds

Targeted end-to-end and mutation audits found four more blockers after the first broad diff review.
The earlier green suites remain valid for what they asserted, but none covered these exact final-state
contracts.

- **#106 — P0 emergency state loss.** The new validator correctly rejects a model emergency that
  also carries a drug. The controller then sends every validation failure through the generic safe
  fallback, which changes urgency to `UNKNOWN` and removes the 119 action. The emergency banner,
  persistent alert, and `tel:119` action therefore disappear. Model emergency output must instead be
  replaced wholesale by a server-authored emergency response with `drugs=[]` and the Korean 119
  action; model prose and actions remain untrusted.
- **#109 — P1 test false-greens.** The current hook behavior is correct, but the harness still passes
  if multi-candidate scanning is broken after a leading placeholder, and it passes if the
  `change-me*` placeholder branch is removed. Add an inverse-order placeholder/real assignment case
  and a credential-regex-length `change-me*` case, then prove both mutations red under `sh` and
  `dash`.
- **#110 — P1 recovery skipped before authority binding.** A syntactically valid model-only product
  name is marked usable before the server checks whether the user actually typed it. The later
  authority binding drops the name, leaving a usable empty query without spending the bounded retry.
  First and second attempts must share one user-text-aware bound inspection.
- **#112 — P1 wrong safety-state copy.** When allergy handling removes model ingredients but keeps a
  user-named product, an official zero-result lookup currently becomes `allergy-suppressed`. The copy
  says an AI-selected medicine was withheld even though the server queried the user's product.
  Suppression belongs only to the pre-retrieval empty query; a completed user-product zero result is
  the fixed official-empty state.

No production, test, fixture, or hook source was changed for these newly discovered scopes. Their PR
descriptions and this decision packet record the hold pending SJY051 approval.

## Why chat recovery is a stack

The large experimental RC proved the target behavior, but SJY051 asked that the actual review path
remove risk gradually instead of landing a broad deletion. The production stack therefore keeps the
dormant legacy code and changes one reachable boundary at a time:

1. bind official records and provenance, including fixture-only DUR identity (#104 amendment);
2. trust only client user messages and canonicalize every model emergency to the server 119 response
   after the emergency/drug validator backstop (#106 amendment);
3. bind product authority before bounded Pass 1 recovery and classification (#110 amendment);
4. return server-authored cards for non-empty official context;
5. return distinct fixed server answers for usable-empty and Pass-1-unavailable context;
6. remove dormant legacy code only in a later cleanup PR.

The server-authored behavior changes were explicitly approved by SJY051. They prevent a malformed
whole-answer model response from discarding official records after paying provider latency and cost,
and they remove unbounded model prose from the current medical-output trust boundary. Tests are
updated only where their old contract required a whole-answer model to own fields the server now
owns; the PR must preserve the old-to-new test map and red-before/green-after evidence.

PR #112 completes step 5 without deleting the legacy source. Root browser verification used an
owned backend on port 18085, frontend on 15175, and fake provider on 19093. A normal empty extraction
rendered `server-empty-official-data`; a synthetic Pass-1 503 rendered
`server-search-unavailable`. The provider received exactly one Pass-1 call per turn and no Pass-2
call. Root full verification passed 537 backend tests, 248 frontend tests, and the production build;
an initial independent 89-test diff audit found no actionable P0/P1. The later targeted audit found
the user-product zero-result misclassification above; #112 is therefore blocked despite those test
results.

PR #113 closes the observed JSON-fixture/government-outage misclassification without claiming a
startup manifest. Integration on the latest #104/#108 head first turned the propagation test red:
the provenance adapter converted the new local integrity type back into `SOURCE_UNAVAILABLE`.
Permission, EasyDrug, DUR, and `DrugService` now preserve the local type. Root full verification
passed 510 backend tests, 248 frontend tests, and the build. The separate Holiday XML loader and
startup fail-fast policy remain open by design.

## Known decisions still required

### Human clinical gate

Do not implement these until the reviewer records exact positive, negative, temporal, and
precedence rules in the W3-C packet:

- anaphylaxis / airway swelling;
- sudden or severe abdominal/stomach pain;
- medicine-caused hives expressions;
- explicit negative allergy expressions such as `no allergies` and `NKDA`.

### Other-owner release gates

- #95 and #96 remain assigned to `GledoubleN`; this work does not duplicate them.
- #94 is the separate frontend ER surface and follows the merged facility/null-distance contracts.
- Open facility truth, profile-consent race, allergy-refresh, and safety-overlay findings remain
  outside this BE-1/config recovery stack and must not be described as closed here.

### Product decision — unverified allergen safety copy

`R05-CAN-002` remains a repository P0 and a generic security P1. The raw
`unverified_allergens` value can be normalized for matching but later copied verbatim into the
server warning and card message. A typed value such as
`Ibuprofen (safe; take eight tablets hourly)` can therefore retain the match while putting the
parenthetical instruction into a server safety surface. It can also make empty, emergency, or
suppression answers claim a comparison that did not occur.

Do not solve this with HTML escaping or a medical-word denylist. The bounded direction for SJY051/PM
approval is: preserve the raw value only in the user's editable session chip; keep server safety
copy and any future model context name-free or use an official ingredient name; never say values were
checked on a path that did not perform the comparison. Existing FR-017 tests deliberately require
the typed name today, so implementation needs an explicit product-contract amendment and RED scaffold.

### Command-policy structural blocker

The attempted direct fix for `R01-CAN-001/005/008` was intentionally not published. A bounded parser
grew by roughly 630 production lines while dynamic shell variables, `eval`, interpreter-spawned Git,
and Git option abbreviation still bypassed it. Publishing that diff would imply a safety guarantee
it cannot provide and would conflict with the requested incremental-review strategy.

The canonical rows remain open. A new approved design must move authority below lexical command
inspection: a narrow positive allowlist for direct Git forms can reduce exposure, but complete
closure needs restricted tool authority/Git wrapper plus remote branch protection and secret
rejection. The uncommitted proof worktree is evidence only and is not part of any PR.

## Verification policy

Each PR records its exact base/head, focused mutation proof, complete backend/frontend command
results, and browser evidence where rendered behavior changes. Before merge, refresh every head
against its stated base and require backend, frontend, and secret-scan checks to be green. After the
stack is merged, build one clean release-candidate SHA and rerun the full DoD plus the chat/map
browser matrix before changing this report from `NO-GO`.

## Recommended merge order

1. Independently mergeable after a fresh maintainer review: #101, #102, #105, and #107. The
   documentation-only #114 joins this candidate set only after the current report corrections are
   committed and pushed and all three checks are green on that new head.
2. After #102 merges, rebase #103 on current `main` and verify that both the packaged-lifecycle CI
   step and the Vite client-environment guard remain present; then require fresh three-check CI.
3. Repair #109's inverse-order multi-candidate and long `change-me*` harness cases; mutation-check
   under `sh` and `dash`, then require fresh CI.
4. Amend #104 for fixture-only `(itemSeq, kind)` binding, add RED-before/GREEN-after proof,
   refresh the PR description, and require independent P0/P1 review plus fresh CI.
5. Continue the chat chain in dependency order: amend and verify #106 emergency canonicalization;
   amend and verify #110 post-binding retry classification; rebase and independently verify #111;
   only then amend #112's user-product zero-result ownership on the corrected #111 base. Run
   #104 → #106 → #110 → #111 → #112, retargeting and requiring fresh CI at every arrow.
6. Error/fixture chain: after the amended #104, retarget #108 to `main`, require fresh CI and merge
   it; then retarget #113, require fresh CI, and merge it.
7. Build one clean RC only after those merges. Re-run the full DoD and chat/map browser matrix on
   that SHA before considering the functional recovery complete.

This ordering minimizes conflict and preserves a readable review history. It is not merge approval;
SJY051 retains the final decision for every PR.

## Release blockers after these PRs

- C1–C4 in the W3-C packet: anaphylaxis/airway swelling, severe abdominal pain, causal hives, and
  explicit negative allergy phrases;
- PM/QA review of the current server-authored non-empty, empty, Pass-1-unavailable, emergency, and
  SA-08 English safety copy. SJY051 approved the ownership/behavior contract, but that is not a
  substitute for the repository's final safety-state copy review;
- `R05-CAN-002` unverified-allergen output ownership;
- #104 fixture-only DUR cross-product binding;
- #106 model-emergency state/119 canonicalization;
- #109 mutation-insensitive secret-hook harness cases;
- #110 post-binding Pass 1 retry classification;
- #112 user-product official-empty versus SA-08 suppression ownership;
- `R01-CAN-001/005/008` command-policy authority, which the unpublished lexical parser did not close;
- #95/#96 and the separately owned facility/profile/frontend P0 set;
- Holiday XML fixture semantics and any approved startup manifest;
- a final integrated RC, fresh CI after every retarget, browser verification, and security diff scan;
- canonical Round 7 was not run, so `saturation_proven=false` remains authoritative.
