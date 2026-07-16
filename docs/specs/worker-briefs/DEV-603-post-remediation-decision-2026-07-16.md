---
title: DEV-603 post-remediation decision
status: current maintainer decision packet — functional recovery GO, release NO-GO
created: 2026-07-16 KST
owner: SJY051 (윤서진)
validated-code-baseline: 040397f0717398a5a7a50294dc39bd013628e6bc
validated-code-tree: daaaf5dcf12cc6e178d2b06a8ecf44724f2a201b
---

# DEV-603 post-remediation decision

## Decision

The bounded chat and map recovery is **GO** on the tested paths. The repository-wide release is
still **NO-GO**.

The recovery conclusion is supported by a clean `main` build, full backend and frontend suites,
the repository lifecycle and secret-boundary verifiers, green GitHub CI, and a browser matrix on
the exact validated code baseline. The release conclusion stays NO-GO because human clinical and
product decisions, other-owner P0 work, frontend ER completion, older facility/profile/command
authority findings, and final release artifacts remain open. The final DIAG scan also did not prove
saturation.

This is the primary current maintainer decision artifact. It supersedes the current-state sections
of the following historical execution documents without rewriting their history:

- `DEV-603-recovery-decision-report-2026-07-16.md`;
- `DEV-603-final-diag-reconciliation-2026-07-16.md`;
- `mermaid-recovery-wave-plan-2026-07-15.md`.

The companion `DEV-603-post-remediation-diag-delta-2026-07-16.md` is the supporting audit artifact.
It records the conservative P0 crosswalk and the limits on any claim derived from the 188-row DIAG
inventory.

## Exact validated baseline

| Evidence | Result |
|---|---|
| `origin/main` | `040397f0717398a5a7a50294dc39bd013628e6bc` |
| Git tree | `daaaf5dcf12cc6e178d2b06a8ecf44724f2a201b` |
| GitHub push CI | run `29459867531`, backend / frontend / secret scan **3/3 successful** |
| Backend | `cd backend && ./gradlew test`: **580 tests**, 0 failures, 0 errors, 0 skipped |
| Frontend tests | `cd frontend && pnpm test`: **20 files / 250 tests** passed |
| Frontend lint | `cd frontend && pnpm lint`: exit 0; six pre-existing Fast Refresh warnings |
| Frontend build | `cd frontend && pnpm build`: success; only the known chunk-size warning |
| Backend lifecycle | `./bin/run-backend.test.sh`: PASS |
| Browser-visible env boundary | `cd frontend && node scripts/verify-client-env-boundary.mjs`: **7/7** |
| Pre-commit secret guard | `./bin/verify-precommit-secret-guard.sh`: **16/16** |
| Worktree state | clean detached verifier; test services stopped after verification |

The push CI is at
<https://github.com/SJY051/mermaid/actions/runs/29459867531>.

The normal local `bootRun` first encountered stale local MariaDB credentials. The browser verifier
therefore used the repository's H2 test runtime through `bootTestRun`; that runtime completed the
same application paths without changing source. This is a local environment note, not a product
failure and not evidence for production database readiness.

## What the recovery changed

### Chat refusal and provider-cost path

The user-visible whole-answer path no longer waits for an untrusted Pass 2 response and then throws
away already retrieved government records when that response is malformed. The reachable normal
path now uses the LLM only for Pass 1 search-term extraction. Under the default allowlisted
`glm-5.2` configuration, that is one structured attempt with one bounded schema-free retry only for
an eligible malformed 2xx or HTTP 400 result. A non-allowlisted model gets one schema-free attempt
and no retry. The server owns the final card shell, product identity, ingredients, official label
text, warning rows, allergy state, urgency, UI actions, and provenance.

This directly addresses the observed failure mode where `glm-5.2` returned malformed structured
output after the server had already retrieved official records. It reduces both wasted latency and
the probability that provider formatting discards the whole answer. It does **not** claim that
model calls cannot fail, that provider cost is zero, or that every eligible Pass 1 query recovers.
Issue #119 remains for reactive retry-log correlation, and #118 preserves the later incremental
removal of dormant whole-answer code.

