# Hardening analysis context

## Analysis identity

- Analysis ID: `hardening_final`
- Immutable source target: `654f906e00e81648d1482210b6a9171747dddd75`
- Immutable source tree: `a14388f597c0c2a17e0dbcfc2d951a390c877214`
- Canonical candidate stream: 188 rows, SHA-256 `274fd61156b7ae3ebeadb1c905258fbe001a5f81380891b9d2491b44652477b8`
- Final batch result: 101 reportable, 22 needs review, 65 not reportable; reportable P0/P1 are 33/44.
- Archive evidence-collection digest: SHA-256 `40de2e5578fecfd180c3e2740afd5a6b81191b118e4b02a08f16c0a101be2753`, computed over `SHA-256␠␠scan-relative-path\n` for the ordered 11-file inventory below. The archive omits the superseded prefinal diagnostic and check-only verification; final Track A/B/C audits and the current validation batches supersede them. EV-A is privacy-redacted under AGENTS.md §2-5.
- Analysis mode: derived, revisable, pre-seal hardening review. It does not modify canonical evidence and does not claim remediation.

The live workspace was at `d6a143a9b3fea571e84f0af12a03d3b5af3b6ee1` while this portfolio was prepared, not at the immutable target. It also had concurrent tracked facility/config/test edits. Source-backed claims therefore come from the immutable target and the final batch receipts, not from the moving worktree. Central adoption is pending until the unrelated diff is cleared and the corrected batches receive a fresh check-only assembly.

## Evidence inventory

| Evidence | Reader-facing title | Scan-relative path | SHA-256 | Use |
| --- | --- | --- | --- | --- |
| `EV-CANON` | Round-06 canonical candidate stream | `artifacts/04_reconciliation/deduped_candidates.jsonl` | `274fd61156b7ae3ebeadb1c905258fbe001a5f81380891b9d2491b44652477b8` | Human titles, affected locations, canonical identity |
| `EV-TM` | Repository threat model | `artifacts/01_context/threat_model.md` | `2fbf3600fa6bd9ef2b646d787d22174349ba67f4db99b62574ce81a5456c7450` | Assets, actors, boundaries, non-negotiable invariants |
| `EV-A` | Track A final chat-failure audit | `artifacts/finalization/track_a_final_audit.md` | `7f3ecafe64b20207759703934c9561f76e6aef21212a3fb0b68cc1d7295067ed` | Coercion failure, stale-runtime stacks, missing value-free correlation |
| `EV-B` | Track B final map/facility audit | `artifacts/finalization/track_b_final_audit.md` | `760d124148e3bab8a4fccf8edb9af596627bc35a3e2d01e4cb8b4141ab88893e` | Accepted Naver map, stale JVM facility 500s, truthful/false-empty UI states |
| `EV-C` | Track C final adversarial audit | `artifacts/finalization/track_c_final_audit.md` | `721073151e36429c0a2aaf9d987da4faa2924ca6b271b430e07532cb58f0fb50` | Role/context, triage/allergy, logs, secrets, injection boundaries |
| `EV-NAVER` | Exact-origin Naver console/network capture | `diagnostic_drafts/evidence/naver_5173_console_network_20260716.md` | `cbab96008330ef853f46405324bc6115cc90968f7a3e72b05dd5aac87c7d468c` | `:5173` map acceptance and facility response evidence |
| `EV-B1` | Final validation batch 01, lines 1–38 | `artifacts/central_validation_round06/batch-01/validation.jsonl` | `7c6b132d11a0c3644c2e169ff5f12183faff22e19d6a5e715dc98c208680d358` | 33 reportable, 0 needs review, 5 not reportable |
| `EV-B2` | Final validation batch 02, lines 39–76 | `artifacts/central_validation_round06/batch-02/validation.jsonl` | `ed5af1803d829d0623097506b172fb0ed3da6e1ea98a4944754620ed628976ed` | 8 reportable, 2 needs review, 28 not reportable |
| `EV-B3` | Final validation batch 03, lines 77–113 | `artifacts/central_validation_round06/batch-03/validation.jsonl` | `1a9d0428b08304586a2853897998d6d69a43e9a200c8b38e9fda190cb94e7bac` | 22 reportable, 2 needs review, 13 not reportable |
| `EV-B4` | Final validation batch 04, lines 114–151 | `artifacts/central_validation_round06/batch-04/validation.jsonl` | `d5392b53f366566817786ebd1807dccc20b0ad3206e390b620958e2f1b7cd805` | 22 reportable, 1 needs review, 15 not reportable |
| `EV-B5` | Final validation batch 05, lines 152–188 | `artifacts/central_validation_round06/batch-05/validation.jsonl` | `83c67554861df3ed6d506a772eb614dc4d6b5a3dc459c827d71fd4b73b06b28f` | 16 reportable, 17 needs review, 4 not reportable |

The batch Markdown reports were also inspected. Their current hashes are: batch 01 `b34688ab1c67bc6bf866f84cdf0bc04a39afa36fadd43416deda310313be4023`; batch 02 `e0a3c339cbe64fbc4987092ce9883b5570e46709e26e5edf2cfd7cefaad86d8e`; batch 03 `8196371138844ec7851249d2092ead1d807c37fc032bf6282dc5922aa70d7fe3`; batch 04 `2e76a815bbaaa62d3a5a1f0eccec62afa5e1e0de55a707afd4805c3548c380e0`; batch 05 `04e95e9625857806d09dd944c9ac6df1faa68e6957492b6f37e4216909d4f313`.

