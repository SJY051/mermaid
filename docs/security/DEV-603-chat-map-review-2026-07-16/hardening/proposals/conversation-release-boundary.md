# Security Hardening Proposal: Typed conversation release boundary

## Decision

Decide whether medical-answer authority remains distributed across request filtering, prompt construction, grounding, `AnswerValidator`, controller replacement, and React rendering, or whether one server-owned release boundary produces the only answer type the UI may render.

## Executive Recommendation

The complete option set is:

- **Option 1: Consolidated local guards.** Keep the current answer shape and strengthen role filtering, context delimiting, field binding, and semantic checks in existing components.
- **Option 2: Candidate-to-release compiler.** Treat all model output as `CandidateAnswer`, bind it against a server evidence registry, apply semantic and §2 policy, and expose only `ReleasedAnswer` to the controller/UI.

I recommend Option 2 under the current safety constraints. Option 1 is attractive for a narrow demo recovery and remains necessary as tactical protection, but it leaves future fields and branches able to bypass a distributed convention.

## Evidence

I inspected the final receipts and the controlled-provider evidence behind the semantic-output rows. The following identifiers remain human-readable here rather than relying on the registry in `context.md`.

| Evidence | Finding or audit | What it establishes |
| --- | --- | --- |
| `R01-CAN-018` | [Answer-level model prose lacks semantic medical grounding](../../artifacts/central_validation_round06/batch-01/validation.jsonl) | **Observed:** schema-valid diagnostic, curative, and word-form dosing prose reached HTTP 200. |
| `R01-CAN-019` | [Drug-card translations are not semantically bound](../../artifacts/central_validation_round06/batch-01/validation.jsonl) | **Observed:** official-looking translated directions/warnings do not have a deterministic meaning-level binding to the retrieved record. |
| `R01-CAN-021` | [Government narrative is elevated into a privileged system message](../../artifacts/central_validation_round06/batch-01/validation.jsonl) | **Observed:** external strings cross from authoritative fact data into model instruction authority. |
| `R01-CAN-027` | [Client-authored assistant history bypasses user-only screening](../../artifacts/central_validation_round06/batch-01/validation.jsonl) | **Observed:** anonymous client text is preserved with `assistant` role while safety prescans consume only user text. |
| `R05-CAN-002` | [Raw allergen labels enter server-stamped safety copy](../../artifacts/central_validation_round06/batch-05/validation.jsonl) | **Observed:** client-origin text can acquire server-authored safety presentation. |
| `R05-CAN-004` | [Display-name-keyed grounding collision](../../artifacts/central_validation_round06/batch-05/validation.jsonl) | **Needs review:** structural identity collapse is observed; a harmful real collision remains unproven. |

**Inferred:** these are recurrence from one ownership problem. The current code asks several components to remember which roles, strings, fields, identities, and prose are authoritative. A newly added field is safe only if every component remembers to apply the right subset of checks.

## Current Design And Failure Mode

The controller rebuilds a request, the service preserves selected client history, public-data strings are projected into privileged context, the model creates a schema-shaped object, grounding replaces some fields, the validator checks structural relationships, and the UI renders the resulting prose and cards. Controls exist, but there is no type-level distinction between an untrusted candidate and a server-released medical answer.

That distinction matters because structural validity is not medical validity. The controlled-provider evidence proves that a candidate can satisfy the schema while diagnosing, promising a cure, and expressing a dangerous dose in words. At the same time, strengthening a broad text filter can fail-close legitimate answers. We need one owner that can reject, replace, or degrade individual fields while preserving safe server facts, with policy reviewed by humans rather than improvised as prompt wording.

## Desired Invariants

- Every client-authored history item is untrusted data; no caller-selected role grants instruction or safety authority.
- Public-API strings remain data with stable record identity; they never become instructions merely because they came from government.
- Only `ReleasedAnswer` may leave the backend answer boundary, and every medical/provenance field in it has a recorded release decision.
- Server-owned fields—source references, allergy state, emergency actions, disclaimer, data mode, and authoritative safety copy—cannot be supplied or selected by the model.
- Semantic policy changes require clinical/product approval and retain a reason code that can be observed without logging the user text.

## Constraints And Non-Goals

The design must preserve the OpenAI-compatible provider and the two-pass retrieval shape. It must not turn the model into a fact source, weaken fail-closed behavior, or invent clinical policy. It does not attempt to solve provider retention or prove that all external strings are benign. No implementation is authorized by this document.

## Before Architecture

[Current conversation release flow](../diagrams/conversation-release-boundary-before.mmd)

The decisive edge is the last one: `AnswerValidator` receives a broadly answer-shaped object, but the UI cannot tell which fields were copied, grounded, replaced, or merely allowed.

## Options

### Option 1: Consolidated local guards

This option keeps the public DTO and current controller flow. We would centralize the role allowlist, delimit external context as data, add exact record-ID joins, and extend current semantic checks to answer-level prose and card translations. Its strongest case is delivery speed: it adds no new wire type or compiler abstraction and can make each reproduced path fail safely.

The residual concern is coverage drift. Every new DTO field, fallback, SSE branch, or server-authored replacement must still be audited across controller and validator. Text-policy false positives also remain coupled to whole-answer rejection unless the current DTO gains explicit per-field degradation semantics.

[Option 1 after view](../diagrams/conversation-release-boundary-local-guards-after.mmd)

| Change | Before | After | Security consequence | Cost |
| --- | --- | --- | --- | --- |
| Client roles | Selected roles preserved | Central allowlist/normalization | Narrows role steering | Compatibility review for existing history |
| External records | System-context strings | Delimited data context | Reduces instruction authority | Prompt and provider regression tests |
| Answer checks | Structural plus partial grounding | Added semantic/identity guards | Blocks known unsafe candidates | False-positive tuning; clinical approval |
| Release type | Same DTO before/after validation | Still the same DTO | Does not prevent future bypass by construction | Low migration cost |