The server-authored transition was intentionally split across small PRs. Dormant Pass 2 source was
not deleted in the behavior PRs, avoiding the multi-thousand-line deletion that SJY051 rejected as
too risky to review in one step.

### Chat 500 and map blank state

The common direct cause of the observed chat 500s and facility 500s was a stale JVM running from a
deleted worktree classpath. The runner now builds and atomically publishes a boot JAR outside the
worktree. Its lifecycle harness proves the child no longer depends on source build output and is
stopped and cleaned up when the worktree marker disappears; PR #102 separately health-checked the
real packaged runtime. No triage, facility, or government adapter behavior was weakened to hide
that runtime error.

Facility results on the clean fixture runtime are non-empty and retain server-owned provenance and
tri-state hours. The main map distinguishes error from a successful empty result. The frontend ER
surface remains deliberately unavailable until #94 is implemented; the merged backend ER support
does not by itself make the user-visible flow complete.

### Safety, provenance, privacy, and configuration

The merged slices now:

- trust only exact client `user` roles;
- replace any model emergency escalation with the fixed drug-free 119 response;
- bind drug results, fallback origin, source identity, and retrieval timestamps server-side;
- reject inconsistent positive result counts in DUR envelopes;
- classify local fixture-integrity errors separately from government outages;
- remove health search terms and rejected validation values from logs;
- put opaque request IDs into ordinary and bounded-parallel log paths;
- use a positive allowlist for browser-visible `VITE_*` values; and
- inspect every non-deleted staged path, including quoted, renamed, type-changed, and newline paths,
  in the pre-commit secret guard.

These changes close or make unreachable several known DIAG paths. They do not close the entire DIAG
inventory; the companion delta names the exact conservative boundary.

## Merged pull requests

All remediation PRs published by the BE-1/config recovery wave are merged. During finalization,
`GledoubleN` opened #125 for other-owner issue #96. It is the remote's only open PR at this snapshot
and remains outside this wave.

### Runtime and rendered-state recovery

| PR | Merge commit | Outcome and reason |
|---|---|---|
| #101 | `b32d30928c5d9c469f5372aba1eebfcc241a9104` | Render unavailable distance honestly instead of inventing a measurement. |
| #102 | `526a4c6942ee662925003189daa90fe2f45ffcd5` | Run removable-worktree backends from an owned packaged artifact, preventing stale nested-class failures. |
| #103 | `f71b073b0a8028a66f775ac224dc0d8ae3152443` | Replace the incomplete browser-secret denylist with an explicit public-variable allowlist. |

### Drug and chat recovery

| PR | Merge commit | Outcome and reason |
|---|---|---|
| #104 | `794b7af7f72ba06ba6a1d1a6fb47b3b66566d10a` | Bind government rows, fallback origin, timestamps, and cache identity to the requested record. |
| #106 | `c51f426493e2a270ff2dcffc27e4748e974152e1` | Accept only client `user` messages at the model boundary. |
| #110 | `5238b3fbe405d22ed561c0238ec5caa28b553617` | Recover eligible malformed Pass 1 output with one bounded retry and explicit availability status. |
| #111 | `a2e91fa735d20a6ef37982aafa0c8670a52a4a3e` | Build non-empty official drug cards on the server rather than accepting a whole model answer. |
| #112 | `7006d82cd9919d5e06a5c1bc0cf1283625e28e0b` | Return distinct fixed empty, unavailable, and allergy-suppressed terminal states. |
| #113 | `a7b51ac4d36cb1d2dfcf91987979566a3924a7cd` | Keep local fixture corruption separate from an upstream government outage. |
| #116 | `8d90218e4a49307a610b380a7fc819ef28971f1b` | Canonicalize every model emergency escalation to the fixed 119 response with `drugs=[]`. |
| #120 | `cd5248b5993d098642f64106be9eaf5cd6b02884` | Fail closed for common single-`l` allergy misspellings without claiming causal-hives coverage. |
| #123 | `cb8b1ae517afe1e7b5faeaf43122816026092019` | Reject malformed DUR envelopes whose declared positive count is inconsistent with returned rows. |

