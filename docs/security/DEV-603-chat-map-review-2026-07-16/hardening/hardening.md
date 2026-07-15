# Security Hardening Review: mermAid Round 06

## Evidence Basis

This portfolio is a derived design review for immutable target `654f906e00e81648d1482210b6a9171747dddd75`. I inspected the final five validation batches, their 188 exact canonical rows, the repository threat model, and the final Track A/B/C audits. The corrected batch totals are **101 reportable**, **22 needs review**, and **65 not reportable**; among reportable rows, **33 are P0** and **44 are P1**. The [context inventory](context.md) records paths, human titles, and hashes.

The architecture question is not how to patch 101 lines independently. We repeatedly see authority reconstructed at the wrong layer: model text becomes answer authority, safety state is inferred in several components, cached/fallback facts lose identity, logs keep values but lose correlation, facility partial failure becomes false absence, and a demo process has no durable proof of what it is running. Those are six control-owner problems, not 101 unrelated defects.

Claims are labeled deliberately. **Observed** behavior comes from a final receipt or retained audit. **Inferred** diagnoses connect those observations across a shared owner. **Proposed** components exist only in this portfolio and do not close findings.

## Constraints

We assume a balanced profile: keep the public login-free product, Spring/React shape, OpenAI-compatible provider, public APIs, MariaDB/Redis, and fixture development mode. No measured latency or memory budget was supplied, so resource effects are source-derived or hypothetical and carry explicit validation plans.

The human gates are part of the design, not postscript. A clinician must approve emergency categories, allergy equivalence, rescue-medication language, and semantic medical policy. Product/UX and accessibility owners must approve safety and facility state presentation. Privacy must approve value-free event fields and retention. Operations must approve quota/deadline/cache-mode policy. The release/security owner must approve artifact attestation and public-environment policy. None of these decisions may be delegated to the model or inferred from scan severity.

Central adoption is pending. The corrected batch files have not received a fresh consolidated check-only seal because the shared workspace has concurrent tracked facility/config/test changes. We can review this derived portfolio now, but we should not treat its source counts as centrally adopted or begin implementation until that diff and seal gate are resolved.

## Opportunity Portfolio

| Opportunity | Evidence | Options | Recommendation | Proposal |
| --- | --- | --- | --- | --- |
| Typed conversation release boundary | Unsafe answer prose, misbound translations, privileged public-data context, and client assistant roles (`R01-CAN-018/019/021/027`) | 1. Consolidate local guards; 2. Compile `CandidateAnswer` into `ReleasedAnswer` | Option 2, gated by clinical policy review | [Conversation release proposal](proposals/conversation-release-boundary.md) |
| Server-owned emergency/allergy state machine | Emergency+medicine output, reaction misses, category omissions, model-owned workflow transitions (`R01-CAN-020/028/029/034`) | 1. Expand matchers/checks; 2. Make invalid safety transitions unrepresentable | Option 2, after clinical and product state-table approval | [Safety state proposal](proposals/safety-state-machine.md) |
| Provenanced evidence envelope | Restamped cache facts and query-independent hybrid fallback (`R01-CAN-098/105/109/113`, `R05-CAN-023`) | 1. Add binding checks and mode keys; 2. Carry identity/freshness/mode in one evidence type | Option 2; retain direct tactical checks during migration | [Evidence identity proposal](proposals/evidence-identity.md) |
| Value-free correlated telemetry | Health terms, coordinates, device capability, malformed-body text in logs, but no ID-exact stage trail (`R01-CAN-059/061/069`, `R06-CAN-003`) | 1. Redact known text logs and add MDC; 2. Replace value logging with a typed event contract | Option 2, with privacy and operations approval | [Observability proposal](proposals/value-free-observability.md) |
| Truthful facility result algebra | Pending false-empty, unknown-to-closed, partial-failure collapse, malformed-envelope empty success (`R01-CAN-037/094/095`, `R03-CAN-001`, `R04-CAN-013`) | 1. Harden branches locally; 2. Return typed complete/partial/empty/unavailable/unsupported states | Option 2, with copy and quota-budget review | [Facility truth proposal](proposals/facility-truthful-results.md) |
| Attested demo artifact and runtime | Secret/workflow guard bypasses plus a stale deleted-worktree JVM serving the demo (`R01-CAN-001/008`, `R04-CAN-001/002/003`, Track A/B) | 1. Repair guards and add preflight; 2. Build an attested bundle run by an owning supervisor | Option 2 for demo/release; Option 1 remains immediate defense in depth | [Demo integrity proposal](proposals/demo-artifact-integrity.md) |

## Recommendation Summary

I recommend the structural option in each proposal, but not as a single rewrite. The strongest common move is to replace reconstructable conventions with types that retain authority: `ReleasedAnswer`, server-owned safety state, a provenanced evidence envelope, value-free events, a facility result algebra, and an attested runtime identity. Each type creates one choke point where a §2 invariant can be reviewed and tested. The local-guard options remain useful as tactical protection and as a lower-risk fallback if delivery time prevents a structural change.

The first design decisions should be the conversation release boundary and safety state machine because they sit directly on medical output. Evidence identity should follow closely because the release gate cannot prove facts whose identity, freshness, or mode was already lost. Observability and demo attestation are enabling controls: they would have made the demo-week failures attributable by request ID and prevented a stale JVM from presenting itself as the current build. Facility result typing is independently valuable and should align with the provenance type so partial rows retain honest source and hours state.

What gives me pause is not the conceptual direction but the policy content. A semantic gate can become another over-strong rejection surface if clinicians do not own its medical rules. A deterministic safety machine can trap negated or historical symptoms if product and clinical reviewers do not approve the state table. A facility algebra can still mislead if UX collapses `partial` into `empty`. The design therefore centralizes ownership; it does not automate human judgment away.

## Next Decisions

1. Clear the unrelated tracked workspace diff and run a fresh corrected central check-only adoption; do not reuse the superseded seal.
2. Have clinical, product/accessibility, privacy, operations, and release/security owners review the explicit gates in the six proposals.
3. Choose between each focused local option and structural option. Structural selection authorizes design refinement only; it does not authorize source changes.
4. Resolve the cited `needs_review` rows before using them to expand medical policy, privacy requirements, or final severity.
5. If an option is selected, request a separate implementation brief. This portfolio intentionally contains no implementation plan or source edits.
