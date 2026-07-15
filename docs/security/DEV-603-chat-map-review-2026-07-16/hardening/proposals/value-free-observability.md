# Security Hardening Proposal: Value-free correlated telemetry

## Decision

Decide whether diagnosis relies on ad hoc log messages that retain health/location/capability values while omitting request correlation, or on a typed event contract that records stages and outcomes without user values.

## Executive Recommendation

- **Option 1: Redaction plus MDC repair.** Remove known sensitive values, attach `request_id` to every log pattern, and sanitize exception messages.
- **Option 2: Value-free event contract.** Emit structured stage/outcome/count/duration/build/mode events through one API; classify exceptions separately and prohibit body/query/user values by type.

I recommend Option 2 with privacy and operations approval. Option 1 should be treated as immediate containment because the current logs already cross §2-5, while the final audits show that those same logs cannot produce an ID-exact stage trail.

## Evidence

| Evidence | Finding or audit | What it establishes |
| --- | --- | --- |
| `R01-CAN-059` | [Profile bearer deviceId is copied into logs](../../artifacts/central_validation_round06/batch-02/validation.jsonl) | **Observed:** a bearer-like capability reaches log readers. |
| `R01-CAN-061` | [Facility failures log coordinates](../../artifacts/central_validation_round06/batch-02/validation.jsonl) | **Observed:** precise/rounded location values reach failure logging. |
| `R01-CAN-069` | [Health-related search terms are logged](../../artifacts/central_validation_round06/batch-02/validation.jsonl) | **Observed:** accepted consultation-derived terms persist in server logs, a P0 §2-5 violation. |
| `R06-CAN-003` | [Malformed chat JSON reaches catch-all logging with health text](../../artifacts/central_validation_round06/batch-05/validation.jsonl) | **Observed:** malformed input can widen transcript-like retention even though the client error is generic. |
| `R05-CAN-011` | [Pass-2 exception content may persist](../../artifacts/central_validation_round06/batch-05/validation.jsonl) | **Needs review:** full-Throwable sink is present; exact provider/framework content propagation is unresolved. |
| `EV-A` | [Track A final audit](../../artifacts/finalization/track_a_final_audit.md) | **Observed:** original request IDs were unavailable; reproduced stacks required unique-second timestamp correlation because MDC was absent from log lines. |
| `EV-B` | [Track B final audit](../../artifacts/finalization/track_b_final_audit.md) | **Observed:** the same correlation gap prevented direct joining of browser 500 IDs to the stale-class stack. |

**Inferred:** logging has inverted value. It preserves the sensitive values the product must minimize, while omitting the build identity, data mode, stage, and request correlation needed to decide whether a failure came from model coercion, local fixture integrity, stale bytecode, or an upstream service.

## Current Design And Failure Mode

`RequestIdFilter` returns an identifier to clients, but the observed runtime pattern does not put it in server lines. Services log exact terms and request-dependent values; catch-all logging can retain attacker-controlled exception text. Operators therefore correlate by timestamps and process context, which is slow and can overstate causality.

Track A/B make the design cost concrete. The demo failures were attributable only after process inspection and JFR/same-runtime controls revealed missing classes from a deleted worktree. A safe stage event such as `facility.dispatch.failed`, `failure_class=linkage`, `build_id=...`, `data_mode=fixture`, and `request_id=...` would have been more diagnostic than coordinates or symptoms.

## Desired Invariants

- Every request-stage event carries the same opaque `request_id`, build identity, and data mode.
- No event field contains raw chat text, extracted terms, allergy labels, addresses, coordinates, profile/device capabilities, provider bodies, keys, or full request URLs.
- Event schemas permit bounded counts, durations, operation IDs, result classifications, and approved reason codes only.
- Exceptions are classified and sanitized before logging; client envelopes remain generic and contain only the correlation ID.
- Retention/access/export policy is reviewed as part of the schema, not left to string-message convention.
- Operators can reconstruct the pass/stage trail and distinguish local artifact, upstream, model, validator, and client-input failures without reading user values.

## Constraints And Non-Goals

This proposal does not eliminate all debugging data or prescribe a telemetry vendor. It does not claim hashes make low-cardinality health terms anonymous; avoid values rather than hashing them. Stack traces may be retained only behind an approved sanitized diagnostic boundary. Privacy and incident-response owners decide retention and access.

## Before Architecture

[Current observability flow](../diagrams/value-free-observability-before.mmd)

The broken symmetry is visible: the client has a request ID, while value-bearing logs and stacks do not reliably share it.

## Options

### Option 1: Redaction plus MDC repair

The focused option removes known sensitive interpolations, neutralizes control characters, adds request ID to the logging pattern, and sanitizes caught exception messages. It is easy to verify against the observed sinks and preserves the existing logging stack.

Its weakness is denylist drift. New code can interpolate a symptom, coordinate, or bearer into an ordinary string. Full exception objects can reintroduce values from frameworks/providers. MDC also does not define which stage/outcome fields are safe or consistent.

[Option 1 after view](../diagrams/value-free-observability-local-guards-after.mmd)

