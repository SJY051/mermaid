# Central validation batch 04

## Outcome

Validated canonical physical lines **114–151 inclusive** exactly once: **38 receipts**, **38 unique candidate IDs**, and no out-of-range candidates.

- Target commit: `654f906e00e81648d1482210b6a9171747dddd75`
- Target tree: `a14388f597c0c2a17e0dbcfc2d951a390c877214`
- Canonical input: `artifacts/04_reconciliation/deduped_candidates.jsonl`
- Canonical input rows: `188`
- Canonical input SHA-256: `274fd61156b7ae3ebeadb1c905258fbe001a5f81380891b9d2491b44652477b8`
- Validation output SHA-256: `d5392b53f366566817786ebd1807dccc20b0ad3206e390b620958e2f1b7cd805`

Disposition totals:

| Disposition | Count |
|---|---:|
| `reportable` | 22 |
| `not_reportable` | 15 |
| `needs_review` | 1 |

Severity among reportable receipts: **7 P0**, **12 P1**, **2 P2**, and **1 P3**. The single deferred receipt is provisional **P2** for the exclusion-set resource hypothesis.

## Validation method

Each receipt was checked against the immutable target and exact canonical physical row. Validation covered:

1. exact canonical line, candidate ID, raw-row SHA-256, target SHA, and current candidate-ledger SHA-256;
2. exact required top-level key order and enumerated disposition/severity values;
3. nonempty source, closest control, sink, impact, fix direction, review tier, rationale, preconditions, and counterevidence;
4. evidence-object key order `path,lines,claim`, immutable-target path existence, and line-range bounds;
5. byte-equivalent `affected_locations` copied from the canonical row, including canonical absent nested-worktree locations;
6. unique coverage of every physical line from 114 through 151.

The Batch 04 structural validator and all-batch assembler check both returned `errors: []`. No canonical row, candidate ledger, repository file, or target object was modified.

## Material decisions

- The hybrid fixture identity/provenance rows (`R01-CAN-114`, `118`–`120`) are remediation-equivalent duplicates of retained `R01-CAN-105`, `109`, `110`, and `113`; they add no independent source/control/sink/impact tuple.
- Consent migration (`R01-CAN-127`) and both concurrent consent cases (`125`, `126`) are reportable P0. For `125`, either a post-opt-out child remains under false consent or stale consent is restored. For `126`, the target's pinned non-dynamic Hibernate update path closes the stale-column proof gap.
- Modal safety-control occlusion (`R02-CAN-002`), null emergency state (`R02-CAN-003`), and secret-hook bypasses (`R04-CAN-001`, `002`) are P0 under the repository's safety invariants.
- Model-authored outer disclaimer (`R04-CAN-004`) is not independently reportable: Batch 02 `R01-CAN-054` already validated the same deployed field path, and the target UI always renders its separate code-authored `DisclaimerStrip`.
- Development MariaDB/Redis exposure is retained as P1 with host firewall, Docker Desktop, and deployment reachability explicitly unresolved.
- Five nested-worktree-only candidates are not applicable to this immutable target; remediation-equivalent composite/subset rows are suppressed as duplicates; local storage corruption and possession of the intended device-ID bearer do not establish independent attacker boundaries.
- The unbounded `exclude_ingredients` hypothesis remains deferred because transport limits exist and no worst-case benchmark establishes material resource exhaustion.

## Per-candidate result

