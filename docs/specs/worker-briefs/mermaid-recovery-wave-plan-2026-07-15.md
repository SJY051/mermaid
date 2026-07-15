---
title: mermAid recovery, PR review, and safety-hardening wave plan
status: active — PR #93/#87/#99–#103/#107 merged; remediation stack published; release NO-GO
created: 2026-07-15 KST
owner: SJY051 (윤서진)
root_orchestrator: Codex / GPT-5.6 Sol xhigh
goal_thread: 019f6551-80f7-70c0-ae7a-30c95bb6b51c
---

# mermAid recovery wave plan

## 1. Outcome and decision ownership

The root orchestrator plans the work, dispatches bounded workers, verifies every worker result against
the real repository, and returns evidence-backed `GO`, `NO-GO`, `WAITING`, or `UNKNOWN` decisions.
SJY051 owns the final decision. No worker or orchestrator pushes, merges, opens or approves a PR, or
posts an external comment without SJY051's explicit authorization. SJY051 later granted explicit
overnight authorization to commit, push, open separated remediation PRs, and merge independently
verified safe PRs while recording the rationale. That authorization does not waive a P0/P1 hold or
make the pending clinical decisions.

The active global goal now covers, in order:

1. Restore and independently verify clean chat and map behavior.
2. Keep merged PR #93 and #87 on the release baseline while their bounded follow-ups remain assigned
   to the original worker.
3. Complete issue #92 as the frontend contract gate for the merged nullable facility-detail field.
4. Fix the confirmed BE-1 and configuration P0 findings in isolated, dependency-ordered waves.
5. Complete issue #94 after #92, plus the bounded P1 observability and UX follow-ups owned by SJY051.
6. Reconcile the final canonical DIAG report against the release candidate.
7. Present the remaining decisions and release recommendation to SJY051.

Wave 0 started after SJY051 approved the prepared goal, routing policy, and wave plan. Its result is
recorded under Gate 0 below; later waves remain dependency-gated.

## 2. Source priority

When sources disagree, use this order:

1. `AGENTS.md`, especially §2, the Review guidelines, and §10–13.
2. Code and tests at the assigned base revision.
3. `mermaid-handoff-2026-07-15.md`, supplied by the maintainer, as the canonical handoff note.
4. The final canonical DIAG report when it becomes available.
5. The interim Track A/B and Track C diagnostic evidence, remembering that it was based on
   `654f906e00e81648d1482210b6a9171747dddd75` and must be reproduced on the assigned base.
6. `mermaid-maintainer-deadline-checklist-2026-07-16.md`, supplied as external secondary advice. It
   does not override the handoff note or repository evidence.

## 3. Model and effort routing policy

### Root

- The root orchestrator stays on **GPT-5.6 Sol xhigh** during active waves.
- SJY051 may raise the root to **Sol max** for the final canonical DIAG reconciliation or another
  single high-value review where deeper single-agent reasoning is worth the cost.
- Do not use Ultra. Delegation is explicit and controlled by the root.

### Worker quality floor

Except for genuinely trivial work, do not recommend:

- Luna at `xhigh` or below;
- Terra at `high` or below;
- Sol at `medium` or below.

Therefore the normal non-trivial worker floor is:

- **Luna max** for clear, repeatable, bounded QA or mechanical evidence collection;
- **Terra xhigh** for bounded but substantial implementation, runtime, configuration, or test work;
- **Sol high** for complex review and safety-relevant judgment;
- **Sol xhigh** for ambiguous trust-boundary, FE–BE safety-contract, or difficult provider work;
- **Sol max** only for the hardest single final synthesis or verification pass.

Preserve these explicit relative preferences when choosing between adjacent candidates:

- Prefer Terra xhigh over Sol medium.
- Prefer Luna max over Terra medium.
- Prefer Sol high over Terra max.

The current collaboration `spawn_agent` interface does not expose a per-call model or effort field.
Worker model recommendations are therefore explicit routing preferences, not unverified claims about
the actual child model. If a safety-critical writer cannot be confirmed to meet the quality floor,
the root Sol xhigh performs or re-performs the critical implementation and uses the worker only for
bounded research, tests, or independent review.

Workers must not delegate to their own subagents even if the local depth setting technically permits
it. The root owns all fan-out.

## 4. Isolation and concurrency

- The shared checkout is not a writer workspace. At plan creation it was four commits behind
  `origin/main` and contained the user-owned untracked diagnostic briefs
  `A2-wireframe-parity.md` and `DIAG-chat-map-failures.md`.
- After the facility merges, `main` was `4358efec58d7e18c6bdc1615886185f76d606c08`.
  PR #99 and the canonical security archive PR #100 later produced the historical reconciliation
  baseline `3d586695c46815998fa073e4e9d63d51de27fbc5`. Merged PRs #101/#102/#103/#107 advanced the
  current remote baseline to `aef030df88633db2644ccc2c238ceb3d92e7d871`. New independent writers
  must start from current `origin/main`, or from an explicitly recorded remediation-stack head whose
  dependency chain and merge-base are stated.
- Every writer gets an isolated worktree and branch based on an exact recorded SHA.
- Read-only workers may inspect PR refs or evidence in isolated worktrees without changing them.
- Run no more than three write-heavy workers at once.
- Never run two writers against the same production file or the same moving FE–BE contract.
- Assign process ownership and ports before starting a runtime. A worker stops only processes it
  started and recorded.