| Change | Before | After | Security consequence | Cost |
| --- | --- | --- | --- | --- |
| Correlation | Client ID, absent log pattern | Request ID on lines/stacks | Enables direct join | Logging configuration/tests |
| Sensitive fields | Terms/coordinates/device IDs | Known values redacted | Reduces current leakage | Future string sinks can drift |
| Exceptions | Full Throwable paths | Sanitized messages/classification | Narrows malformed/provider leakage | Less ad hoc detail |
| Event shape | Free-form text | Still mostly free-form | Does not prevent new value fields | Low migration cost |

Performance impact should be negligible relative to I/O. Reliability improves for correlation, but blanket exception suppression can make diagnosis harder if classification is too coarse. Rollback is straightforward, though re-enabling unsafe value logs is not an acceptable operational fallback.

### Option 2: Value-free event contract

The structural option provides a small typed event API. Callers select a stage and outcome enum, bounded counts/durations, approved operation/source IDs, and a reason class. Correlation context automatically adds request/build/mode. There is no string slot for user text or arbitrary exception messages. Sanitized exception classification is a separate adapter, and CI tests reject unapproved event fields.

This gives us positive control rather than a growing sensitive-value denylist. It also aligns with the release/state/result proposals: those boundaries naturally emit reason codes without exposing rejected content. The cost is developer ergonomics and schema governance. If the event catalog is too rigid, engineers may bypass it; if it is too broad, it becomes another free-form log API.

Structured events add bounded serialization and telemetry volume. Memory impact depends on buffering/export and is unmeasured; backpressure must never block a care request. The event sink must fail open for availability while remaining value-free, and sampling must not erase P0/P1 control-failure counts. A compatibility bridge can translate safe existing log events during adoption, while direct unsafe strings remain tactical fixes rather than tolerated migration debt.

[Option 2 after view](../diagrams/value-free-observability-event-contract-after.mmd)

| Change | Before | After | Security consequence | Cost |
| --- | --- | --- | --- | --- |
| API | Arbitrary log strings | Typed approved event fields | Prevents value logging by construction | Event catalog and developer migration |
| Correlation | Partial MDC | Automatic request/build/mode context | Exact cross-stage attribution | Context propagation |
| Failure detail | Throwable text | Sanitized failure class/reason | Separates diagnosis from user values | Exception taxonomy maintenance |
| Operations | Timestamp/manual joins | Structured stage trail | Faster, less speculative incident analysis | Storage/query/dashboard adaptation |

## Comparison

| Dimension | Option 1: redaction/MDC | Option 2: event contract |
| --- | --- | --- |
| Security | Removes known leaks; string drift remains | Positive schema prevents arbitrary value fields |
| Performance | Neutral | Bounded event encoding/export; unmeasured |
| Memory | Neutral | Depends on buffer/export configuration |
| Reliability | Better joins, fewer details | Better stage trails; telemetry must never block requests |
| Operability | Familiar text logs | Stronger structured queries and reason counts |
| Migration | Focused changes | Call-site and operational query migration |
| Rollback | Preserve redaction while reverting format | Keep safe bridge; never restore value-bearing logs |

## Recommendation

I recommend Option 2 because privacy and diagnostic quality point in the same direction: log less content and more state. Option 1 is mandatory near-term containment and should remain even if the structural event contract is deferred.

## Evidence Coverage And Residual Risk

| Evidence | Option 1 | Option 2 | Tactical fix still required? |
| --- | --- | --- | --- |
| `R01-CAN-059/061/069` — bearer/location/health logs | Addresses known statements | Addresses through positive event schema | Yes |
| `R06-CAN-003` — malformed body logging | Addresses exception handler | Addresses via sanitized classifier | Yes |
| `R05-CAN-011` — possible pass-2 exception content | Mitigates | Mitigates structurally | Needs-review reproduction remains |
| Track A/B correlation gap | Addresses MDC | Addresses automatic stage context | Yes |

Residual risk includes ingress/access logs outside this repository, telemetry vendor processing, operator misuse, low-cardinality reason inference, and accidental sensitive data in ungoverned third-party library logs.

## Migration And Rollout

Unsafe current log statements require direct removal regardless of option. A future event contract should coexist only with safe legacy logs and must never use sensitive hashes as a transition shortcut. Detailed sequencing is deferred.

## Validation Plan

- Send canary symptom, allergy, address, coordinate, and device-capability values through success and malformed paths; assert none appears in application logs/events.
- For each Track A/B failure class, prove one request ID reconstructs triage/extraction/retrieval/coercion/validation or facility dispatch without timestamp inference.
- Load-test telemetry exporter failure, backpressure, and high event volume; care-request latency/availability must remain within an approved threshold.
- Audit field cardinality, retention, access, and export destinations with privacy/operations owners.
- Mutation-test addition of arbitrary string/value fields to the event schema.

## Implementation Work Packages

Intentionally omitted pending option selection and privacy/operations approval.

## Open Questions

- Which sanitized stack/class information is necessary for incident response?
- What retention and access policy applies to request/build/mode metadata?
- Which event counts must remain unsampled for safety monitoring?
- How will third-party library logs be brought under the same value-free boundary?