| Line | Candidate | Disposition | Severity | Confidence | Rationale |
|---:|---|---|---|---:|---|
| 114 | `R01-CAN-114` | `not_reportable` | `none` | 0.99 | This is a duplicate composite with no independent weakness or remediation. |
| 115 | `R01-CAN-115` | `not_reportable` | `none` | 1.00 | All claimed affected locations are absent from the immutable target. |
| 116 | `R01-CAN-116` | `not_reportable` | `none` | 1.00 | All claimed affected locations are absent from the immutable target. |
| 117 | `R01-CAN-117` | `not_reportable` | `none` | 1.00 | All claimed affected locations are absent from the immutable target. |
| 118 | `R01-CAN-118` | `not_reportable` | `none` | 0.99 | This permission-list provenance row is fully subsumed by retained R01-CAN-105. |
| 119 | `R01-CAN-119` | `not_reportable` | `none` | 0.99 | This row is exactly the union of the retained permission-detail binding and fallback-provenance findings. |
| 120 | `R01-CAN-120` | `not_reportable` | `none` | 0.99 | This row is exactly the union of the retained DUR binding and fallback-provenance findings. |
| 121 | `R01-CAN-121` | `reportable` | `P1` | 0.93 | The public write-on-read primitive permits unbounded durable allocation; deployment controls bound practical exploitation. |
| 122 | `R01-CAN-122` | `reportable` | `P1` | 0.91 | Per-request validation does not prevent durable distinct-child growth or response amplification. |
| 123 | `R01-CAN-123` | `reportable` | `P1` | 0.91 | Distinct favorite growth and full-list responses remain unbounded despite per-field controls. |
| 124 | `R01-CAN-124` | `not_reportable` | `none` | 0.99 | This is a duplicate composite with no independent weakness. |
| 125 | `R01-CAN-125` | `reportable` | `P0` | 0.96 | A deterministic two-transaction schedule leaves a post-withdrawal allergy row or restores stale consent; either outcome violates §2-5, so runtime reproduction is a regression test rather than missing proof. |
| 126 | `R01-CAN-126` | `reportable` | `P0` | 0.96 | Spring Boot 3.5.16 pins Hibernate 6.6.53.Final; for this non-versioned entity without @DynamicUpdate, the normal update path binds all updateable properties, so the later country update deterministically writes stale consent=true after opt-out. |
| 127 | `R01-CAN-127` | `reportable` | `P0` | 0.94 | For any upgraded database with legacy rows, retention/disclosure under false consent is deterministic. |
| 128 | `R01-CAN-128` | `not_reportable` | `none` | 1.00 | All claimed affected locations are absent. |
| 129 | `R01-CAN-129` | `reportable` | `P3` | 0.78 | Deterministic local secret exposure survives, but the narrow same-host/manual preconditions keep severity low. |
| 130 | `R01-CAN-130` | `reportable` | `P1` | 0.91 | The weak listener is concrete if reachable; deployment reachability remains explicit uncertainty. |
| 131 | `R01-CAN-131` | `reportable` | `P1` | 0.93 | Unauthenticated control is direct if reachable; deployment and typed-payload uncertainty are preserved. |
| 132 | `R01-CAN-132` | `not_reportable` | `none` | 0.99 | The umbrella adds no independent source-control-sink tuple. |
| 133 | `R01-CAN-133` | `not_reportable` | `none` | 1.00 | All claimed affected locations are absent. |
| 134 | `R01-CAN-134` | `not_reportable` | `none` | 0.96 | Writing same-origin tab storage requires capability already sufficient for equivalent disruption. |
| 135 | `R01-CAN-135` | `not_reportable` | `none` | 0.95 | Same-origin storage poisoning adds no new security capability. |
| 136 | `R02-CAN-001` | `reportable` | `P1` | 0.88 | Untrusted provider output deterministically erases care information before the intended validator. |
| 137 | `R02-CAN-002` | `reportable` | `P0` | 0.97 | The UI routes around the always-visible disclaimer/emergency invariant. |
| 138 | `R02-CAN-003` | `reportable` | `P0` | 0.95 | A malformed model field can suppress emergency safety state outside finite pre-triage coverage. |
| 139 | `R02-CAN-004` | `reportable` | `P1` | 0.87 | Missing external-boundary validation can repeatedly remove safety-relevant care information. |
| 140 | `R02-CAN-005` | `not_reportable` | `none` | 0.93 | No independent disclosure, prediction, or authorization-bypass mechanism is shown. |
| 141 | `R02-CAN-006` | `reportable` | `P2` | 0.82 | Amplification is static but bounded to two calls; practical cost depends on provider behavior. |
| 142 | `R02-CAN-007` | `reportable` | `P1` | 0.98 | A documented cap can hide open care facilities during an ordinary request. |
| 143 | `R03-CAN-001` | `reportable` | `P1` | 0.94 | One optional upstream failure can suppress the entire nearby-care result. |
| 144 | `R03-CAN-002` | `reportable` | `P1` | 0.84 | Unchecked provider geometry can corrupt a safety-relevant location flow; fixed-upstream dependence limits confidence. |
| 145 | `R03-CAN-003` | `reportable` | `P2` | 0.97 | The residual defect is operation-level source-title and retrieval-granularity loss; successful live components share MFDS and ITEM_SEQ, while wrong binding and fixture origin are separate P0 findings, so this does not meet the repository's narrow P1 care-delivery threshold. |
| 146 | `R03-CAN-004` | `needs_review` | `P2` | 0.56 | Unbounded code shape is real, but transport caps and unmeasured amplification prevent confident reporting. |
| 147 | `R03-CAN-005` | `reportable` | `P1` | 0.84 | The parser fails open on a care schedule field, with provider-format uncertainty retained. |
| 148 | `R04-CAN-001` | `reportable` | `P0` | 0.98 | Deterministic filename bypass can route around the never-commit-.env invariant. |
| 149 | `R04-CAN-002` | `reportable` | `P0` | 0.99 | A simple deterministic line composition bypasses the secret guard. |
| 150 | `R04-CAN-003` | `reportable` | `P1` | 0.88 | The control is disabled only after a local config failure and missed warning, below direct P0 bypasses. |
| 151 | `R04-CAN-004` | `not_reportable` | `none` | 0.99 | This is remediation-equivalent to R01-CAN-054 and adds only a speculative non-target consumer, not a reachable target sink. |

## Required follow-up for deferred rows

- `R03-CAN-004`: measure maximum accepted exclusion cardinality and concurrent worst-case normalization/comparison cost before promoting or rejecting the availability claim.