- Preserve API quota and secrets. Use fixture mode first; live mode requires an explicit reason and
  must not print secret values.
- The original worker owns follow-up issues #95 and #96. Our implementation workers do not absorb
  those issues merely because PR #93 and PR #87 are now merged.

## 5. Change protocol

For safety or other non-mechanical work, use this sequence:

1. **Research:** establish the current behavior, accepted contract, file allowlist, and red-test plan.
2. **Scaffold:** write tests only and prove red-before for the intended behavior gap.
3. **Root review:** verify the red failure and commit the scaffold when authorized.
4. **Implementation:** keep the scaffold frozen and change only the approved production allowlist.
5. **Worker verification:** targeted green, full repository checks, browser verification when needed.
6. **Root verification:** inspect the diff and independently rerun the relevant evidence.

Never weaken, skip, delete, or rewrite the scaffold to make the implementation pass.

## 5-A. Re-baselined execution order — 2026-07-16 KST

This section is the authoritative execution order after SJY051 explicitly authorized the two
facility merges. The detailed sections below retain the investigation history and individual
contracts; when their old status text conflicts with this section, this section wins.

### Merge baseline — complete

- PR #93 was squash-merged as `ba0733d72cdfdfbac06c380f377addd5b26b1b8e` after its canonical
  HIRA verifier update and green CI. Backend hardening remains in #95; nullable frontend rendering
  remains SJY051-owned issue #92.
- PR #87 was merged only after #93. Its branch was non-force updated with main, the single
  `FacilityService` conflict was resolved additively, backend 465/465, frontend 248/248, production
  build, independent conflict audit, and new GitHub CI all passed. It was squash-merged as
  `4358efec58d7e18c6bdc1615886185f76d606c08`. Backend hardening remains in #96; map UX remains #94.
- #95 and #96 are assigned to `GledoubleN` and are release gates, not work for SJY051's BE-1/FE-1
  workers. Do not duplicate them in our branches.

### Parallel Wave A — complete

The following file sets do not overlap and may proceed concurrently from exact `4358efec`:

1. **A-FE / issue #92:** change the frontend `Facility.distanceMeters` contract to `number | null`,
   render null honestly in `DetailDrawer`, and prove no `0 m` coercion. This is a required contract
   gate before facility-detail wiring and before issue #94. It requires unit, build, and real-browser
   evidence.
2. **A-CONFIG / Vite boundary:** permit only `VITE_NAVER_MAP_CLIENT_ID`, reject every other
   case-sensitive `VITE_*` name before serve/test/build, and keep errors value-free.
3. **A-DRUG / hybrid provenance:** bind every permission/Easy/DUR fallback to its actual query or
   record ID, carry real origin and retrieval time through cache values, and fail closed on mismatch.
   SJY051 explicitly approved adapting `DrugRetrievalTest.java`, `DrugServiceFixtureTest.java`,
   `CacheConfigTest.java`, and adding `DrugHybridFallbackTest.java`/`DrugCacheTest.java` as RED/GREEN
   evidence, with no existing assertion weakened or deleted. The eventual PR and report must explain
   that typed query/result binding plus real origin/retrieval-time propagation changes the client
   contract and therefore requires its test doubles and Redis round-trip evidence to implement that
   same contract; the tests are not being changed merely to make production compile.

**2026-07-16 KST historical checkpoint — `GO` at the isolated-slice stage, superseded by
§15–§16 for the affected published PRs.** Issue #92 now carries `distanceMeters: number | null`; its unit
tests and a real-browser list/detail run prove null renders as `Distance unavailable` and never
`0 m`. The Vite boundary passes 7/7 value-free positive-allowlist cases. The drug slice now carries
typed rows with actual origin and retrieval time, uses mode/configuration/query-aware V2 cache
routes, rejects unbound fallback payloads, and propagates public-source failures rather than
silently skipping them. Its old-base focused matrix passed 61/61 and full backend suite 454/454;
the first latest-main integration passed 64/64 focused and 515/515 full. Independent diff review
found no P0/P1 issue at that checkpoint, and mutations for query binding, origin, cache route,
record ID, and oldest retrieval time all turned the owning tests red. Later targeted audits found
the fixture-only DUR product-binding P0 and the additional stack blockers recorded in §15–§16;
therefore this historical GO is not current merge approval for #104 or its dependants.

The protected-test approval remains part of the eventual PR/report rationale: real origin and
retrieval-time values are now part of the permission/Easy/DUR client result contract, so the
existing test doubles and Redis serialization tests must speak that typed contract. They were not
relaxed to make production compile. The browser gate later exposed one additional integration case:
fixture list candidates can all resolve to the same captured detail record because fixture mode
ignores query parameters. `DrugService.RetrievedContext` now deduplicates by the **actual detail
record identity** before deriving drugs, product names, and sources. The new fixture test failed
RED with expected 1 / actual 3 before that change and passed GREEN afterwards; loosening the
canonical builder's cardinality fail-close or relabelling the record with the requested list ID was
explicitly rejected because either would weaken provenance.

### Wave B — latest-main RC integration — historical checkpoint; published stack now blocked

After each Wave A slice has independent GO evidence, integrate into one fresh `4358efec` RC in this
order:

1. packaged-artifact lifecycle runner and CI ownership checks;
2. Vite boundary, preserving every current-main and lifecycle CI step;
3. bounded Pass-1 recovery plus server canonical cards;
4. forged-assistant user-only boundary and final SA-08 server suppression;
5. hybrid drug provenance;
6. issue #92 frontend contract.

