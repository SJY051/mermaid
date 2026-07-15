# Security Hardening Proposal: Attested demo artifact and runtime

## Decision

Decide whether a demo is “current” because a developer started commands in a shared checkout, or because one owned runtime can prove the source, dependencies, resources, configuration class, data mode, and process/port identity it is serving.

## Executive Recommendation

- **Option 1: Repaired guards plus launch preflight.** Fix secret/workflow guard gaps, require clean builds, refuse stale ports/classpaths, and expose build/data-mode identity.
- **Option 2: Attested demo bundle and owning supervisor.** CI creates an immutable manifest-bound bundle; a supervisor owns its process and port lease, verifies resources/config, and exposes runtime identity to the browser and telemetry.

I recommend Option 2 for demo/release operation. Option 1 remains necessary defense in depth for everyday development and secret prevention, but it cannot make an arbitrary long-lived JVM attest to what it loaded.

## Evidence

| Evidence | Finding or audit | What it establishes |
| --- | --- | --- |
| `R01-CAN-001` | [Composite lexical command-policy bypasses](../../artifacts/central_validation_round06/batch-01/validation.jsonl) | **Observed:** command policy parses privileged workflow operations lexically and incompletely. |
| `R01-CAN-008` | [No-verify commit can bypass the secret hook](../../artifacts/central_validation_round06/batch-01/validation.jsonl) | **Observed:** a deterministic path can route around §2-8 protection. |
| `R04-CAN-001` | [Nested `.env` paths bypass filename guard](../../artifacts/central_validation_round06/batch-04/validation.jsonl) | **Observed:** secret-file detection is not path-complete. |
| `R04-CAN-002` | [Placeholder suppression hides real credentials](../../artifacts/central_validation_round06/batch-04/validation.jsonl) | **Observed:** value allow/suppression logic can mask a real credential. |
| `R04-CAN-003` | [Hook installation fails open](../../artifacts/central_validation_round06/batch-04/validation.jsonl) | **Observed:** setup can appear successful while the last-line secret guard is absent. |
| `R05-CAN-020` | [Environment-file variants fall outside protected classification](../../artifacts/central_validation_round06/batch-05/validation.jsonl) | **Observed:** server credentials can cross model/log boundaries through filename variation. |
| `EV-A/EV-B` | [Track A](../../artifacts/finalization/track_a_final_audit.md) and [Track B](../../artifacts/finalization/track_b_final_audit.md) final audits | **Observed:** a stale JVM loaded classes/resources from a deleted worktree and served chat/facility 500/503 responses on the demo port; clean target-equivalent servers did not reproduce those linkage failures. |
| `R05-CAN-009` | [Global gitleaks value allowlist](../../artifacts/central_validation_round06/batch-05/validation.jsonl) | **Needs review:** broad regex overlap is observed; a real detector bypass still needs proof. |

**Inferred:** repository guards and runtime identity are the same control family: both answer “what privileged artifact are we about to publish or execute?” Today the answer relies on token/name heuristics and developer process memory.

## Current Design And Failure Mode

Local hooks and scanner configuration try to prevent dangerous Git/secret operations. Builds and servers can be started independently from mutable worktrees. Vite proxies a fixed backend port without an authenticated build handshake. A process may survive after its source worktree is deleted, retaining only part of a newly compiled class/resource graph and failing with `NoClassDefFoundError` or missing fixture resources.

That is exactly what Track A/B observed. The UI and HTTP error handler were functioning, but the demo served a runtime that no longer corresponded to a coherent artifact. The map key was correct and the model/upstream were often never reached. A reliable demo must refuse this incoherent state before a user request.

## Desired Invariants

- Every demo runtime exposes immutable revision/tree, build artifact digest, resource digest, schema/config version, data mode, and start time.
- The process serving the configured port is owned by the launcher/supervisor that verified that manifest; a stale or foreign process is rejected, not reused.
- Backend/frontend compatibility and required fixtures/classes are checked before readiness.
- Browser-public environment variables come from a positive allowlist; secrets are content-scanned and never depend solely on filename or variable-name heuristics.
- Secret scanning and CI are authoritative publication gates; local hooks are defense in depth and cannot silently report installed when absent.
- A demo can be rolled back only to another internally coherent attested bundle.

## Constraints And Non-Goals

This proposal does not make a developer workstation a production trust anchor or promise cryptographic supply-chain security without key management. It does not replace repository review, CI, or secret rotation. Release/security and operations owners must choose attestation authority, retention, and who may start/stop the demo.

## Before Architecture

[Current build/demo flow](../diagrams/demo-artifact-integrity-before.mmd)

The stale-process edge bypasses the apparent source/build chain: the browser reaches whichever process owns the port, not necessarily the artifact the developer just inspected.

## Options

### Option 1: Repaired guards plus launch preflight

The focused option improves command parsing and secret path/value tests, makes hook installation failure explicit, requires a clean build, checks port ownership/classpath/resource presence, and exposes build/data-mode metadata in health and browser diagnostics. It directly prevents the observed stale-worktree reuse when the prescribed launcher is used.

Its limitation is authority. A developer can still start another process manually, and a preflight script can drift from build contents. Name/path heuristics remain defense in depth rather than a proof that the bundle contains no secret.

[Option 1 after view](../diagrams/demo-artifact-integrity-local-guards-after.mmd)

