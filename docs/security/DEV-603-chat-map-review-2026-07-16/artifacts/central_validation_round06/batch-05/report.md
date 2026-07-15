# Central validation batch 05

## Outcome

All 37 canonical candidates on physical lines 152–188 were validated exactly once against immutable target objects at `654f906e00e81648d1482210b6a9171747dddd75` (tree `a14388f597c0c2a17e0dbcfc2d951a390c877214`). After independent semantic cross-audit corrections, the batch closes with **16 reportable**, **17 needs_review**, and **4 not_reportable** receipts.

`needs_review` preserves a material clinical, provider, SDK, or deployment fact that target objects cannot settle. Its severity is the conditional tier if that missing fact is established; it is not a final reportable finding yet.

## Frozen inputs

- Canonical ledger: `artifacts/04_reconciliation/deduped_candidates.jsonl`
- Canonical rows: 188
- Canonical SHA-256: `274fd61156b7ae3ebeadb1c905258fbe001a5f81380891b9d2491b44652477b8`
- Batch range: physical lines 152–188 inclusive
- Batch count: 37
- Target commit: `654f906e00e81648d1482210b6a9171747dddd75`
- Target tree: `a14388f597c0c2a17e0dbcfc2d951a390c877214`
- Validation method: immutable `git show` / `git grep` source tracing, target configuration and test countercontrols, and bounded adjacency checks. `R06-CAN-003` additionally has clean-server HTTP reproduction at execution revision `f68cc39948bdb11139af07017336e45ef07e2325`; the complete `backend/` tree has an empty diff from the immutable target.
- Independent semantic cross-audit: `artifacts/central_validation_round06/cross_audits/batch-05.md`

## Five-point rubric

- [x] Attacker-controlled or external **source** is concrete and reachable under stated preconditions.
- [x] The **closest control** was inspected on the exact target path, including safe siblings and target tests.
- [x] The claimed **sink** is present and connected to the source without replacing an instance with a neighboring finding.
- [x] **Impact and preconditions** cross a supported security or care-information boundary; clinical/provider/deployment uncertainty is preserved.
- [x] **Counterevidence and tier** follow AGENTS: a validated §2 bypass is P0; P1 requires direct care, safety, accessibility, availability, privacy, or secret-boundary impact; privileged local developer-integrity gaps without that path are P2.

## Counts

| Dimension | Value | Count |
|---|---|---:|
| Disposition | reportable | 16 |
| Disposition | needs_review | 17 |
| Disposition | not_reportable | 4 |
| Severity | P0 | 12 |
| Severity | P1 | 11 |
| Severity | P2 | 8 |
| Severity | P3 | 2 |
| Severity | none | 4 |

Severity counts include conditional tiers on `needs_review` rows.

## Closure table

