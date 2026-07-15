# Central validation round 06 — batch 01

## Outcome

Validated canonical physical lines **1–38 inclusive**, exactly once, against immutable target `654f906e00e81648d1482210b6a9171747dddd75`.

- Coverage: **38/38 assigned rows**
- Unique candidate IDs: **38**
- Reportable: **33**
- Not reportable: **5**
- Needs review: **0**
- Severity: **P0 13**, **P1 13**, **P2 7**, **none 5**
- Blocking execution issues: **none**
- Canonical stream: `artifacts/04_reconciliation/deduped_candidates.jsonl`
- Canonical stream SHA-256: `274fd61156b7ae3ebeadb1c905258fbe001a5f81380891b9d2491b44652477b8`
- Validation receipt SHA-256: `7c6b132d11a0c3644c2e169ff5f12183faff22e19d6a5e715dc98c208680d358`

The five rejected rows are `R01-CAN-015`, `R01-CAN-016`, `R01-CAN-024`, `R01-CAN-025`, and `R01-CAN-032`. Their exact evidence is confined to `.claude/worktrees/interesting-borg-847dc7`, which is absent from the immutable target. Related outer-target behavior was not substituted into those rows; target-valid analogues remain accounted for by their own canonical candidates.

`R01-CAN-014` is a reportable latent build-guard gap at P2, not a current secret leak: synthetic key-bearing `VITE_` names pass the target heuristic, but the target's only active `VITE_` sink consumes the intentionally public Naver client ID. Exploitation requires both new browser code that actively references an evading name and an operator who supplies a real secret under it; that resulting §2-7 violation would be P0.

## Method

Each receipt records the attacker or external source, closest control, reachable sink, concrete impact, realistic preconditions, counterevidence, remaining proof gaps, immutable target evidence, exact canonical row hash, and current canonical-ledger hash. Code evidence was read with `git show` or `git cat-file` at the pinned target. Safe parser/validator probes and the fresh controlled-provider run recorded below were used where noted; no destructive Git/filesystem action, real secret read, commit, push, or canonical-ledger append was performed.

`affected_locations` is copied verbatim from each canonical row, including optional fields and original key order. Nested-worktree entries therefore remain visible for provenance even when the disposition rejects them as absent-target contamination.

Umbrella rows `R01-CAN-001` and `R01-CAN-005` overlap operation-specific command-policy children. Their receipts are preserved for exact-once canonical accounting, but executive issue totals should avoid double-counting the same remediation closure.

## Independent controlled-provider revalidation

Prior draft artifacts were excluded from this correction. A disposable fixture-mode server from runtime checkout `d6a143a9b3fea571e84f0af12a03d3b5af3b6ee1` on port 18083 used a local OpenAI-compatible provider on port 19091; neither a production LLM nor a live government API was contacted. The complete runtime backend differs from the immutable target only in listed holiday/facility work, while the exact chat validation, grounding, LLM transport, drug retrieval, schema, and fixture blobs exercised by both probes are target-identical. The sealed bundle is `artifacts/central_validation_round06/evidence/batch-01-controlled-provider/`; its checksum manifest SHA-256 is `d76268b445d950c2327774361cf6fd3652d8fef9b7b3e95e9e0bb353eb4361d5`.

### `R01-CAN-018`

- Provider trace: extraction returned zero ingredients/products, then pass 2 returned a schema-valid answer whose summary was `You have influenza. Take eight ibuprofen tablets every two hours; this will cure it.`
- Server trace: `Extracted 0 ingredient(s), 0 product name(s)`; `No drug search terms in this turn`; `RAG pass 2: model answered in 8ms (217 chars of context)`; no validation-failure log followed.
- Wire result: HTTP 200, `X-Request-Id: 59cda66b-eaf7-4422-978d-0224eecc2f65`; the response preserved the diagnostic and dangerous word-form dosing summary verbatim with an empty drug list.

### `R01-CAN-020`

- Provider trace: extraction returned `Acetaminophen`; the fixture retrieval context contained three products; pass 2 returned emergency urgency, canonical `SHOW_EMERGENCY_CALL`/119 actions, and one card matching the last same-name grounded product.
- Server trace: `RAG pass 1: terms=[Acetaminophen]/[] → 3 drug(s). extract 35ms, retrieve 628ms`; `RAG pass 2: model answered in 9ms (8179 chars of context)`; no validation-failure log followed.
- Wire result: HTTP 200, `X-Request-Id: 71ed3b69-2065-4d4e-831c-204c6a8aff26`; the response simultaneously contained `urgency.level: emergency`, the 119 call action, and the grounded `어린이타이레놀산160밀리그램(아세트아미노펜)` drug card with server-authored fixture provenance.