| Change | Before | After | Security consequence | Cost |
| --- | --- | --- | --- | --- |
| Secret/workflow guards | Lexical/path/name gaps | Canonical parsing plus expanded regression cases | Narrows known bypasses | Ongoing parser/scanner maintenance |
| Launch | Manual commands/shared port | Preflight verifies owner/classpath/resources | Rejects known stale-runtime state | Launcher discipline |
| Runtime identity | Implicit | Health/browser build and mode metadata | Faster drift detection | Small endpoint/UI surface |
| Artifact | Mutable build directories | Clean rebuild required | Reduces partial class/resource mixes | Slower startup |

The preflight adds startup time, not request latency. Memory is neutral. Reliability improves only when the launcher is the actual entrypoint. Rollback is simple: start a verified prior clean build, but never attach the proxy to an unknown existing port owner.

### Option 2: Attested demo bundle and owning supervisor

The structural option moves identity into the artifact. CI produces backend/frontend outputs plus a manifest containing target revision/tree, dependency lock digests, class/resource/schema digests, public configuration schema, and compatibility version. A supervisor acquires the demo port lease, verifies the manifest, starts the exact bundle, checks readiness/identity, and then exposes it to Vite/browser. A mismatched or pre-existing process cannot satisfy readiness.

This approach makes demo state reproducible and diagnosable. The browser can display non-secret build/data-mode identity, and the value-free event contract can attach it to every request. Content-based secret scanning and a positive browser-environment allowlist protect the publication boundary; local hooks remain useful but are no longer treated as the only control.

The main cost is operational ownership. Manifests need versioning; the supervisor must handle crashes, shutdown, port leases, and rollback; signing adds key management if cryptographic attestation is chosen. Bundle creation increases CI time and storage. Runtime memory is essentially unchanged apart from supervisor overhead. The architecture improves failure isolation because an incoherent artifact never becomes ready, but a broken supervisor could block the demo entirely; a simple verified manual fallback must still validate the same manifest.

[Option 2 after view](../diagrams/demo-artifact-integrity-attested-bundle-after.mmd)

| Change | Before | After | Security consequence | Cost |
| --- | --- | --- | --- | --- |
| Artifact identity | Mutable worktree/build dirs | Manifest-bound immutable bundle | Prevents partial/stale source-resource identity | CI/storage/versioning |
| Process authority | Whoever owns the port | Supervisor owns port and verified child | Rejects stale/deleted-worktree JVM | Operational component |
| Secret boundary | Heuristics/local hooks | Content scan plus public-env allowlist | Stronger publication control | Policy/key maintenance |
| Diagnosis | Source assumption and timestamps | Build/mode/request identities | Exact runtime attribution | Metadata plumbing |

## Comparison

| Dimension | Option 1: guards/preflight | Option 2: attested bundle |
| --- | --- | --- |
| Security | Narrows known bypasses; manual paths remain | Binds execution/publication to verified artifact identity |
| Performance | Startup checks only | CI/startup verification; request path neutral |
| Memory | Neutral | Small supervisor/manifest overhead |
| Reliability | Better prescribed launches; bypassable | Coherent readiness/rollback; supervisor dependency |
| Operability | Familiar scripts | Bundle lifecycle, port lease, manifest and incident tooling |
| Migration | Low-to-medium | Release pipeline and demo operating-model change |
| Rollback | Start verified clean build | Activate previous verified compatible bundle |

## Recommendation

I recommend Option 2 for the demo/release lane because the observed outage was an artifact-identity failure that source tests could not detect in a surviving process. Option 1 should still be applied as defense in depth and as a bridge when a full bundle workflow is not yet available.

## Evidence Coverage And Residual Risk

| Evidence | Option 1 | Option 2 | Tactical fix still required? |
| --- | --- | --- | --- |
| `R01-CAN-001/008` — command/no-verify bypass | Addresses parser cases | Mitigates publication even if local guard fails | Yes |
| `R04-CAN-001/002/003`, `R05-CAN-020` — secret guard gaps | Addresses known paths/values/install | Addresses through authoritative CI content gate and allowlist | Yes |
| Track A/B stale runtime | Addresses when launcher used | Addresses through bundle+supervisor identity | Operational cleanup still required |
| `R05-CAN-009` — gitleaks allowlist | Unknown until bypass proof | Mitigates with reviewed content policy | Needs-review proof remains |

Residual risk includes compromised CI/signing authority, malicious reviewed source, dependencies executing during build, public source maps, operator misconfiguration, and a supervisor with excessive host authority.

## Migration And Rollout

The stale process must not be reused during any future rollout. Local guards/preflight can protect the transition, while bundle/supervisor selection and rollback policy require release-owner approval. This portfolio does not prescribe the implementation sequence.

## Validation Plan

- Attempt startup with a process already owning the port, a deleted-worktree classpath, missing fixture, mismatched frontend/backend schema, and changed data mode; readiness must fail before user traffic.
- Reproduce each command/secret bypass against local defense and authoritative CI publication gates.
- Scan built JS/source maps/resources for canary secrets and unapproved public environment variables.
- Kill/restart/rollback the supervisor and verify port ownership, request/build correlation, and zero cross-generation class/resource mixing.
- Measure CI bundle time, startup verification time, artifact size, and supervisor recovery time against approved demo thresholds.

## Implementation Work Packages

Intentionally omitted pending option selection and release/security/operations approval.

## Open Questions

- Is digest verification sufficient for the demo, or is signed attestation required?
- Which component owns the port lease and lifecycle on developer machines and demo hosts?
- Which build/data-mode identity may be shown publicly without exposing sensitive deployment detail?
- What is the authoritative content-based secret policy and emergency rotation path?