| Line | Candidate | Title | Disposition | Severity | Confidence | Decisive rationale or proof gap |
|---:|---|---|---|---|---:|---|
| 152 | `R04-CAN-005` | Positive itemSeq detail caches have attacker-expandable cardinality | reportable | P1 | 0.88 | The public itemSeq source reaches two positive Redis cache keys with only a six-hour TTL. HYBRID fallback broadens the positive keyspace, so repeated unique misses can deny care-data availability; external deployment controls only bound confidence. |
| 153 | `R04-CAN-006` | MariaDB development image is selected by a mutable tag | reportable | P3 | 0.86 | The mutable image reference and privileged database-volume execution are both present. The supply-chain risk is real but development-scoped and requires upstream tag substitution, so it is a low-tier hardening finding. |
| 154 | `R04-CAN-007` | Redis development image is selected by a mutable tag | reportable | P3 | 0.86 | The mutable Redis image executes with persistent cache data and a host-published port. The complete tuple survives, but the development-only boundary and registry-compromise precondition keep it P3. |
| 155 | `R04-CAN-008` | Manual precise location persists without a retention choice | reportable | P2 | 0.88 | The exact coordinate and label deterministically enter localStorage and outlive the tab without a retention disclosure at the decision point. User initiation and clearability reduce but do not remove the privacy exposure. |
| 156 | `R04-CAN-009` | Exact device coordinates cross into the Naver SDK without recipient disclosure | needs_review | P2 | 0.64 | Product and privacy review must decide the required recipient disclosure and verify the configured SDK and provider's coordinate handling. |
| 157 | `R04-CAN-010` | Provider phone text reaches two tel URI sinks without canonical validation | needs_review | P1 | 0.62 | No target-object evidence establishes an affected platform's tel parsing semantics or a provider row containing exploitable control syntax. |
| 158 | `R04-CAN-011` | Chat open-now flow discards unknown-hours facilities | reportable | P0 | 0.96 | FacilityService's Boolean.TRUE filter removes unknown-hours facilities before NearbyFacilities can render them. That routes around AGENTS §2-3 and can hide a potentially open care option, so it is a validated P0. |
| 159 | `R04-CAN-012` | Hospital adapter accepts finite coordinates outside WGS84 ranges | reportable | P1 | 0.86 | The adapter checks finiteness but not geographic range, and accepted values reach both the API response and Naver LatLng. A malformed provider row can misplace or hide care information, meeting the repository's P1 care-impact lens. |
| 160 | `R04-CAN-013` | Malformed successful public-API envelopes become empty success | reportable | P1 | 0.94 | The shared parser turns malformed successful envelopes into empty lists consumed as successful absence by facility and drug adapters. That can hide care or verified medicine data, so the exact fail-open path is P1. |
| 161 | `R04-CAN-014` | Fallback overnight inference reuses today as yesterday | needs_review | P1 | 0.66 | An authoritative provider contract or captured live row must establish the undated fallback interval's day identity. |
| 162 | `R05-CAN-001` | Address geocoding query strings cross retention boundaries | needs_review | P2 | 0.60 | The target contains no reverse-proxy or provider retention configuration and no authoritative recipient-disclosure requirement. |
| 163 | `R05-CAN-002` | Raw allergen labels enter server-stamped safety copy | reportable | P0 | 0.97 | Raw request prose is deterministically stamped into the server safety panel and a privileged system message. A label can include reassurance such as safe while still matching after parenthesis stripping, directly bypassing the AGENTS §2-2 server-authored allergy boundary; this is P0. |
| 164 | `R05-CAN-003` | Source-qualified checkout overwrites tracked work by policy design | reportable | P2 | 0.98 | Source-qualified checkout deterministically overwrites tracked work, but the demonstrated effect is privileged local developer-work loss. No deployed care or secret-disclosure path is established; the equivalent checkout sink in `R01-CAN-003` is P2. |
| 165 | `R05-CAN-004` | Display-name-keyed grounding collapses distinct government products | needs_review | P0 | 0.64 | A real or synthetic same-name collision must demonstrate that mixed semantics survive validation and server stamping. |
| 166 | `R05-CAN-005` | Extraction omits the provider no-storage flag | needs_review | P0 | 0.55 | The configured provider's default storage and retention contract is not present in target objects. |
| 167 | `R05-CAN-006` | Extraction lacks a provider output-token budget | needs_review | P1 | 0.55 | Oversized input and aggregate admission are already retained findings. A bounded fake-provider harness must establish that the missing pass-1 output ceiling independently permits materially excessive output or P1 resource consumption for one admitted request. |
| 168 | `R05-CAN-007` | Extraction ignores structured-output compatibility policy | not_reportable | none | 0.96 | The source is trusted deployment configuration and the path fails closed to an empty verified-medicine context. It is a real compatibility defect but not a security-boundary violation, so it is not reportable. |
| 169 | `R05-CAN-008` | Prompt-injected ingredient selection can acquire official provenance | needs_review | P0 | 0.62 | A controlled configured-provider and government-fixture reproduction plus qualified clinical review must establish an inappropriate rendered selection. |
| 170 | `R05-CAN-009` | Global gitleaks value allowlist accepts credential-shaped storage keys | needs_review | P0 | 0.52 | A target-version gitleaks reproduction must show an otherwise-detected credential being suppressed by this value allowlist. |
| 171 | `R05-CAN-010` | Bounded provider JSON expands through unbounded ingredient splitting | needs_review | P2 | 0.58 | A target-JVM benchmark or crafted provider response must establish material CPU or heap amplification. |
| 172 | `R05-CAN-011` | Pass-2 exceptions may persist consultation-derived content in logs | needs_review | P0 | 0.55 | An exact target Spring/provider canary reproduction must show consultation-derived response text in the logged Throwable. |
| 173 | `R05-CAN-012` | Raw government facility names cross the Naver marker-title boundary | needs_review | P1 | 0.38 | A browser probe against the exact deployed Naver SDK must show Marker.title becoming executable HTML. |
| 174 | `R05-CAN-013` | Precise facility coordinates enter infrastructure-retained GET targets | needs_review | P2 | 0.56 | Production reverse-proxy, CDN, access-log, observability, retention, and reader-access configuration are not in target objects. |
| 175 | `R05-CAN-014` | Generic emergency copy may delay prescribed rescue medication | needs_review | P0 | 0.72 | Qualified clinical review must approve reason-specific wording and any rescue-medication exception. |
| 176 | `R05-CAN-015` | Valid urgent-care instructions are not rendered | reportable | P1 | 0.97 | Urgent is a valid preserved backend state, yet both first-party render paths gate the safety copy on emergency only. This deterministically hides care-timing information and meets the repository's P1 safety-information lens. |
| 177 | `R05-CAN-016` | Zero-valued schedule semantics collapse before provider-aware interpretation | not_reportable | none | 0.93 | The candidate's source precondition is not present in target objects and is contradicted by the target's explicit schedule contract and tests. The hypothetical alternate encoding is therefore not reportable. |
| 178 | `R05-CAN-017` | Model-owned non-emergency urgency classification has no fail-closed semantic check | needs_review | P0 | 0.68 | A concrete reviewed emergency phrase and controlled provider response must reproduce an accepted false non-emergency result. |
| 179 | `R05-CAN-018` | Git commit broad-stage modes bypass repository scope controls | reportable | P2 | 0.99 | The broad commit mode is deterministic, but pre-commit and review remain, no no-verify path is claimed, and no direct care or §2 effect is shown. This matches the P2 defense-in-depth class of `R01-CAN-006`. |
| 180 | `R05-CAN-019` | Unlisted destructive Git subcommands bypass the repository policy | not_reportable | none | 0.99 | Remediation-subsumed duplicate: `R01-CAN-005` already contains the same restore/clean tuple and pinned no-denial probes. Absorb the evidence rather than issuing a second finding. |
| 181 | `R05-CAN-020` | Secret-bearing environment-file variants fall outside protected-path classification | reportable | P1 | 0.96 | is_real_env recognizes only the literal basename, so common secret-bearing variants bypass every reader and source guard. This can disclose developer credentials to model-visible output and logs, a validated P1 local secret-boundary failure. |
| 182 | `R05-CAN-021` | Broad polymorphic Redis cache deserialization trusts package families | not_reportable | none | 0.99 | Remediation-subsumed duplicate: `R01-CAN-131` already covers the same Redis-write boundary, serializer-compatible poisoning and denial, missing-gadget limit, and strict cache-typing remediation. |
| 183 | `R05-CAN-022` | DUR rows missing ITEM_SEQ are silently dropped as no warnings | reportable | P1 | 0.92 | The parser silently drops the row and downstream code treats the incomplete list as ordinary, including copy that says no contraindication was published. That can hide safety data and is P1 under the care-information lens. |
| 184 | `R05-CAN-023` | Cached drug facts are restamped as freshly retrieved | reportable | P0 | 0.99 | Cached facts do not carry fetch time, while source() stamps Instant.now on every assembly and the prompt says retrieved just now. This deterministically falsifies server-owned provenance and directly bypasses AGENTS §2-9, so it is P0. |
| 185 | `R05-CAN-024` | Alternate destructive filesystem utilities bypass the rm-only policy | reportable | P2 | 0.98 | Find -delete is a distinct effect-based policy gap, but the demonstrated effect is privileged local deletion with no direct care or §2 path. It matches the P2 local-authority class of `R01-CAN-004`. |
| 186 | `R06-CAN-001` | Negated or non-current red-flag text can lock a conversation into emergency-only responses | needs_review | P1 | 0.78 | Qualified clinical and product reviewers must approve the context truth table and distinguish acceptable conservative escalation from harmful over-triage. |
| 187 | `R06-CAN-002` | Unreviewed in-code form qualifiers collapse into exact blocking identity | needs_review | P0 | 0.62 | A qualified human reviewer must assess every qualifier for both provider-side and user-side contexts and pin reviewed versus unreviewed verdicts. |
| 188 | `R06-CAN-003` | Outer malformed chat JSON reaches the catch-all logger with attacker health text | reportable | P0 | 0.96 | Clean-server HTTP reproduction returned a generic 500 while the correlated full-Throwable server log persisted the synthetic health token in both HttpMessageNotReadableException and JsonParseException. The complete backend is byte-equivalent to the immutable target, directly reproducing the §2-5 bypass and validating P0. |