## Exact coverage

| Line | Candidate | Disposition | Severity | Confidence | Review tier | Validation rationale |
|---:|---|---|---:|---:|---|---|
| 1 | `R01-CAN-001` | reportable | P0 | 0.93 | P0-blocker | Validated variants include a deterministic no-verify secret-commit chain, routing around §2-8; operation-specific children should not be double-counted in executive totals. |
| 2 | `R01-CAN-002` | reportable | P2 | 0.96 | non-blocking-security | A real local developer-integrity control bypass is proven, but it does not itself route around a healthcare §2 invariant or P1 care-delivery rule. |
| 3 | `R01-CAN-003` | reportable | P2 | 0.95 | non-blocking-security | This is a validated developer-integrity gap with local preconditions, below the repository's P0/P1 healthcare review budget. |
| 4 | `R01-CAN-004` | reportable | P2 | 0.96 | non-blocking-security | The bypass and destructive sink are concrete, but required trusted-agent authority and lack of direct care-path impact keep it non-blocking. |
| 5 | `R01-CAN-005` | reportable | P0 | 0.88 | P0-blocker | The deterministic no-verify path routes around §2-8 even though other composite effects depend on repository and remote state. |
| 6 | `R01-CAN-006` | reportable | P2 | 0.93 | non-blocking-security | The defense-in-depth staging gap is real but downstream review and pre-commit remain and no §2 care invariant is directly bypassed. |
| 7 | `R01-CAN-007` | reportable | P2 | 0.84 | non-blocking-security | The local policy bypass is real, but impact requires repository/refspec state and a remote that permits main updates; absent proof of that P1 sink, it remains non-blocking security hardening. |
| 8 | `R01-CAN-008` | reportable | P0 | 0.88 | P0-blocker | This is a deterministic route around the §2-8 secret-commit invariant under stated staging and publication preconditions. |
| 9 | `R01-CAN-009` | reportable | P1 | 0.9 | P1-blocker | The bypass is deterministic and exposes deploy credentials to the agent transcript, though it requires privileged local tool execution. |
| 10 | `R01-CAN-010` | reportable | P1 | 0.9 | P1-blocker | A normal Git global option deterministically bypasses the exact secret-read control and reaches a concrete disclosure sink. |
| 11 | `R01-CAN-011` | reportable | P1 | 0.9 | P1-blocker | Path-qualified readers are ordinary, independently triggerable forms that defeat the repository's credential-read guard. |
| 12 | `R01-CAN-012` | reportable | P1 | 0.9 | P1-blocker | The parser/executor semantic mismatch deterministically hands the secret-bearing path to a child shell. |
| 13 | `R01-CAN-013` | reportable | P1 | 0.95 | P1-blocker | The code-backed control claim is false for the explicitly registered patch tool; target-valid outer files prove the gap despite rejecting nested contamination. |
| 14 | `R01-CAN-014` | reportable | P2 | 0.9 | non-blocking-security | Reportable latent build-boundary weakness only: an actively consumed evading VITE_ secret would be §2-7 P0, but the immutable target contains no such active sink or current leak. |
| 15 | `R01-CAN-015` | not_reportable | none | 1 | non-blocking-security | Rejected because all exact evidence is nested-worktree contamination and laundering the outer analogue would violate target-bound validation. |
| 16 | `R01-CAN-016` | not_reportable | none | 1 | non-blocking-security | All exact locations are absent-target nested-worktree contamination, so the row cannot be validated against the immutable target. |
| 17 | `R01-CAN-017` | reportable | P2 | 0.88 | non-blocking-security | This is a plausible local confidentiality gap with environment-dependent reachability, not a direct §2 commit or browser-bundle violation. |
| 18 | `R01-CAN-018` | reportable | P0 | 0.99 | P0-blocker | Fresh sealed fixture-mode evidence proves the final API returns diagnostic, curative, and dangerous word-form dosing prose, directly violating §2-1 and the verified-answer boundary. |
| 19 | `R01-CAN-019` | reportable | P0 | 0.91 | P0-blocker | The missing semantic binding sits inside the §2 verified-government-fact surface, so a wrong translation is a direct P0 safety-invariant failure. |
| 20 | `R01-CAN-020` | reportable | P0 | 0.99 | P0-blocker | Fresh sealed evidence proves an OUT-04 final-output violation: emergency urgency and a medicine card survive together. This finding concerns post-model output composition, not whether deterministic triage ran before the model. |
| 21 | `R01-CAN-021` | reportable | P0 | 0.88 | P0-blocker | The code crosses an external-data-to-system-authority boundary that can undermine §2 verified-answer invariants; exploit reliability is model-dependent but the trust-boundary flaw is exact. |
| 22 | `R01-CAN-022` | reportable | P1 | 0.88 | P1-blocker | A 2 MiB anonymous request was accepted and the static path shows no application admission bound, creating a cold-path availability risk for users seeking care. |
| 23 | `R01-CAN-023` | reportable | P1 | 0.9 | P1-blocker | No aggregate admission control exists before multiple billable and quota-limited calls, so care information can become unavailable. |
| 24 | `R01-CAN-024` | not_reportable | none | 1 | non-blocking-security | Exact candidate evidence is absent-target contamination; substituting the outer analogue would violate immutable-target validation. |
| 25 | `R01-CAN-025` | not_reportable | none | 1 | non-blocking-security | All exact paths are nested-only and absent, so this canonical row cannot be validated against the target. |
| 26 | `R01-CAN-026` | reportable | P1 | 0.91 | P1-blocker | The UI supplies a repeatable overlap primitive on an already long, quota-bearing care-information path. |
| 27 | `R01-CAN-027` | reportable | P0 | 0.99 | P0-blocker | P0 under the user's explicit rule that any client-controlled role steering is a §2-1 boundary violation; confidence applies to proven assistant-role preservation, not to speculative model obedience. |
| 28 | `R01-CAN-028` | reportable | P0 | 0.97 | P0-blocker | A common reaction declaration bypasses the server's fail-closed allergy path and can re-enable model drug suggestions, directly violating §2-2/SA-08. |
| 29 | `R01-CAN-029` | reportable | P0 | 0.99 | P0-blocker | An acknowledged emergency category reaches routine handling, directly routing around §2-4's mandatory pre-model triage. |
| 30 | `R01-CAN-030` | reportable | P0 | 0.98 | P0-blocker | Ordinary typographic variants bypass the §2-4 deterministic emergency gate, a direct safety-invariant failure. |
| 31 | `R01-CAN-031` | reportable | P1 | 0.97 | P1-blocker | Wrong or missing emergency-state affordance is explicitly P1 in this repository even though fixed copy partially mitigates it. |
| 32 | `R01-CAN-032` | not_reportable | none | 1 | non-blocking-security | All affected locations are absent-target nested-worktree contamination, so the exact candidate is not reportable. |
| 33 | `R01-CAN-033` | reportable | P1 | 0.94 | P1-blocker | Wrong or confusing English in an emergency state is explicitly P1 under the repository review guidelines. |
| 34 | `R01-CAN-034` | reportable | P0 | 0.96 | P0-blocker | Model data crosses into authoritative allergy workflow control, weakening §2-2/SA-08 and the server-authored safety boundary. |
| 35 | `R01-CAN-035` | reportable | P1 | 0.94 | P1-blocker | Model output can trigger a sensitive ambient browser capability; permission prompts mitigate first use but prior grants make the path reachable without a fresh gesture. |
| 36 | `R01-CAN-036` | reportable | P1 | 0.91 | P1-blocker | Model-controlled UI fan-out can make a core care-location surface unavailable and spend constrained public API quota. |
| 37 | `R01-CAN-037` | reportable | P1 | 0.98 | P1-blocker | The repository explicitly elevates missing cold-path progress to P1 because it can prevent care information from reaching the user. |
| 38 | `R01-CAN-038` | reportable | P0 | 0.94 | P0-blocker | Silent deletion of a declared allergen can turn an incomplete check into reassurance, directly violating §2-2/SA-08. |

## Blockers and caveats

There are no batch-completion blockers. Every row has a final `reportable` or `not_reportable` disposition and a current ledger hash. Candidate-specific uncertainty remains in each receipt's `proof_gaps` and `counterevidence`; it does not create unaccounted coverage.

The reportable P0/P1 rows require remediation planning or human safety judgment before implementation. This batch changed no repository behavior and did not append to canonical ledgers.