### Observability and repository boundaries

| PR | Merge commit | Outcome and reason |
|---|---|---|
| #105 | `21b5650faa0512c46286afa607664575ba52d2b9` | Include an opaque request ID in server log lines. |
| #107 | `aef030df88633db2644ccc2c238ceb3d92e7d871` | Log health-search counts and stages, not ingredient or product values. |
| #108 | `bba2a050dff714eca99ed990a61a09b3a5e4a634` | Handle malformed chat JSON without request-body or full-throwable logging. |
| #109 | `1f8ef37727dc16efa69195e510b38678dbd01412` | Close nested-env and placeholder/multi-assignment secret-hook gaps. |
| #121 | `7b4dee76fb95b44e293fee1792974c24693aefa2` | Replace rejected validation values with fixed reason codes in logs. |
| #122 | `c91a0522c2f011f1a20b1dc3eec4d0385519ad8d` | Propagate and restore MDC across the bounded parallel worker helper. |
| #124 | `040397f0717398a5a7a50294dc39bd013628e6bc` | Parse staged paths losslessly and include rename/type-change cases in the secret guard. |

### Decision evidence

| PR | Merge commit | Outcome and reason |
|---|---|---|
| #114 | `c6ce956767f5ae212df08faff6a608a5de6b909f` | Archive the wave plan, W3-C decision packet, and then-current DIAG reconciliation. |

PRs #87 and #93 were also reviewed at their latest heads and merged before this remediation stack.
Their follow-up P0s remain assigned to their existing owner as #96 and #95 respectively; this wave
did not absorb or modify that owner's follow-up work.

The bounded follow-up issues #68, #92, #115, and #117 are closed by #120, #101, #122, and #121
respectively. #118 and #119 remain open because they describe later cleanup and a separate Reactor
callback boundary, not incomplete acceptance criteria hidden inside those merged PRs.

## Exact-browser matrix on the validated code baseline

| Scenario | Observed result |
|---|---|
| `I have a headache but I am alergic to aspirin. What should I do?` | Fixed server allergy clarification and picker rendered; fake provider call count remained zero. |
| Chest-pain emergency phrase | Fixed emergency/119 state rendered; fake provider call count remained zero. |
| Switch from emergency chat to Map | Persistent 119 alert, `tel:119`, and Open Chat remained available. |
| Fixture facility list | Five facilities rendered with Sample data provenance and tri-state hours. |
| 서울적십자병원 detail | `Hours unknown`, 966 m, call link, official source, and Sample data rendered. |
| ER control | Disabled with honest unavailable copy; no false claim that the frontend ER flow is complete. |

The isolated worktree intentionally did not copy the developer's Naver client ID, so this run
rendered the honest `VITE_NAVER_MAP_CLIENT_ID is not set` state instead of authenticating the SDK.
The earlier exact-origin diagnostic proved that the registered `http://localhost:5173` origin
loaded and rendered Naver Maps successfully, and `useNaverMap.ts`'s SDK load/auth path did not change
in this recovery wave. `FacilityMap.tsx` did change for honest distance rendering, so a release
candidate still needs one final registered-origin browser pass with its actual public client ID.

## Work that remains with SJY051

### Decisions before implementation

1. Complete the W3-C truth tables for anaphylaxis/airway swelling, sudden or severe
   abdominal/stomach pain, medicine-caused hives, and explicit negative allergy expressions such
   as `no allergies` or `NKDA`. These are clinical/product contracts, not regex cleanup.
2. Decide the server-owned copy contract for `R05-CAN-002`: raw unverified-allergen text must not
   become a safety instruction, while the user's editable chip may still preserve what they typed.
3. Decide a structural command-authority design for `R01-CAN-001/005/008`. The unpublished lexical
   parser remained bypassable and was correctly abandoned rather than published.

### Bounded engineering follow-ups

1. #119 — carry request ID into reactive provider retry callbacks without propagating transcript or
   health values.
2. #94 — complete the frontend ER contract and verify its privacy/error/provenance behavior after
   the other-owner #96 work is ready.