After every step run the owning focused matrix and `git diff --check`. After the full sequence run
backend tests, frontend tests, frontend build, and fixture-mode browser chat/map/detail smoke before
starting another safety contract.

**2026-07-16 KST historical result — `GO` for that unpublished integrated RC, superseded by
§15–§16 for current merge decisions.** This monolithic local RC itself was never
committed or published; its scopes were later split into reviewable PR #101–#113. The ephemeral
integration worktree at `/private/tmp/mermaid-rc-4358efe` was based on exact merged main
`4358efec58d7e18c6bdc1615886185f76d606c08` and contains all six integration steps above. Final
evidence after the fixture-identity correction is:

- backend `./gradlew cleanTest test`: 516 tests, 0 failures/errors/skips;
- frontend client-env boundary: 7/7; `pnpm test`: 250/250; `pnpm build`: success;
  `pnpm lint`: exit 0 with six pre-existing Fast Refresh warnings;
- packaged runtime ownership harness, both Bash syntax checks, and `git diff --check`: success;
- real-browser issue #92 list/detail: `Distance unavailable`, with no `0 m` coercion;
- real-browser fixture chat `I have a fever`: one strict Pass-1 provider request with roles
  `system,user`, one retrieved canonical Tylenol card, no whole-answer Pass 2, no model-rejection
  copy. The first browser run correctly exposed `CARDINALITY_MISMATCH`; the post-fix run logged one
  retrieved drug and rendered the official source/card instead of hiding all records;
- map/list/detail browser evidence remains healthy from the same RC integration pass. Naver SDK
  authentication was already established at the exact registered origin; the isolated RC lacked a
  copied Naver environment value, so that secret-free test run did not claim a second auth proof.

The `/private/tmp` worktree and captures are local, non-durable QA evidence and may disappear. The
durable review evidence is the published PR head, its owning tests, and the verification summary in
the PR description; do not treat the temporary path as an artifact retention guarantee.

At that checkpoint this closed functional recovery and RC integration only. It did not close the
terminal DIAG or the remaining Wave C safety contracts, and later audits in §15–§16 block the
corresponding published stack until its bounded amendments are verified.

### Wave C — remaining safety P0, sequential

1. **Historical RC only; published #106 blocked by §16:** canonicalize any emergency final state to
   a server-authored 119 response with `drugs=[]`.
2. **Historical RC only; published #106 blocked by §16:** remove model control over `answerId`,
   urgency, and safety UI intent on the remaining legacy whole-answer path.
3. **Historical RC only; published #112 blocked by §16:** SJY051 chose server-authored
   empty answers. A true empty official context does not invoke whole-answer Pass 2 and returns a
   fixed unavailable/unknown answer with no active content. This explicitly modifies the earlier
   hybrid semantic-regex proposal because no bounded detector can soundly identify every unknown
   medicine name, while a rejected model turn still incurs latency and provider cost. The eventual
   PR/report must state that the behavior and related tests were modified with SJY051's approval and
   explain this safety, UX, and operating-cost rationale. LEAK-02 is closed for the current reachable
   response surface by removing model prose; role isolation and a disclosure contract remain hard
   prerequisites for any future record-scoped enrichment.
4. **Assessment complete, human approval required:** the clinical packet covers
   anaphylaxis/throat swelling, severe abdominal pain, `gives me hives`, and `no allergies`.
   The decision packet is
   `docs/specs/worker-briefs/W3-C-clinical-expression-review-packet-2026-07-16.md`. No phrase pattern
   is implemented until the emergency/allergy thresholds are written and approved.

W3-B evidence on published PR #112:

- root review confirmed the ordinary-answer split is exhaustive: direct safety answers return first,
  non-empty official contexts use `ServerAuthoredAnswerBuilder`, and a context with no sources or
  grounded drugs uses `ServerAuthoredEmptyAnswer`; the retained whole-answer call is unreachable;
- focused chat safety tests passed, and full backend `./gradlew cleanTest test` passed 537/537 with
  zero failures, errors, or skips;
- real Chrome at `http://127.0.0.1:15175` sent `I have a headache` and synthetic `FAILPASS1`
  through the PR head backend on owned port 18085 and a fake provider on owned port 19093. The
  provider received exactly one Pass-1 request for each turn; no Pass-2 request occurred;
- the normal turn rendered the exact `server-empty-official-data` copy, while the synthetic 503
  rendered the distinct `server-search-unavailable` copy. Neither response exposed the Pass-2
  sentinel; browser console errors/warnings were empty. Screenshot evidence is
  `/private/tmp/mermaid-w3b-empty-answer-final.png` and
  `/private/tmp/mermaid-w3b-search-unavailable-final.png`;
- frontend 248/248 and the production build passed. The initial independent focused audit passed 89
  tests and found no actionable P0/P1 at that checkpoint;
- tests that formerly asserted model-owned empty summary/action/validator behavior were changed only
  with SJY051's explicit contract approval. Their replacements assert provider zero calls, complete
  server DTO equality, empty/unavailable distinction, JSON/SSE equality, and SA-08 precedence;
- the initial checkpoint therefore marked W3-B `GO` as ready PR #112 on exact head
  `34d9a0181a9435871c8f03316ac7c30e008d4563`, stacked on #111. That historical decision is
  **superseded by §16**: a later targeted audit found that a user-product official zero result is
  misclassified as allergy suppression, so #112 is currently blocked.

