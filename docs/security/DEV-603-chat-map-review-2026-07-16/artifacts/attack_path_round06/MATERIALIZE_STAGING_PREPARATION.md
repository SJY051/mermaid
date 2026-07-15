# Round 06 Attack-Path Staging Materialization Contract

`materialize_staging.mjs` is a deterministic boundary adapter. It does not perform attack-path analysis, change any semantic decision, append a ledger receipt, or run the final attack-path assembler. It combines frozen 15-key semantic drafts with physically adopted validation metadata to produce the exact current 35-key staging rows accepted by `build_attack_path.mjs`.

This final freeze does not authorize materialization or adoption. The ten draft/report bundle pins below are the accepted post-cross-audit values; any later drift requires a new review and seal.

## Preconditions

The materializer requires all of the following at matching immutable bytes:

- target commit `654f906e00e81648d1482210b6a9171747dddd75` and tree `a14388f597c0c2a17e0dbcfc2d951a390c877214`;
- canonical stream `artifacts/04_reconciliation/deduped_candidates.jsonl`, SHA-256 `274fd61156b7ae3ebeadb1c905258fbe001a5f81380891b9d2491b44652477b8`, with 188 rows;
- approved `artifacts/attack_path_round06/build_attack_path.mjs`, SHA-256 `1421765ba8a03ca0a3f67e98c448520e23462a47781f8e04174bd0cc8397e7f6`; a builder change requires a new schema/policy audit before materialization;
- adopted `artifacts/central_validation_round06/validation.jsonl` and its exact manifest;
- exact central dispositions: 101 reportable, 22 needs_review, and 65 not_reportable;
- every one of the 188 canonical ledgers with exactly one final `central-validation-round-06` receipt and no attack-path receipt;
- the exact threat-model footer:

      Repository: target_sha256_3b79a0a9591bbdee6ac51053b05ea9ecc32c6b6d7bb58211be3c77de70ea2356
      Version: 654f906e00e81648d1482210b6a9171747dddd75

- a repository with no tracked unstaged or staged changes. Existing untracked porcelain is sealed and must remain byte-identical through planning or writing.

The materializer authenticates each historical ledger prefix against the source validation row, central receipt, and central manifest preappend hashes. It authenticates the current post-central ledger against the manifest planned hash and refuses any pre-existing attack-path suffix.

## Frozen workload

Exactly 123 central-validation rows enter materialization, in consolidated validation order:

| Batch | Canonical lines | Rows | Semantic draft | Report source |
|---|---:|---:|---|---|
| batch-01 | 1-38 | 33 | `drafts/batch-01.jsonl` | `batch-01/reports/` |
| batch-02 | 39-76 | 10 | `drafts/batch-02.jsonl` | `batch-02/reports/` |
| batch-03 | 77-113 | 24 | `drafts/batch-03.jsonl` | `batch-03/reports/` |
| batch-04 | 114-151 | 23 | `drafts/batch-04.jsonl` | `batch-04/reports/` |
| batch-05 | 152-188 | 33 | `drafts/batch-05.jsonl` | `batch-05/reports/` |

All 123 reports are immutable inputs already at their builder-facing paths. The materializer verifies their exact inventory and bytes and never rewrites them; its only planned phase outputs are the five materialized JSONL files and the manifest commit marker.

## Final accepted cross-audit pins

These values exactly match the accepted semantic drafts and deterministic report bundles:

| Batch | Semantic draft SHA-256 | Deterministic report-bundle SHA-256 |
|---|---|---|
| batch-01 | `2b417d775da3a54a46740289357c70281bc373d06147db60283c45cf62be672c` | `1d63478b5c738ef7d240d8c524f2127dc5b733319b92633297f4d043eda443aa` |
| batch-02 | `b19fae12e9b2260900f6b09b95263b54a217acbd3c51c081d21b4471cf7c4381` | `c7e8b4c0826cbb17c1502c40a61444c7be07c9dea533f0e655f8048b505c4f5f` |
| batch-03 | `6b0edebc34ad40cc470b142f9548af7b5ed619b3f40e061631dc79619f7bfaa5` | `0c31d3f40b9b9d01d8480ce17f4eae0a1ca0c278b59e71f56c34f8fd2aa806fb` |
| batch-04 | `78d51d018f6049efa66af3aa97e5ab081d6ce3edf8408c5c0a277465ac57cf5e` | `4009560d5e7f7bf16845506970163b7d1a7a2ac29c0e3a43b7c0ba5e706f778d` |
| batch-05 | `6912468eb296915c9a034250ecde308a3afa75464863558aa0b47454ae57bc48` | `0d5f3197d4fba327535a36298bdb3964fdaeee900f5c19b5f7bdedd99f2fddc4` |

