# Round 7 continuation workflow

This is the exact continuation contract for the capped Round 6 review. It is
not permission to fix application behaviour, weaken safety checks, or
reinterpret the frozen Round 0–6 results.

This bundle is a historical snapshot from a maintainer's local clone, not a
live authority for current `main` or production. Round 7 may extend the frozen
scan only against the audited tree. Any remediation or current-release decision
must separately revalidate the affected path against its actual target and
environment.

## 1. Establish authority

1. Read the repository AGENTS.md, especially §2, Review guidelines, and §11.
2. Read [report.md](report.md) and both hold documents.
3. Verify SHA256SUMS.
4. Verify the target commit and tree. The scan recorded commit
   `654f906e00e81648d1482210b6a9171747dddd75`, published at
   `refs/heads/feat/DEV-007d-icons`. If that object is absent from a narrow
   checkout, fetch that ref explicitly:

       git fetch origin refs/heads/feat/DEV-007d-icons:refs/remotes/origin/feat/DEV-007d-icons
       test "$(git rev-parse 654f906e00e81648d1482210b6a9171747dddd75^{tree})" = a14388f597c0c2a17e0dbcfc2d951a390c877214

   Commit `f4a2b6de89f5e4fa4ef5a81e5dafd54f8255367b`, reachable from `main`, owns
   the same audited tree and is the portable fallback for source inspection:

       test "$(git rev-parse f4a2b6de89f5e4fa4ef5a81e5dafd54f8255367b^{tree})" = a14388f597c0c2a17e0dbcfc2d951a390c877214
5. Verify the frozen canonical input:
   - 188 rows;
   - SHA-256 274fd61156b7ae3ebeadb1c905258fbe001a5f81380891b9d2491b44652477b8;
   - terminal state capped_by_user_deadline_after_round_06;
   - saturation_proven=false.

Do not silently update the audited tree to current main. A new tree requires a
new scan authority and invalidates line-level evidence from this bundle.

## 2. Rehydrate, never execute in the archive

Copy this directory to an isolated scan workspace while preserving the
artifacts/ layout. Do not run builders from the repository copy: they may write
receipts and make the documentation branch dirty.

The archived builders also pin /Users/asqi/Developer/mermaid as the repository
path. Treat their bytes and hashes as audit evidence. If a continuation runs on
another path, generate a new authority-bound builder or make an explicitly
reviewed copy; do not mutate the archived script and continue to quote its old
hash.

## 3. Clear the two physical-adoption holds

Before Round 7 discovery:

1. Correct R05-CAN-004 affected-location provenance from
   DrugService.java:236-253 to the immutable target's actual item-sequence
   deduplication predicate at DrugService.java:214-219.
2. Issue a new authority seal and correction receipt. Do not edit only the
   display report; keep canonical candidate, ledger, authority manifest, and
   hashes aligned.
3. Wait for a repository state with no tracked staged or unstaged changes. Do
   not stash, revert, resolve, or commit another worker's changes to open this
   gate.

The exact blockers are documented in:

- [round06_adoption_hold.md](artifacts/finalization/round06_adoption_hold.md)
- [r05_can_004_provenance_correction_hold.md](artifacts/finalization/r05_can_004_provenance_correction_hold.md)

## 4. Adopt central validation

Use the frozen five validation.jsonl files and their reports. Follow
[ASSEMBLER_PREPARATION.md](artifacts/central_validation_round06/ASSEMBLER_PREPARATION.md)
without weakening its clean-worktree, target, schema, row-count, or hash gates.

Required order:

1. run check-only twice;
2. confirm the outputs are byte-identical;
3. perform one manifest-bound write;
4. run the post-write audit;
5. record the new consolidated stream, manifest, and ledger suffix hashes.

The semantic decisions are already complete. This phase adopts receipts; it
must not reclassify findings opportunistically.

## 5. Materialize attack-path receipts

After central validation adoption, use the frozen attack drafts and 123
individual reports. Follow
[MATERIALIZE_STAGING_PREPARATION.md](artifacts/attack_path_round06/MATERIALIZE_STAGING_PREPARATION.md).

Required order:

1. verify all five draft and report-bundle pins;
2. run materializer check-only twice and compare bytes;
3. materialize the five attack-path streams;
4. run attack assembler check-only;
5. perform the sealed write and post-write audit.

Repository priority and the generic exploitability matrix remain separate
axes. Generic priority never lowers an AGENTS.md §2 P0.

## 6. Run Round 7 blind-first discovery

Only after the adoption boundary is resolved:

1. start exactly six independent discovery workers;
2. give every worker the same canonical brief and the same
   [rank input](artifacts/02_discovery/rank_input.jsonl) and
   [deep-review input](artifacts/02_discovery/deep_review_input.jsonl);
3. require each worker to generate its own target-specific threat model;
4. do not give workers this final report, the 188 prior candidates, validation
   results, attack reports, recurrence hints, or novelty themes;
5. do not inspect substantive partial-round output;
6. wait until all six workers are complete and idle before merge.

The coordinator may know the prior state. Discovery workers must remain blind
to it until their independent outputs are frozen.

## 7. Reconcile novelty

After Round 7 closes:

1. compare the six preserved outputs against the frozen 188-row canonical
   inventory;
2. merge only when one remediation closes every absorbed candidate;
3. preserve remediation-distinct residue as a new candidate;
4. write the Round 7 merge record, inventory, canonical report, ledgers, and
   novelty comparison;
5. rerun centralized validation and attack-path analysis only for the changed
   canonical set, while maintaining complete canonical manifests.

If Round 7 adds zero new canonical clusters, record saturation. If it adds any
new cluster, another complete six-worker round is required unless the user
applies a new explicit cap.

## 8. Handoff boundaries

| Role | May read before discovery | Must not do |
|---|---|---|
| Coordinator | Entire bundle | Feed prior findings or themes into worker prompts |
| Discovery worker | AGENTS.md, target, worklists, own brief | Read prior candidates, report, validation, or attack results |
| Validation/attack worker | Frozen Round 7 canonical inputs and canonical threat model | Reopen discovery merge decisions without evidence |
| Fix worker | Final report plus only the assigned finding evidence | Change a §2 invariant without human judgment |

Round 7 remains part of the same resumable scan:
4ad9c6af-1323-4ce2-88ad-7af5cbfbbb1f.