Those two screenshot paths are local, non-durable QA captures. PR #112's tests, exact head, and PR
description are the durable evidence; the images are not committed artifacts.

W3-A evidence on the isolated RC:

- RED-before: three controller safety-state tests failed, and the emergency-plus-drug validator test
  failed before its production check existed;
- GREEN-after: controller, validator, and triage focused tests passed; both controller
  canonicalization and validator checks were independently mutation-tested red and restored green;
- full backend: 521 tests, zero failures, errors, or skips;
- independent read-only P0/P1 diff audit: no actionable finding;
- real browser: a fake provider returned reserved `answerId=allergy-clarification`, emergency prose,
  `911`, an invented drug, and a model question. The rendered answer contained only the server-owned
  119 emergency shell and fixed non-diagnosis warning; all malicious sentinels and the allergy picker
  were absent. The provider received exactly one Pass 1 and one Pass 2 request;
- browser environment note: the packaged runtime correctly refused the local MariaDB credentials,
  while its jar excludes the test-only H2 driver. UI semantics were therefore exercised with a
  repository-unchanged Gradle init that used the test runtime classpath. Packaged lifecycle behavior
  remains covered by the separate Wave A runner harness and was not re-characterized by this check.

PR/report rationale to preserve: a model emergency value is treated only as an untrusted escalation
trigger, never as a diagnosis or UI contract. Phone number, wording, urgency state, answer identity,
actions, disclaimer, and the empty medicine list are all rebuilt by the server. The extra validator
rule independently closes future paths that might bypass the controller canonicalization. SJY051
explicitly approved the necessary production/test scope and requested that this reasoning accompany
the eventual PR and release report.

This W3-A evidence belongs to the unpublished isolated RC. Published PR #106 contains the role
boundary and validator backstop but not the controller canonicalization proven above; §16 therefore
blocks #106 until that bounded server-emergency amendment is restored and independently verified.

### Wave D — frontend and bounded P1

- Issue #94 starts only after #92 is integrated and the merged ER backend contract is present.
- Request-ID/value-free logging and fixture-error semantics are published as #105, #107, #108, and
  #113. The JSON fixture PR is stacked on #108 because it integrates both the #104 drug adapters and
  the malformed-body handler; Holiday XML and startup fail-fast remain separate decisions.
- The attempted command-policy parser is blocked and unpublished. It grew by roughly 630 production
  lines while shell variables, `eval`, interpreter-spawned Git, and Git option abbreviations still
  bypassed it. `R01-CAN-001/005/008` therefore remain open until authority moves below lexical shell
  parsing.
- #95 and #96 must be green or explicitly accepted before release; their implementation remains
  with `GledoubleN`.

### Final release gate

Build one release-candidate SHA, run the full DoD and browser matrix, then reconcile the terminal
canonical DIAG inventory against that SHA. Do not restart PR #93/#87 review unless a regression is
observed on the release candidate.

The canonical Round 06 report is now archived on `main` by PR #100 at
`docs/security/DEV-603-chat-map-review-2026-07-16/report.md`, with 188 canonical findings and the
supporting validation/attack-path artifacts. It supersedes the interim draft for finding inventory,
but it explicitly records that Round 7 was not run and `saturation_proven=false`. Therefore the
remediation report may reconcile known findings against a release candidate, but must not claim
exhaustive security closure or that no new P0 exists. Final release remains `NO-GO` until the open
code PRs, human clinical decisions, and separately owned release gates are resolved.

## 6. Wave 0 — clean runtime baseline

### W0-A — clean runtime and 500 classification

- Mode: read-only diagnosis; generated build artifacts and owned runtime processes only.
- Recommended worker: **Terra xhigh**.
- Goal: build and run the assigned current base from an isolated worktree and determine whether the
  historical chat and facility 500s reproduce without the deleted-worktree classpath.
- Required evidence: revision, cwd, artifact/classpath, PID ownership, HTTP status, request ID,
  result count, provenance mode, and absence or presence of linkage errors.
- Do not kill an existing process or propose a map source fix from historical symptoms alone.

### W0-B — real-browser chat and map matrix

- Mode: read-only browser QA against W0-A's clean runtime.
- Recommended worker: **Luna max**.
- Goal: distinguish Naver SDK accepted/rendered state from facility API and frontend rendering state;
  reproduce the representative chat, emergency, pharmacy, and hospital paths.
- ER is not treated as current-main behavior unless PR #87 has actually landed in the assigned base.

### Gate 0

Wave 1 may start only when the root can state one of:

- `GO`: clean current-base runtime is identified and historical stale-runtime failures are separated
  from remaining source defects;
- `NO-GO`: the same source-level failure reproduces on a clean current base with request-ID evidence;
- `UNKNOWN`: a named missing observation prevents classification.

**2026-07-15 KST result — `NO-GO` for current functionality; classification complete.** Exact remote
`main` `d6a143a9b3fea571e84f0af12a03d3b5af3b6ee1` ran as an isolated fixture-mode boot JAR. The
historical allergy, emergency, pharmacy, and hospital linkage failures did not reproduce, confirming
the deleted-worktree JVM as their incident cause. A single fever probe still retrieved three fixture
drugs and then returned `local-fallback`: Pass 2 coercion failed with `JsonMappingException` before
`AnswerValidator`. Wave 1 may therefore address the structured-output source/provider contract while
keeping stale-runtime lifecycle hardening separate. The registered `localhost:5173` origin rendered
Naver Maps but remained wired to the stale 8080 process; an isolated exact-d6 frontend against the
clean backend rendered three pharmacies, two hospitals, and the code-authored chest-pain emergency
answer. Combined exact-d6 plus registered-origin Naver authentication remains a deployment-process
observation, not evidence for a map-source change.