Performance should remain close to current behavior because checks are in-process and bounded by existing response size. Memory is also nearly neutral. Reliability may improve for known cases but whole-answer fail-closed behavior can still reduce availability. Rollback is focused because the wire contract remains unchanged.

### Option 2: Candidate-to-release compiler

This option introduces an explicit authority transition. The provider can produce only a `CandidateAnswer`. A server-owned compiler receives that candidate, the exact evidence registry, deterministic safety state, and reviewed policy; it emits either a `ReleasedAnswer`, a typed server-authored degradation, or a reason-coded refusal. The controller and frontend accept only the released type.

The attractive part is not a new class name; it is completeness. Adding a candidate field does not make it renderable until the compiler defines its authority and release rule. Record identity, translations, actions, summary/guidance prose, provenance, and safety state can be handled separately, so a bad optional sentence need not erase verified cards unless policy says the whole combination is unsafe.

What gives me pause is semantic-policy ownership. A compiler with an unreviewed word list simply moves the over-strong filter. Clinical reviewers must approve diagnosis/dose/cure policy and degradation behavior, and the released type must make “unchecked but rendered” impossible. The compiler adds bounded CPU and temporary objects; the dominant latency remains upstream I/O, but this must be measured on maximum-size answers. A shadow comparison can establish behavior before the public DTO switches, and rollback can keep the old output adapter while disabling release of new fields.

[Option 2 after view](../diagrams/conversation-release-boundary-typed-release-gate-after.mmd)

| Change | Before | After | Security consequence | Cost |
| --- | --- | --- | --- | --- |
| Trust transition | Implicit across methods | Explicit `CandidateAnswer` → `ReleasedAnswer` compiler | One auditable release choke point | New internal types and adapters |
| Fact binding | Display fields and partial replacement | Stable evidence IDs and per-field decisions | Prevents provenance/translation drift | Evidence registry integration |
| Semantic policy | Prompt plus scattered checks | Reviewed release policy with reason codes | Blocks unsafe prose without model authority | Clinical/product governance |
| Rendering | UI trusts answer DTO | UI accepts released DTO only | Invalid fields cannot bypass backend | Coordinated FE–BE contract change |

## Comparison

| Dimension | Option 1: local guards | Option 2: release compiler |
| --- | --- | --- |
| Security | Improves known paths; future branch drift remains | Makes authority transition explicit and field-complete |
| Performance | Likely neutral; source-derived | Bounded extra validation/copy; unmeasured |
| Memory | Neutral | Small bounded candidate/released duplication |
| Reliability | Known failures block; whole-answer rejection remains | Supports typed per-field degradation; compiler becomes critical path |
| Operability | More rejection codes in existing logs | Stable reason codes pair naturally with value-free telemetry |
| Migration | Low wire-contract change | Coordinated internal and FE–BE type migration |
| Rollback | Revert focused checks | Retain adapter/feature gate until released type is proven |

## Recommendation

I recommend Option 2 because the evidence crosses roles, external context, identity, semantics, and rendering; no one local patch owns that full boundary. Option 1 becomes preferable only if the immediate horizon cannot tolerate a contract transition. Even then, we should describe it as tactical containment and keep the release compiler as the reviewed destination.

## Evidence Coverage And Residual Risk

| Evidence | Option 1 | Option 2 | Tactical fix still required? |
| --- | --- | --- | --- |
| `R01-CAN-018` — unsafe answer prose | Addresses known prose patterns | Addresses through complete semantic release decision | Yes, until compiler is authoritative |
| `R01-CAN-019` — unbound translations | Addresses with local comparison | Addresses via evidence-ID field binding | Yes |
| `R01-CAN-021` — public data as system authority | Mitigates with delimiters | Addresses by data-only evidence projection | Yes |
| `R01-CAN-027` — client assistant role | Addresses with allowlist | Addresses through untrusted conversation normalization | Yes |
| `R05-CAN-002` — raw allergen in server copy | Addresses with escaping/copy rules | Addresses by server-owned released safety fields | Yes |
| `R05-CAN-004` — display-name collision | Unknown until collision proof | Mitigates structurally via stable IDs | Needs-review proof remains required |

Residual risk includes clinical-policy error, provider retention, malicious but correctly identified upstream facts, and UI misuse outside the released DTO. No proposal proves the 22 needs-review rows.

## Migration And Rollout

Design adoption should preserve every current tactical safety check until release parity is demonstrated. The reversible boundary is an adapter that can produce the existing response shape from `ReleasedAnswer`; it allows comparison without letting an unvalidated candidate reach users. The order and ownership of implementation are intentionally deferred to a separate brief.

## Validation Plan

- Re-run the sealed controlled-provider cases for diagnostic/cure/dose prose and emergency+card output.
- Mutate every model-owned field and prove the compiler either grounds, replaces, degrades, or rejects it with a value-free reason.
- Compare legitimate OTC answers before/after to quantify false rejection and partial-degradation behavior; clinicians approve the acceptance set.
- Benchmark maximum provider response size for compiler CPU, allocations, and tail latency against the current validator.
- Verify the frontend has no render path accepting `CandidateAnswer` or raw provider JSON.

## Implementation Work Packages

Intentionally omitted. The user requested a design portfolio without an implementation plan; work packages require option selection and the human gates above.

## Open Questions

- Which answer-level claims must reject the whole answer, and which may degrade only one field?
- What clinically reviewed representation should replace fragile dose/diagnosis word lists?
- Does the public API need a versioned wire change, or can `ReleasedAnswer` remain internal initially?
- Which provider-retention guarantee is required for pass 1 and pass 2?
