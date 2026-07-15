# Round 06 Central Validation Assembler Preparation

The assembler is a deterministic, scan-artifact-only bridge from the five validator batches to a consolidated validation stream and one marked validation receipt per canonical ledger. Its default mode is read-only. This preparation does not authorize a write.

## Sealed inputs

- Target commit: `654f906e00e81648d1482210b6a9171747dddd75`
- Target tree: `a14388f597c0c2a17e0dbcfc2d951a390c877214`
- Canonical stream: `artifacts/04_reconciliation/deduped_candidates.jsonl`
- Canonical stream SHA-256: `274fd61156b7ae3ebeadb1c905258fbe001a5f81380891b9d2491b44652477b8`
- Canonical rows: 188
- Batch 01: lines 1-38, `validate_chat_controls`
- Batch 02: lines 39-76, `validate_allergy_output`
- Batch 03: lines 77-113, `validate_availability`
- Batch 04: lines 114-151, `merge_r04_semantic`
- Batch 05: lines 152-188, `merge_r05_semantic`

Each batch must provide a nonempty LF-terminated `validation.jsonl` and `report.md` under `artifacts/central_validation_round06/batch-0N/`. The assembler requires exact row counts, exact contiguous canonical-line order, 188 unique IDs, the exact canonical ID at each line, and the SHA-256 of each exact raw canonical JSONL line.

Final frozen batch seals:

| Batch | Validation SHA-256 | Report SHA-256 |
|---|---|---|
| 01 | 7c6b132d11a0c3644c2e169ff5f12183faff22e19d6a5e715dc98c208680d358 | b34688ab1c67bc6bf866f84cdf0bc04a39afa36fadd43416deda310313be4023 |
| 02 | ed5af1803d829d0623097506b172fb0ed3da6e1ea98a4944754620ed628976ed | e0a3c339cbe64fbc4987092ce9883b5570e46709e26e5edf2cfd7cefaad86d8e |
| 03 | 1a9d0428b08304586a2853897998d6d69a43e9a200c8b38e9fda190cb94e7bac | 8196371138844ec7851249d2092ead1d807c37fc032bf6282dc5922aa70d7fe3 |
| 04 | d5392b53f366566817786ebd1807dccc20b0ad3206e390b620958e2f1b7cd805 | 2e76a815bbaaa62d3a5a1f0eccec62afa5e1e0de55a707afd4805c3548c380e0 |
| 05 | 83c67554861df3ed6d506a772eb614dc4d6b5a3dc459c827d71fd4b73b06b28f | 04e95e9625857806d09dd944c9ac6df1faa68e6957492b6f37e4216909d4f313 |

The builder rejects any different batch bytes before planning outputs.

## Validation row contract

Top-level keys must appear in this exact order:

`schema_version,event,candidate_id,canonical_line,canonical_row_sha256,target,disposition,severity,confidence,source,closest_control,sink,impact,preconditions,counterevidence,proof_gaps,evidence,affected_locations,fix_direction,review_tier,rationale,canonical_ledger_path,canonical_ledger_sha256`

- `schema_version`: `deep-security-central-validation-v1`
- `event`: `centralized_validation_receipt`
- `target`: the sealed target commit above
- `disposition`: `reportable`, `not_reportable`, or `needs_review`
- `severity`: `P0`, `P1`, `P2`, `P3`, or `none`
- `confidence`: a finite number from 0 through 1
- `source`, `closest_control`, `sink`, `impact`, `fix_direction`, `review_tier`, and `rationale`: nonempty strings
- `preconditions`, `counterevidence`, and `proof_gaps`: arrays containing only nonempty strings
- `evidence`: an array of exact ordered `{path,lines,claim}` objects with nonempty string fields
- `affected_locations`: an array of objects with nonempty `path` and `lines`; optional `label` and `detail` values must be strings
- `canonical_ledger_path`: exactly `artifacts/05_findings/<candidate_id>/candidate_ledger.jsonl`
- `canonical_ledger_sha256`: the ledger's pre-append SHA-256 captured by the validator

## Receipt and phase boundary

The planned ledger event is `canonical_validation_receipt`, marked by `validation_run_id: central-validation-round-06`. Existing history, prior validation/attack-path rows, and Round 06 discovery/provenance receipts remain byte-for-byte before this suffix. Re-running after a successful write accepts only one identical marked final row and plans no further append.

The receipt embeds the validator record without reinterpreting its disposition, severity, confidence, evidence, rationale, or review tier. It records `centralized_validation_performed: true` and `centralized_attack_path_analysis_performed: false`. The assembler never creates a `canonical_attack_path_receipt`; attack-path analysis remains a later phase.

## Invocation

From the scan root:

```bash
node artifacts/central_validation_round06/build_central_validation.mjs --check-only
```

The check-only result prints `planned_manifest_sha256` and the complete list of files that would change, but writes nothing. A write is possible only through both explicit gates:

```bash
node artifacts/central_validation_round06/build_central_validation.mjs \
  --write \
  --manifest-sha256=<exact planned_manifest_sha256 from the accepted check-only run>
```

No write should run until the central check-only plan has been reviewed and separately authorized.

## Planned outputs

- `artifacts/central_validation_round06/validation.jsonl`: byte-preserving concatenation of the five validator streams
- `artifacts/central_validation_round06/validation_report.md`: mechanical batch seals, counts, and all worker judgments
- `artifacts/central_validation_round06/validation_summary.json`: deterministic counts and the validation/attack-path phase boundary
- `artifacts/central_validation_round06/validation_manifest.json`: builder, canonical, batch, consolidated-output, repository-state, and 188 ledger-plan seals
- One marked `canonical_validation_receipt` suffix in each corresponding canonical ledger

The builder stages changed files to same-directory temporary files, renames only after all staging succeeds, verifies every planned hash, and restores snapshots on an observed failure. It re-verifies sealed inputs, the canonical stream, target object/tree, tracked/staged cleanliness, and exact repository status before and after any authorized write.