## 7. Wave 1 — functional recovery

### W1-A — structured-output contract and fix

- Recommended worker: **Sol xhigh**.
- Dependency: Gate 0.
- Sequence: research → accepted contract → red scaffold → implementation → browser verification.
- Goal: fix the provider/schema/coercion path that returns `COERCION_FAILED` after successful
  retrieval, without displaying raw prose or weakening grounding and validation.
- Required decisions: provider response contract, structured-model allowlist truth, bounded retry or
  normalization behavior, value-free telemetry, and safe malformed-output behavior.

### W1-B — stale-runtime lifecycle hardening

- Recommended worker: **Terra xhigh**.
- Dependency: W0-A classification.
- Goal: determine whether a repository-owned, minimal packaged-artifact or process-ownership measure
  can prevent a JVM from running from a removed worktree. A no-code operational conclusion is valid
  when repository enforcement would be disproportionate.
- If the fix would touch a file changed by an in-flight facility PR, postpone or restack it rather
  than editing concurrently.

### W1-C — functional regression matrix

- Recommended worker: **Luna max**.
- Dependency: accepted W1-A implementation revision and any accepted W1-B revision.
- Goal: prove valid structured answers reach grounding and validation, malformed answers still fail
  closed, emergency output remains server-authored, and the map remains healthy.

### Gate 1

The functional PR is ready for SJY051's decision only after targeted tests, all repository DoD
commands, and the real-browser matrix pass. Opening, pushing, or merging the PR still requires
explicit authorization.

**2026-07-15 KST result — `NO-GO`; bounded recovery is correct but insufficient for the current
provider.** In an isolated exact-`d6a143a9` clone, Wave 1 added tests and a two-attempt state machine
that retries an HTTP 400 or structurally unusable schema response once without `response_format`,
keeps both attempts inside the original 60/120-second logical-pass budget, sets `store:false` on
Pass 1, and replaces final malformed/prose output with a fixed server-authored refusal. Focused
tests and the full backend suite pass. No validator, grounding, controller, provenance, prompt,
schema, or frontend contract was weakened.

The live fixture-mode fever probe `X-Request-Id: 6707561c-63c1-44a3-8821-a31c19b51362` showed the
remaining blocker precisely. Pass 1 recovered in 8.5 seconds and retrieved three drugs. Pass 2's
schema attempt then consumed about 92 seconds before being classified unusable; the schema-less
retry had only the remaining 28 seconds and the logical pass failed closed at 120 seconds. An
earlier prompt-only full-answer probe parsed but was correctly rejected with
`INV6_PRODUCT_SOURCE_MISMATCH`, the existing OUT-06 swapped product/source contract. Therefore the
next step may not increase the timeout, reset the retry budget, expose raw prose, rewrite a card's
source ID, or weaken validation. SJY051 must approve a contract-level direction for the current
model/provider before this isolated diff can become a functional PR.

Switching models alone was also tested and ruled out. The repository's other allowlisted candidate,
`kimi-k2.6`, completed the same fever turn in 49.8 seconds and produced structurally valid output,
but request `X-Request-Id: d4601359-0d2b-4620-8167-1fbd637d91c5` was rejected by the same sole
`INV6_PRODUCT_SOURCE_MISMATCH`. The remaining availability defect is therefore the model-owned
multi-product card/source pairing contract, not only `glm-5.2` JSON formatting.

W1-B classified the historical 500/map incident separately: a Gradle `bootRun` JVM outlived its
deleted worktree and later failed lazy nested/synthetic class loads. A clean packaged JAR returns
fixture pharmacy/hospital lists and the server-authored emergency answer. Repository prevention is
deferred to a separate packaged-artifact/process-ownership tooling slice so it does not mix with the
chat/provider change.

### Gate 1 architecture decision — approved by SJY051 on 2026-07-15 KST

The live evidence rules out treating whole-answer retry plus whole-answer discard as the normal AI
service path. It preserves safety but still pays the full model cost and latency before returning a
refusal. The next implementation therefore requires an explicit product-contract choice.

| Option | Immediate behavior | Cost and latency | Safety/product assessment |
|---|---|---|---|
| A. Keep full Pass 2, then show server cards only after failure | Wait for the current model path; compensate after rejection | Still pays up to the full Pass 2 budget and discards frequent output | Emergency-only mitigation; **not recommended as steady state** |
| C. Server canonical cards now; disable product-card Pass 2 until qualified | After Pass 1 retrieval, return server-owned product, ingredient, Korean dose, DUR, prescription, allergy and source fields; model translation fields remain null and the existing UI explains that they are unavailable | Removes the known-bad Pass 2 call entirely for non-empty drug contexts | **Recommended immediate Wave 1 recovery**, subject to SJY051 approval of the temporary product behavior and fixed English copy |
| B. Server canonical cards plus optional record-scoped enrichment | Canonical cards exist first; a model may fill only `indicationSummary` and `labelCautions`, one official record per call; failed fields remain null | Short bounded parallel enrichment budget; later circuit breaker; no whole-answer discard | **Recommended target architecture**, after semantic-policy and prompt/data-role contracts are approved |