## Reportable P0 closure

- `R04-CAN-011` (line 158): FacilityService's Boolean.TRUE filter removes unknown-hours facilities before NearbyFacilities can render them. That routes around AGENTS §2-3 and can hide a potentially open care option, so it is a validated P0.
- `R05-CAN-002` (line 163): Raw request prose is deterministically stamped into the server safety panel and a privileged system message. A label can include reassurance such as safe while still matching after parenthesis stripping, directly bypassing the AGENTS §2-2 server-authored allergy boundary; this is P0.
- `R05-CAN-023` (line 184): Cached facts do not carry fetch time, while source() stamps Instant.now on every assembly and the prompt says retrieved just now. This deterministically falsifies server-owned provenance and directly bypasses AGENTS §2-9, so it is P0.
- `R06-CAN-003` (line 188): Clean-server HTTP reproduction returned a generic 500 while the correlated full-Throwable server log persisted the synthetic health token in both HttpMessageNotReadableException and JsonParseException. The complete backend is byte-equivalent to the immutable target, directly reproducing the §2-5 bypass and validating P0.

### R06-CAN-003 dynamic evidence

- Execution revision: `f68cc39948bdb11139af07017336e45ef07e2325`; `git diff --exit-code 654f906e00e81648d1482210b6a9171747dddd75 f68cc39948bdb11139af07017336e45ef07e2325 -- backend` exited 0.
- Response headers: `artifacts/central_validation_round06/evidence/r06-can-003/r06can003-malformed.headers`, SHA-256 `3272169c3d67594d380c2720214ea3d10095c5c14eac2e88a4db5544913b77f1`; HTTP 500 and request ID `fc4b4a52-d125-4c3d-8c09-8df64fe74ae2`.
- Response body: `artifacts/central_validation_round06/evidence/r06-can-003/r06can003-malformed.body`, SHA-256 `d50e3107bf6263d59bafe5ecc4527e04c6cdd9bb4181d06e39ca86f7e6f1a8c0`; generic `INTERNAL_ERROR` body with the same request ID and no reflected health token.
- Server log: `artifacts/central_validation_round06/evidence/r06-can-003/r06can003-server-log.txt`, 67 lines, SHA-256 `0fdc2a3191f982be4877a768d223907e4c4fb58271d70924df0bf3bd0a744f9a`; lines 3 and 64 quote `chest_pain_and_cannot_breathe_R06CAN003` in `HttpMessageNotReadableException` and its `JsonParseException` cause.
- Integrity manifest: `artifacts/central_validation_round06/evidence/r06-can-003/SHA256SUMS`; `sha256sum -c SHA256SUMS` passes for all three sealed artifacts.

