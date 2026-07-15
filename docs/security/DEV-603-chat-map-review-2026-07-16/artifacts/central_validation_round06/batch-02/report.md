# Central validation round 06 — batch 02

## Scope and integrity

- Immutable target: `654f906e00e81648d1482210b6a9171747dddd75`
- Canonical input: `artifacts/04_reconciliation/deduped_candidates.jsonl`
- Canonical input SHA-256: `274fd61156b7ae3ebeadb1c905258fbe001a5f81380891b9d2491b44652477b8`
- Physical lines: 39–76 inclusive
- Expected and emitted receipts: 38
- Output JSONL SHA-256: `ed5af1803d829d0623097506b172fb0ed3da6e1ea98a4944754620ed628976ed`
- Repository writes: none; the immutable target and canonical/per-candidate ledgers were not edited.

Every receipt retains its canonical ID, raw-row SHA-256, target, per-candidate ledger identity, and verbatim `affected_locations`. Cross-audit corrections changed only validation judgments and supporting validation evidence.

## Outcome

| Disposition | Count |
|---|---:|
| reportable | 8 |
| needs_review | 2 |
| not_reportable | 28 |

| Severity | Count |
|---|---:|
| P0 | 3 |
| P1 | 3 |
| P2 | 4 |
| none | 28 |

### P0 reportable

- `R01-CAN-046`: public drug search drops allergy exclusions that cannot be normalized, so the server can author `no_match_found` from an incomplete declared-allergy set. Immutable target source proves the fail-open transition.
- `R01-CAN-069`: accepted user-authored product substrings and model-derived consultation terms are persisted at INFO. This directly violates the §2-5 no-transcript-persistence boundary.

### Conditional P0 needing review

- `R01-CAN-039`: target documents conflict. `AGENTS.md` and `synonyms.tsv` intentionally keep unsigned rows blocking under AR-02, while spec 005 says only signed bindings may block. Human and clinical owners must resolve the authoritative policy before this can be classified as a §2-6 bypass.

### P1 reportable

- `R01-CAN-059`: possession of `deviceId` authorizes profile operations, and the failure path concatenates that bearer-like value into an `ApiException` message that `GlobalExceptionHandler` logs.
- `R01-CAN-072`: pinned Reactor Netty bounds connection and pool acquisition, but the target sets no explicit response/read timeout or whole-operation deadline. An accepted response that stalls or never completes can still deny care-information surfaces.

### Conditional P1 needing review

- `R01-CAN-058`: the externally loaded Naver SDK executes top-level in the same application origin as chat and sensitive client state. Target source proves ambient capability; exploitation still depends on vendor/CDN/TLS compromise or a human architectural decision.

### P2 reportable

- `R01-CAN-061`: supported live-mode pharmacy and hospital failures embed precise care-search coordinates in exceptions logged by the global handler.
- `R01-CAN-064`: bounded profile validation values, including attacker-controlled control characters, are written to operational logs.
- `R01-CAN-067`: unconstrained `itemSeq` can reach a verbatim not-found log sink; pinned Tomcat/Logback behavior supports CR/LF log forging, but no live no-result HTTP reproduction was preserved.
- `R01-CAN-068`: untrusted provider `resultMsg` reaches hybrid/live log sinks without control-character neutralization; exploitation requires a compromised or hostile upstream.

## Rejected and suppressed candidates

- `R01-CAN-040`–`R01-CAN-045`: the target sends full turn history, propagates structured-list incompleteness, removed model allergen extraction, and fails closed on malformed, truncated, or partially unresolved exclusions.
- `R01-CAN-047`: detail-route exclusion dropping is the same root cause as `R01-CAN-046`; its affected location remains carried by the retained finding.
- `R01-CAN-048`–`R01-CAN-055`: shipped grounding makes allergy verdicts, English display names, warnings, and prescription status server-owned; unknown cards fail closed, and the visible disclaimer is code-authored.
- `R01-CAN-056` and `R01-CAN-057`: same-origin storage writers add no cross-user or privilege-boundary reach beyond equivalent same-origin UI authority. The exact canonical authority for `R01-CAN-056` is quarantined; conditional third-party capability remains represented by `R01-CAN-058`.
- `R01-CAN-060`: canonical affected locations are exclusively quarantined. An analogous outer-target path does not validate this exact row under the batch authority rule.
- `R01-CAN-062` and `R01-CAN-063`: service-key logging is not present in the pinned runtime; target code does not log the request URI or query string.
- `R01-CAN-065` and `R01-CAN-066`: all canonical affected paths belong to an absent quarantined worktree, so these exact rows remain rejected as nested-worktree contamination.
- `R01-CAN-070` and `R01-CAN-071`: pharmacy and hospital coordinate-log callsites are children of the retained composite `R01-CAN-061`.
- `R01-CAN-073`–`R01-CAN-076`: individual blocking callers are children of the retained shared timeout root cause `R01-CAN-072`.

## Evidence and validation

- `R01-CAN-046`, `R01-CAN-058`, and `R01-CAN-059` now rely on immutable target source only; mutable `/private/tmp` probes and reconciliation self-citations were removed.
- `R01-CAN-064` likewise no longer relies on a mutable temporary log.
- `R01-CAN-072` now states the precise timeout gap: response/read and whole-operation lifetime after connection acceptance, not an absolute absence of every runtime timeout.
- Structural verification covers exact top-level and evidence-object key order, enum/type constraints, 38 unique IDs, canonical lines 39–76, raw canonical-row hashes, per-candidate ledger hashes, and byte-equivalent `affected_locations`.
- No canonical ledger was appended or edited, and no repository source file was changed.