For option B, the model must never author product identity, source, ingredients, dose, warnings,
allergy, urgency, actions, disclaimer, answer ID, or provenance. Enrichment starts disabled for the
currently failing GLM/Kimi paths. Cross-user caching remains disabled until the semantic output gate
exists; otherwise a single plausible mistranslation would be amplified to every user of that record.

The immediate option-C scaffold can stay backend-only: a server answer builder and controller tests.
It must build cards in server-source order, validate the completed server candidate with the existing
`AnswerValidator`, preserve blocked allergy data, refuse inconsistent/missing source mappings, make
no product-card Pass 2 call for non-empty contexts, and leave emergency/allergy direct answers ahead
of the builder. It may not edit the model's rejected card, translate Korean medical text, add a
dependency, or change the frontend DTO.

SJY051 approved option C as the immediate Wave 1 recovery contract and option B as the follow-up
target architecture. Implementation must still keep the new fixed English fallback copy reviewable
rather than silently treating it as final PM/QA copy. The eventual PR description must explicitly
state that option C temporarily diverges from the documented two-pass English-explanation flow,
identify the measured whole-answer cost/latency and rejection evidence behind that choice, and name
the qualification conditions under which record-scoped enrichment may later be enabled. Option B's
field-level semantic-policy contract remains a separate follow-up implementation gate.

Option C removes the known-bad full-answer Pass 2, but it does not remove Pass 1a: the extractor is
still the only path from symptoms to government-data retrieval. The clean A4 reproduction returned
non-JSON and therefore searched for nothing, while the isolated bounded prototype recovered the same
Pass 1 class in about 8.5 seconds. Wave 1 therefore includes a separate Pass-1-only recovery slice:
an allowlisted model may retry HTTP 400 or parser-unusable structured output once without
`response_format`, within the original 60-second logical budget and only when at least 15 seconds
remain. It must keep `n=1`, `store=false`, a small server-owned output ceiling, the same two
server-authored messages, value-free telemetry, and a maximum of two provider calls. Timeout,
connect, 429, 5xx, valid-empty, parser-salvageable, and non-allowlisted paths do not retry. The old
prototype's Pass-2 retry and `StructuredOutputFallback` changes are explicitly not part of this
slice. Option C and Pass-1 recovery may be implemented in separate isolated clones because their
production files do not overlap, but integration, live-provider, and browser gates remain serial.

## 8. Other-author PR review lane

**Closed on 2026-07-16 KST.** SJY051 authorized both merges. The detailed findings retained below
are historical pre-merge evidence, not a current `DO NOT MERGE` decision. Remaining work moved to
#95/#92 for PR #93 and #96/#94 for PR #87.

### PR #93

- Recommended reviewer: **Sol high**.
- Read-only complete-diff review against current `main` after the author updates it.
- Produce an explicit `MERGE` or `DO NOT MERGE` recommendation and a Korean draft comment; do not post.

**Historical head `b765d2c8ff34747c9919b097b2d508edde714639` — pre-merge review snapshot.** The head was
based directly on current `main` (`d6a143a9`) and merges mechanically. GitHub CI run `29420255513`
is green in all three jobs; an isolated root run also passed 453 backend tests, and the preceding
head's unchanged frontend and API-contract surfaces passed 248 frontend tests, production build,
and 54/54 executable API checks. The new commit adds a mutation-sensitive blank-address assertion
and does not regress those surfaces.

Five merge conditions remain after the latest commit:

1. `FacilityService.detail()` treats any string accepted by Java's base64url decoder as a
   well-formed HIRA `ykiho`. On an isolated fixture runtime, both `facility:hira:not-base64`
   (`X-Request-Id: 13af4839-6cef-4ed7-a32f-2fc919c010e6`) and the canonical base64url encoding of
   `not-ykiho`, `facility:hira:bm90LXlraWhv`
   (`X-Request-Id: 6d83ed60-155a-43f9-b5ce-08f42873244e`), returned `501 NOT_IMPLEMENTED`; the
   documented malformed-id contract is `404`. The existing test uses `not*base64`, which the
   decoder rejects before this gap and therefore cannot detect it.
2. The NMC HPID regex rejects malformed values, but unlimited distinct syntactically valid IDs such
   as `C0000000`, `C0000001`, and so on still each reach `basisDetail()`. This does not yet enforce
   the requested admission/rate boundary around the 1,000-call/day provider quota.
3. `PharmacyDetailBatch` carries only the row and origin. `FacilityService` therefore stamps the
   current request time into `SourceRef.retrievedAt` and `operation.verifiedAt` even on a six-hour
   cache hit, presenting cached data as freshly retrieved. The existing HIRA cache contract already
   preserves the actual fetch timestamp across cache hits.
4. `parseBasisDetail()` checks only null coordinates. `PublicApiResponse.number()` accepts JSON or
   string `NaN`/infinity through `Double.parseDouble`, so non-finite locations can still become a
   verified-looking detail response.
5. The secret-bearing exception causes were removed in all three pharmacy paths, but the requested
   sentinel service-key/coordinate log-capture regression is absent. The only new failure-path
   assertion is `basisDetail(...).hasNoCause()`, so it neither inspects emitted logs nor guards
   `findNear()` and `weeklyHours()` against reintroducing URI-bearing exception text.