## Reportable P1 closure

- `R04-CAN-005` (line 152): Public attacker-selected `itemSeq` values become positive six-hour Redis keys; fixture-only and HYBRID fallback ignore the selected key and return fixed positive fixtures, while Redis has no cardinality or memory budget.
- `R04-CAN-012` (line 159): Finite but out-of-WGS84 provider coordinates reach both the response and Naver map sink and can misplace or hide care information.
- `R04-CAN-013` (line 160): Malformed successful public-API envelopes become ordinary empty success and can hide facility or verified-medicine data.
- `R05-CAN-015` (line 176): Valid urgent-care instructions are preserved by the backend but hidden by both first-party render paths.
- `R05-CAN-020` (line 181): Secret-bearing `.env.*` variants bypass the literal-`.env` protected-path class and can enter model-visible output and logs.
- `R05-CAN-022` (line 183): DUR rows missing `ITEM_SEQ` are silently dropped and downstream copy presents the incomplete list as no published contraindication.

## Needs-review queue

- `R04-CAN-009` (line 156, conditional P2): Product/privacy review and configured-provider behavior must settle the coordinate-recipient disclosure and retention claim.
- `R04-CAN-010` (line 157, conditional P1): Platform `tel:` parsing and a harmful provider value remain unproved.
- `R04-CAN-014` (line 161, conditional P1): Provider semantics must establish whether the undated fallback interval can produce a false-open result.
- `R05-CAN-001` (line 162, conditional P2): Proxy/provider query retention and reader access are absent from target objects.
- `R05-CAN-004` (line 165, conditional P0): A same-name collision must demonstrate mixed semantics surviving validation and server stamping.
- `R05-CAN-005` (line 166, conditional P0): The configured provider's default storage and retention contract is not present in target objects.
- `R05-CAN-006` (line 167, conditional P1): A bounded fake-provider harness must establish that missing pass-1 `max_tokens` independently permits materially excessive output or P1 resource consumption for one admitted request.
- `R05-CAN-008` (line 169, conditional P0): A controlled provider/fixture reproduction and qualified clinical review must establish an inappropriate rendered ingredient selection.
- `R05-CAN-009` (line 170, conditional P0): A target-version gitleaks reproduction must show an otherwise-detected credential suppressed by the value allowlist.
- `R05-CAN-010` (line 171, conditional P2): A target-JVM benchmark or crafted provider response must establish material split/allocation amplification.
- `R05-CAN-011` (line 172, conditional P0): An exact target Spring/provider canary must show consultation-derived content in the logged Throwable.
- `R05-CAN-012` (line 173, conditional P1): A browser probe against the exact Naver SDK must show `Marker.title` becoming executable HTML.
- `R05-CAN-013` (line 174, conditional P2): Production intermediary logging, retention, and reader access are absent from target objects.
- `R05-CAN-014` (line 175, conditional P0): Qualified clinical review must approve reason-specific emergency wording and any rescue-medication exception.
- `R05-CAN-017` (line 178, conditional P0): A reviewed rule miss plus controlled provider result must reproduce an accepted false non-emergency classification.
- `R06-CAN-001` (line 186, conditional P1): Qualified clinical/product review must distinguish acceptable conservative escalation from harmful over-triage.
- `R06-CAN-002` (line 187, conditional P0): Qualified human review must assess every in-code qualifier in provider-side and user-side contexts.

## Suppressed as not reportable

- `R05-CAN-007` (line 168): The source is trusted deployment configuration and the path fails closed to an empty verified-medicine context. It is a real compatibility defect but not a security-boundary violation, so it is not reportable.
- `R05-CAN-016` (line 177): The candidate's source precondition is not present in target objects and is contradicted by the target's explicit schedule contract and tests. The hypothetical alternate encoding is therefore not reportable.
- `R05-CAN-019` (line 180): Remediation-subsumed duplicate of `R01-CAN-005`, which already carries the same restore/clean tuple and pinned no-denial probes.
- `R05-CAN-021` (line 182): Remediation-subsumed duplicate of `R01-CAN-131`; no concrete allowed gadget or independent stronger sink survives.

## Integrity and mutation boundary

- Every canonical line 152–188 appears once in `validation.jsonl`; no candidate outside that range appears.
- Each receipt binds the canonical row hash computed without the JSONL newline and the current scan-relative per-candidate ledger hash.
- `affected_locations` is copied verbatim from the canonical row; `evidence` projects target locations and the sealed R06-CAN-003 scan-root artifacts into the required `path,lines,claim` key order.
- No canonical row, candidate ledger, repository file, or diagnostic draft was edited or appended.