## Epistemic labels

- **Observed** means the final receipt, retained trace, or immutable source directly establishes the stated behavior.
- **Inferred** means several observed facts support a structural diagnosis, but the proposed component does not yet exist and the inference is not remediation proof.
- **Proposed** means a design option. It must not be described as implemented, measured, or finding-closing.

## Opportunity clusters

The portfolio intentionally does not repeat all 101 reportable rows. It uses the following high-signal evidence to expose repeated control ownership. Every identifier is paired with its human title here and again where used in a proposal.

| Control owner | Representative final evidence | Needs-review evidence retained as a gate |
| --- | --- | --- |
| Conversation role/context and semantic release | `R01-CAN-018` — Answer-level model prose lacks semantic medical grounding; `R01-CAN-019` — Drug-card translations are not bound to retrieved facts; `R01-CAN-021` — Government narrative is elevated into a privileged system message; `R01-CAN-027` — Client-authored assistant history bypasses safety screening; `R05-CAN-002` — Raw allergen labels enter server-stamped safety copy | `R05-CAN-004` — Display-name-keyed grounding can collapse distinct products; `R05-CAN-008` — Prompt-injected selection may acquire official provenance |
| Deterministic emergency/allergy state | `R01-CAN-020` — Emergency answers can retain medication cards; `R01-CAN-028` — Reaction-only allergy declarations bypass fail-closed handling; `R01-CAN-029` — Emergency categories bypass deterministic triage; `R01-CAN-034` — Model answerId can invoke server-only allergy workflow; `R01-CAN-046` — Drug search silently drops unnormalizable exclusions; `R02-CAN-003` — Null urgency suppresses fail-upward emergency handling | `R05-CAN-014` — Emergency copy may conflict with rescue medication; `R06-CAN-001` — Negated/non-current red flags can lock a conversation; `R06-CAN-002` — Unreviewed form qualifiers gain exact blocking identity |
| Identity, provenance, cache, and data mode | `R01-CAN-098` — Cached pharmacy facts are restamped as fresh; `R01-CAN-105` — Hybrid fallback is stamped live/current; `R01-CAN-109` — Fixed fixture product is bound to an arbitrary ID; `R01-CAN-113` — DUR fallback warnings are not bound to the requested medicine; `R05-CAN-023` — Cached drug facts are restamped as fresh | `R05-CAN-004` — Display-name-keyed grounding identity collision |
| Value-free observability and correlation | `R01-CAN-059` — Profile bearer deviceId is copied into logs; `R01-CAN-061` — Facility failures log coordinates; `R01-CAN-069` — Accepted health-related search terms are logged; `R06-CAN-003` — Malformed chat JSON reaches catch-all logging with health text; Track A/B — request IDs are returned but absent from runtime log lines | `R05-CAN-011` — Pass-2 exceptions may persist consultation-derived content |
| Facility availability and failure truthfulness | `R01-CAN-037` — Pending facility work renders as completed no-results; `R01-CAN-094`/`095` — Malformed hours collapse to closed; `R03-CAN-001` — One detail failure discards all nearby hospitals; `R04-CAN-011` — Unknown-hours facilities are discarded; `R04-CAN-013` — Malformed upstream envelopes become empty success | `R04-CAN-014` — Overnight inference semantics remain externally unresolved |
| Build and demo artifact integrity | `R01-CAN-001` — Composite lexical command-policy bypasses; `R01-CAN-008` — No-verify commits can bypass secret hooks; `R04-CAN-001` — Nested `.env` paths bypass the pre-commit guard; `R04-CAN-002` — Placeholder suppression can hide real credentials; `R04-CAN-003` — Hook installation fails open; Track A/B — stale classes from a deleted worktree served the demo | `R05-CAN-009` — Global gitleaks allowlist may mask credential-shaped values |

## Constraints and human gates

- No source modification, tactical fix, implementation plan, or claim of closure is authorized by this portfolio.
- §2 behavior remains non-negotiable. Structural work cannot weaken fail-closed allergy behavior, deterministic emergency ordering, unknown-hours truthfulness, transcript lifetime, human reviewer attestation, secret boundaries, or server-owned provenance.
- Clinical review is mandatory before changing emergency categories, rescue-medication copy, allergy equivalence/normalization, dose/diagnosis semantics, or reassurance language.
- Product/UX and accessibility review is mandatory for safety-state copy, partial/empty/unavailable facility presentation, and browser actions.
- Privacy review is mandatory for log fields, retention, coordinate handling, profile capabilities, and third-party processing.
- Operations review is mandatory for quota budgets, timeouts, cache namespaces, data-mode behavior, telemetry retention, and demo process ownership.
- Release/security-owner review is mandatory for CI/hook authority, public environment allowlists, artifact manifests, and secret-scanner policy.
- The 22 `needs_review` rows remain unresolved. A structural proposal can reduce recurrence risk but cannot promote or dismiss them without their named external, clinical, provider, deployment, or runtime proof.

## Adoption status

The old central check-only result is expressly superseded. The corrected final batches produce the totals recorded above, but the moving shared repository currently has unrelated tracked changes in facility/config/test files. Therefore central adoption, manifest sealing, and any implementation selection remain pending. The portfolio itself is derived output under `hardening/` and is safe to review before that gate clears.