The author subsequently fixed the canonical HIRA verifier and PR #93 was squash-merged as
`ba0733d72cdfdfbac06c380f377addd5b26b1b8e`. The remaining backend boundaries are tracked in #95.
The separately approved FE follow-up #92 is now part of Parallel Wave A because the frontend does
not yet call the detail endpoint but must accept its nullable distance before later wiring.

### PR #87

- Recommended reviewer: **Sol high**.
- Starts only after #93 is accepted or merged and #87 is updated against that state.
- #93 and #87 are not parallel review/fix lanes because they overlap `FacilityService` and cache tests.

PR #87 was updated with the merged #93 main, independently verified, and squash-merged as
`4358efec58d7e18c6bdc1615886185f76d606c08`. Backend follow-up is #96 and frontend ER map work is
#94. This lane does not reopen absent a release-candidate regression.

## 9. Wave 2 — parallel P0 workstreams

Starts after the W1-A contract is stable. A trust-boundary branch that touches `ChatProxyService`
must stack on the structured-output head rather than racing it.

### W2-A — client role boundary and untrusted government data

- Recommended worker: **Sol xhigh**.
- Goal: ensure an anonymous client cannot author an `assistant` identity and government strings are
  not promoted into a privileged system instruction.
- Main area: `chat/**` and its security tests.

### W2-B — hybrid query/result binding and provenance

- Recommended worker: **Sol high**.
- Goal: prevent an acetaminophen query from returning unrelated ibuprofen fixture rows marked live;
  preserve actual row origin and reject query/result mismatch.
- Hard implementation boundary: `drug/**` and its tests; no `chat/**` production edits.

### W2-C — Vite browser-public positive allowlist

- Recommended worker: **Terra xhigh**.
- Goal: permit only explicitly approved browser-public `VITE_*` names and reject API/service-key or
  unknown browser variables without printing values.
- Main area: `frontend/vite.config.ts` and its dedicated test scaffold.

### Gate 2

All three workstreams require red-before/green-after evidence and independent root review. Passing
tests do not close a P0 unless the root confirms the actual trust or provenance boundary.

## 10. Wave 3 — sequential chat safety contracts

### W3-A — server-owned safety state and OUT-04

- Recommended worker: **Sol xhigh**.
- Dependency: W2-A stable.
- Goal: remove model control over allergy UI state, urgency, and safety intent; canonicalize every
  emergency final answer to server-owned 119 action with `drugs=[]`.
- FE and BE contract changes must land together.

### W3-B — medical prose semantic gate and LEAK-02

- Recommended worker: **Sol xhigh**.
- Dependency: W3-A contract fixed.
- **Contract amended and approved by SJY051 on 2026-07-16.** The release implementation removes
  whole-answer model prose instead of attempting an incomplete medicine-name/diagnosis regex gate.
  Non-empty official contexts use server canonical cards; true empty contexts use the fixed
  `ServerAuthoredEmptyAnswer`; direct emergency/allergy/SA-08 answers remain server-authored.
- The modification must always be disclosed in the PR and reports. Reason: a sound arbitrary
  medicine-name detector is unavailable for empty context, and paying for model output that is then
  discarded is both poor UX and avoidable operating cost.
- Initial verification in PR #112 covered four RED-before contract failures, focused GREEN,
  independent mutation proof, backend 537/537, frontend 248/248 plus build, and real Chrome for both
  usable-empty and Pass-1-unavailable. The fake provider received no whole-answer call. A later
  targeted audit found the distinct user-product official-zero misclassification in §16, so this
  evidence does not make #112 merge-ready.
- Published #106 retains the pre-model emergency response and independent emergency-plus-drugs
  validator, but not the controller canonicalization that existed only on the historical RC. It is
  therefore blocked by §16 until model emergency output is again replaced with the server-authored
  119 state.

### W3-C — emergency and allergy expression review packet

- Recommended worker: **Sol high**.
- Mode: assessment only until human review.
- Goal: produce the evidence and unanswered clinical/product questions for anaphylaxis/throat
  swelling, severe abdominal pain, `gives me hives`, and `no allergies` negation handling.
- No model may decide the clinical threshold or implement patterns before a written human-approved
  contract exists.
- Packet prepared at
  `docs/specs/worker-briefs/W3-C-clinical-expression-review-packet-2026-07-16.md`; C1–C3 await a
  named human clinical reviewer and C4 awaits SJY051's explicit product decision.

## 11. Wave 4 — bounded P1 follow-ups

### W4-A — request-ID and value-free logging

- Recommended worker: **Terra xhigh**.
- Goal: add MDC request ID to useful logs while removing ingredient/product values and precise
  coordinates from persistent logging.
- Published slices: merged #107 removes health search-term values; #108 gives malformed JSON a
  value-free handler. #105's request-ID correlation is P0-blocked because arbitrary client header
  text would become global MDC log content. Coordinate and other-lane sinks remain open.

### W4-B — fixture integrity error semantics

- Recommended worker: **Terra xhigh**.
- Goal: ensure a missing local fixture cannot masquerade as a real government-service outage and
  assess startup completeness validation.
- Published as #113 for the shared JSON loader and #104-integrated drug fallback helpers. A local
  missing/corrupt fixture is non-retryable 500; real government failure remains retryable 503.
  Holiday XML and startup manifest policy are not included and remain explicit follow-ups.

### W4-C — `NearbyFacilities` pending false-empty

- Recommended worker: **Luna max**.
- Goal: render no-results only after a successful empty response; pending and rejected states stay
  distinct.