For one batch, the report bundle is the LF-terminated canonical JSONL stream of report records in semantic-draft order. Each record has exactly `candidate_id`, `path`, `bytes`, and `sha256`; `path` is the report's source path. The bundle hash therefore seals exact inventory, order, paths, lengths, and bytes without introducing a separate mutable manifest file.

The same ten values are compiled into the materializer. Any byte or inventory drift fails before output planning.

## Exact semantic input

Each compact, canonical semantic draft row has exactly these 15 ordered keys:

    candidate_id,attack_path_steps,attack_path_facts,counterevidence,impact_level,likelihood_level,critical_criteria_satisfied,hard_suppression,hard_suppression_rationale,calibrated_severity,final_policy_decision,final_priority,confidence,proof_gaps,rationale

The materializer copies these values without normalization, policy recalculation, prose changes, list reordering, or field synthesis. It checks that projecting the materialized row back to these keys is JSON-identical to the input row. It also applies the approved builder's current structural enums, reciprocal structured-fact suppression rule, severity matrix, priority mapping, and confidence/counterevidence equality as rejection-only predicates; it never changes a failing value. The final assembler independently repeats those checks and performs the full visible-report validation.

## Exact builder output

The current approved builder schema contains 35 ordered keys:

    schema_version,event,candidate_id,validation_line,source_validation_path,source_validation_stream_sha256,source_validation_row_sha256,central_validation_manifest_path,central_validation_manifest_sha256,threat_model_path,threat_model_sha256,target,validation_disposition,title,instance_key,ledger_row_id,affected_locations,attack_path_steps,attack_path_facts,counterevidence,impact_level,likelihood_level,critical_criteria_satisfied,hard_suppression,hard_suppression_rationale,calibrated_severity,final_policy_decision,final_priority,confidence,proof_gaps,rationale,report_path,report_sha256,canonical_ledger_path,canonical_ledger_sha256

The adoption-dependent fields are derived only as follows:

- validation line, disposition, affected locations, stream hash, and raw row hash: adopted consolidated validation;
- title and instance key: fixed canonical row at that validation line;
- central manifest hash: physically adopted central manifest;
- threat-model hash: exact target-bound per-scan threat model;
- ledger row ID: `central-validation-round-06:<candidate_id>`;
- canonical ledger path and hash: current authenticated post-central/pre-attack ledger;
- report path: builder-facing `batch-0N/reports/<candidate_id>.md`;
- report hash: exact frozen source report bytes;
- schema version, event, target, and fixed artifact paths: constants in the materializer.

Outputs are compact canonical LF-terminated JSONL files:

    artifacts/attack_path_round06/batch-01/attack_path.jsonl
    artifacts/attack_path_round06/batch-02/attack_path.jsonl
    artifacts/attack_path_round06/batch-03/attack_path.jsonl
    artifacts/attack_path_round06/batch-04/attack_path.jsonl
    artifacts/attack_path_round06/batch-05/attack_path.jsonl

The commit marker is:

    artifacts/attack_path_round06/materialization_manifest.json

The manifest contains a sorted seal for every adopted input file, including all 188 current post-central ledgers, every semantic draft and source report, the approved final assembler, and the materializer itself. It also seals each report bundle, materialized JSONL, and repository observation. It is written last.

## Check-only and write gate

Default and explicit check-only modes only calculate the plan:

    node artifacts/attack_path_round06/materialize_staging.mjs
    node artifacts/attack_path_round06/materialize_staging.mjs --check-only

An eventual write requires the exact manifest hash emitted by a fresh accepted check-only run:

    node artifacts/attack_path_round06/materialize_staging.mjs \
      --write \
      --manifest-sha256=<exact planned_manifest_sha256>

Do not run an accepted check-only plan until the central manifest is physically adopted and the repository clean gate passes. Do not run write until the resulting plan seal is independently accepted.

The write transaction creates only missing builder-output directories, stages all files with exclusive temporary names, fsyncs staged files, renames the manifest last, rechecks every input and repository observation, and verifies an idempotent post-write plan. On any observed failure it removes committed files and newly created directories in reverse order. An output without a manifest, a manifest with incomplete or changed outputs, an orphan temporary file, a symlinked path component, an unexpected batch-directory entry, or an unexpected pre-existing output is a fail-closed manual-review condition.
