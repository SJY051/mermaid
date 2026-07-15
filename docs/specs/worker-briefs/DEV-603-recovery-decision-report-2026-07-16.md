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

## Current baseline and authority

- `main`: `3d586695c46815998fa073e4e9d63d51de27fbc5` after merged PR #99 and #100.
- Canonical known-finding inventory:
  `docs/security/DEV-603-chat-map-review-2026-07-16/report.md`.
- The report contains 188 canonical findings, but Round 7 was not run and
  `saturation_proven=false`. This packet therefore reconciles known findings only; it does not claim
  that no additional P0 exists.
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
| #106 | accept only client `user` roles; reject emergency answers carrying drugs | #104 | merge after #104, then retarget to `main` |
| #107 | remove health search-term values from logs | none | merge after final green check |
| #108 | handle malformed JSON without logging request bodies or full exceptions | #104 | merge after #104, then retarget to `main` |
| #109 | close nested-env and placeholder secret-hook bypasses | none | merge after final green check |
| #110 | bounded Pass 1 structured-output retry and usable/unavailable classification | #106 | merge after #106, then retarget to `main` |
| #111 | server-authored cards for non-empty official context | #110 | merge after #110, then retarget to `main` |
| #112 | distinct server-authored empty, Pass-1-unavailable, and SA-08 terminal states | #111 | merge after #111, then retarget to `main` |
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

## Why chat recovery is a stack

The large experimental RC proved the target behavior, but SJY051 asked that the actual review path
remove risk gradually instead of landing a broad deletion. The production stack therefore keeps the
dormant legacy code and changes one reachable boundary at a time:

1. bind official records and provenance, including fixture-only DUR identity (#104 amendment);
2. trust only client user messages and add the emergency/drug validator backstop (#106);
3. bound and classify Pass 1 structured-output recovery;
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
an independent 89-test diff audit found no actionable P0/P1.

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

1. Independently mergeable after a fresh maintainer review: #101, #102, #105, #107, #109.
2. After #102 merges, rebase #103 on current `main` and verify that both the packaged-lifecycle CI
   step and the Vite client-environment guard remain present; then require fresh three-check CI.
3. First amend #104 for fixture-only `(itemSeq, kind)` binding, add RED-before/GREEN-after proof,
   refresh the PR description, and require independent P0/P1 review plus fresh CI.
4. Only then run the provenance/chat chain: #104 → #106 → #110 → #111 → #112. Retarget and require
   fresh CI at each arrow.
5. Error/fixture chain: after the amended #104, retarget #108 to `main`, require fresh CI and merge
   it; then retarget #113, require fresh CI, and merge it.
6. Build one clean RC only after those merges. Re-run the full DoD and chat/map browser matrix on
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
- `R01-CAN-001/005/008` command-policy authority, which the unpublished lexical parser did not close;
- #95/#96 and the separately owned facility/profile/frontend P0 set;
- Holiday XML fixture semantics and any approved startup manifest;
- a final integrated RC, fresh CI after every retarget, browser verification, and security diff scan;
- canonical Round 7 was not run, so `saturation_proven=false` remains authoritative.