### W4-D — facility cache mode namespace

- Recommended worker: **Sol high**.
- Dependency: #93 and #87 settled.
- Goal: prevent fixture/live cache cross-contamination while preserving row-owned provenance and
  Redis compatibility.

## 12. Final wave — canonical DIAG and release gate

- Root model: SJY051 raises the root to **Sol max** for this pass.
- Mode: read-only reconciliation and verification.
- Compare the final DIAG report and every prior P0 against the actual release-candidate SHA.
- Classify every item as `FIXED`, `OPEN`, `PARTIALLY FIXED`, `STALE`, `NOT REPRODUCED`, or
  `ACCEPTED PRODUCT GAP` with current evidence.
- Rerun the full backend test, frontend test, frontend build, and real-browser chat/map matrix.
- Return `RELEASE GO` or `RELEASE NO-GO`. SJY051 makes the final release and merge decision.

Do not launch another exhaustive Ultra swarm merely to duplicate the canonical DIAG scan.

**2026-07-16 KST result — known-finding reconciliation complete; release `NO-GO`.** The final DIAG
semantically classified all 188 canonical candidates, including 33 reportable P0 rows, but Round 6
receipts were not physically adopted into every ledger, attack paths were not materialized, and
Round 7 did not run (`saturation_proven=false`). All 33 reportable P0 rows survived at the original
`main@3d586695` crosswalk; after merged #107, 32 survive on current `main@aef030df`, with twelve
unmerged candidate fixes, four partial rows, four human decisions, and twelve rows lacking a
complete published candidate. The full evidence and exact PR-head
crosswalk are in
`docs/specs/worker-briefs/DEV-603-final-diag-reconciliation-2026-07-16.md`. No clean corrected RC
exists while #104/#106 remain P0-blocked and #109/#110/#112 remain P1-blocked.

## 13. Verification and reporting contract

Before any code change is called complete, run now and record exit codes:

```bash
cd backend && ./gradlew test
cd frontend && pnpm test
cd frontend && pnpm build
```

If browser-visible behavior changed, verify it in a real browser. For every worker result, the root
reports:

1. task, base SHA, worktree, recommended model/effort, and whether the model was actually confirmable;
2. files changed and exact scope;
3. red-before and green-after evidence;
4. targeted and full-suite commands with exit codes;
5. browser evidence and request IDs where applicable;
6. conflicts or dependencies;
7. remaining P0/P1 and uncertainty;
8. the root recommendation;
9. the exact decision reserved for SJY051.

## 14. Stop conditions

Stop a worker and return to the root when:

- the required fix leaves the approved file or lane allowlist;
- a new dependency is needed;
- a safety contract requires clinical, product, or SJY051 judgment;
- current repository evidence contradicts the assigned premise;
- the worker would need to touch another author's branch;
- a port or process is owned by someone else;
- a test appears wrong or can pass for the wrong reason;
- a destructive or external action would be required.

## 15. Late audit override — #104 merge hold

A final product-binding audit after the published wave found that fixture-only DUR lookup ignores
the requested `itemSeq` and attaches the four non-empty captured fixture rows to product
`202005623`, even though those rows belong to four different products. The older parser/assembly test
documents the mismatch and treats it as expected fixture behavior.

This overrides earlier merge-ready language for #104. SJY051 subsequently authorized the protected
fixture/README amendment scope, with the reason required in the PR/report. The PR and both dependent
stacks remain blocked until #104 proves `(itemSeq, kind)` binding, correct zero-row fixture behavior,
cache invalidation, mutation-sensitive tests, full verification, and independent P0/P1 review. No
fixture was modified during the audit that found the issue.

## 16. Second late audit override — #106, #109, #110, and #112

Targeted final-state and mutation audits supersede the earlier broad green review for four more PRs:

- #106 is blocked because emergency+drug rejection falls through the generic fallback, removing the
  emergency urgency and 119 action instead of returning the server-authored emergency state.
- #109 is blocked because the current nine-case harness stays green when leading-placeholder
  multi-candidate scanning or the `change-me*` branch is broken.
- #110 is blocked because raw product-array usability is decided before user-authored product
  binding, so a model-only product can suppress the bounded retry and become usable empty.
- #112 is blocked because a completed zero-result lookup for a user-named product is described as
  suppression of an AI-selected medicine.

These findings do not invalidate unrelated successful assertions; they show that the earlier test
sets did not cover the final output and mutation boundaries above. No source or protected test was
changed during this audit. Each PR requires an approved bounded amendment, red-before/green-after
evidence, full verification, independent P0/P1 review, and fresh CI before its merge hold is lifted.

## 17. Third late audit override — #105 request-ID privacy hold

PR #105 adds the request-ID MDC key to every log line, but the current `RequestIdFilter` accepts any
nonblank client `X-Request-Id` up to 100 characters verbatim. Exact-head execution of the existing
filter-backed request test logged the supplied value as `[requestId=trace-me-123]`. A client can
therefore substitute symptom, allergy, coordinate, or log-shaping text and make it persistent log
content. The existing logging test manually injects MDC and does not cover this boundary.

This overrides earlier merge-ready language for #105. It remains open until the server accepts only
an approved opaque ID shape or mints a UUID, hostile values are replaced, a real
filter-to-MDC-to-appender test is mutation-sensitive, the full DoD passes, and independent P0/P1
review plus fresh CI are green. No #105 source or test was modified during this audit.