3. #55 and #61 — rescope or close their now-unreachable whole-answer requirements; do not claim
   completion merely because the current path bypasses the dormant code.
4. #118 — remove dormant whole-answer Pass 2 only in small, reviewable slices after current
   behavior is locked. Do not repeat the multi-thousand-line deletion.
5. Coordinate the still-open facility/profile/allergy/overlay findings listed in the DIAG delta
   without taking ownership away from another lane.

### Human and release evidence

1. PM/QA review of the fixed server-authored non-empty, empty, unavailable, allergy, and emergency
   English copy.
2. #23 accessibility/mobile/English UX audit.
3. #24 presentation/demo package.
4. #25 deployment, documentation synchronization, and release tag.
5. One clean final RC with real registered-origin Naver browser verification, live API access
   checks under approved credentials, full DoD, and a security delta review of that exact SHA.

## Work that remains with another owner

- #95 — pharmacy detail provenance and lookup boundary hardening, assigned to `GledoubleN`.
- #96 — ER failure redaction, cache isolation, and executable contract, assigned to `GledoubleN`;
  ready PR #125 is open at `bfa3ad0db7882737a84bb391031e1eac59293597` with CI 3/3 green.

These remain P0 release gates. They were intentionally not edited, reassigned, or duplicated by the
BE-1/config recovery wave. This packet records #125's remote state but does not review or approve
another owner's diff.

## Current open issue inventory

This table accounts for every issue that was open in the remote current-state check. “Unassigned”
means GitHub has no assignee; it is not permission for this wave to absorb another lane.

| Issues | Current owner or lane | Decision use |
|---|---|---|
| #118, #119 | assigned to SJY051 / BE-1 | incremental dormant-code cleanup and bounded reactive request correlation |
| #95, #96 | assigned to `GledoubleN` / BE-2; #96 has open PR #125 | other-owner P0 release gates; do not touch |
| #94 | unassigned | frontend ER completion after the backend/privacy contract is ready |
| #55, #61 | unassigned | rescope against the reachable server-authored architecture before implementing dormant-path requirements |
| #98 | unassigned / BE-2 P1 | Seoul operating-hours data quality; coordinate with the facility lane |
| #64 | unassigned | English context for hospital names and addresses |
| #23, #24, #25 | unassigned / #23–#24 PM/QA, #25 BE-1; all P0 | accessibility/UX, demo package, deployment/docs/tag |
| #26 | unassigned / FE-1 P1 | public README transition |
| #28, #30 | unassigned onboarding | BE-2 and PM/QA first-day administrative checklists |

The W3-C decisions and the surviving canonical rows are documented work even though they are not
represented by additional newly opened issues in this wave.

## Recommended resume order

1. Record the W3-C and `R05-CAN-002` human decisions.
2. Coordinate #95 and independently review the owner's #125/#96 before any merge decision;
   separately scope the older facility/profile survivors.
3. Implement and browser-verify #94 only on the agreed backend/privacy contract.
4. Close #119 request-correlation coverage.
5. Rescope #55/#61 against the now-reachable server-authored architecture.
6. Remove dormant Pass 2 incrementally under #118, with one small concern per PR.
7. Complete PM/QA and release issues #23–#25.
8. Build and audit one clean final release-candidate SHA.

The tested chat/map recovery can be used as the base for that work. It is not permission to label
the repository release-ready before the remaining gates are closed.

## Artifact authority

| Question | Primary artifact |
|---|---|
| What should SJY051 decide next? | This document |
| What happened during the waves? | `mermaid-recovery-wave-plan-2026-07-15.md` |
| What exact human clinical decisions are needed? | `W3-C-clinical-expression-review-packet-2026-07-16.md` |
| What can be claimed against the 188-row DIAG inventory? | `DEV-603-post-remediation-diag-delta-2026-07-16.md` |
| What findings did the original scan know? | `docs/security/DEV-603-chat-map-review-2026-07-16/report.md` |
| What proves the current code baseline? | exact SHA/tree, local command results, browser matrix, and GitHub CI listed above |
