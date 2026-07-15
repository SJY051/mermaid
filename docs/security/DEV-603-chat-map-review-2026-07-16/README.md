# DEV-603 chat/map diagnostic and security review

This directory is the durable repository copy of the Round 0–6 diagnostic and
adversarial security review completed on 2026-07-16. The original working
artifacts lived under a macOS temporary scan directory; this bundle preserves
the human report and the machine-readable state needed to audit or resume it.

The review changed no application, validator, grounding, fixture, or runtime
behaviour.

## Start here

| Reader | Required document | When to read more |
|---|---|---|
| Product owner or reviewer | [Final report](report.md) | Read the Track A/B/C audits only when checking a disputed diagnosis. |
| Follow-up fix-brief author | [Final report](report.md) and [hardening portfolio](hardening/hardening.md) | Open the relevant validation and attack-path report for the finding being scoped. |
| Round 7 coordinator | [Final report](report.md), [workflow](WORKFLOW.md), terminal state, authority manifest, both adoption holds | Consume the frozen JSONL inputs mechanically; do not expose prior findings to blind discovery workers. |
| Round 7 discovery worker | Repository AGENTS.md, the immutable target, the shared worklists, and its own worker brief | Do not read this report, prior candidates, validation results, or novelty history before completing discovery. |

The [final report](report.md) is the human-facing primary deliverable. The
byte-exact temporary scan projection is retained as
[source/scan-report-original.md](source/scan-report-original.md), SHA-256
38d0dd1e15ce9e573ec1d15f2baba0a5aca6fcc21b597414326edfc43e48b4ab.
The repository copy changes only its local Markdown links so they resolve
inside this durable bundle.

## Authority

- Scan ID: 4ad9c6af-1323-4ce2-88ad-7af5cbfbbb1f
- Audited commit: 654f906e00e81648d1482210b6a9171747dddd75
- Audited tree: a14388f597c0c2a17e0dbcfc2d951a390c877214
- Terminal reason: capped_by_user_deadline_after_round_06
- Saturation: not proven (saturation_proven=false)
- Canonical inventory: 188 rows, SHA-256
  274fd61156b7ae3ebeadb1c905258fbe001a5f81380891b9d2491b44652477b8
- Central validation: 101 reportable, 22 needs review, 65 not reportable
- Repository review tier: 33 reportable P0 findings
- Attack-path analysis: 123 eligible rows across five frozen batches

The authoritative state files are:

- [terminal state](artifacts/deep_merge/round-06_terminal_state.json)
- [semantic authority manifest](artifacts/deep_merge/round-06_semantic_final_authority_manifest.json)
- [canonical candidates](artifacts/04_reconciliation/deduped_candidates.jsonl)
- [central validation adoption contract](artifacts/central_validation_round06/ASSEMBLER_PREPARATION.md)
- [attack-path materialization contract](artifacts/attack_path_round06/MATERIALIZE_STAGING_PREPARATION.md)
- [receipt adoption hold](artifacts/finalization/round06_adoption_hold.md)
- [R05-CAN-004 provenance correction hold](artifacts/finalization/r05_can_004_provenance_correction_hold.md)

## Layout

The bundle contains:

- README.md — human index and authority;
- WORKFLOW.md — Round 7 continuation contract;
- report.md — primary final report with durable links;
- source/scan-report-original.md — byte-exact temporary projection;
- diagnostic_drafts/evidence/ — browser console/network evidence;
- hardening/ — six proposals and 18 Mermaid diagrams;
- artifacts/01_context/ — canonical validation threat model;
- artifacts/02_discovery/ — frozen shared worklists;
- artifacts/04_reconciliation/ — 188 canonical candidates;
- artifacts/central_validation_round06/ — five validation batches and builder;
- artifacts/attack_path_round06/ — five attack batches, reports, and tools;
- artifacts/deep_merge/ — authority and terminal state;
- artifacts/finalization/ — Track A/B/C audits and adoption holds.

The original relative paths are intentionally preserved below artifacts/. The
builders derive their scan root from those paths.

## Integrity

From this directory, run:

    shasum -a 256 -c SHA256SUMS

SHA256SUMS covers every archived file except itself. The top-level report has a
different hash from the original projection only because its temporary absolute
links were rewritten to repository-relative links.

## Inclusion boundary

Included:

- final report and Track A/B/C evidence audits;
- complete hardening portfolio;
- canonical 188-row inventory and shared worklists;
- all five centralized validation batches and reports;
- all five attack-path drafts and 123 individual reports;
- authority, terminal, hold, assembler, and materializer artifacts.

Excluded:

- repeated raw outputs from every discovery worker;
- copied build trees, test class files, transient server logs, and browser
  screenshots already reduced into the accepted evidence documents;
- canonical central/attack receipts that were deliberately never written.

The excluded worker material is not needed for ordinary review or the defined
Round 7 continuation. It remains improper to claim discovery saturation from
this archive.

## Safety notes

- This is evidence, not a patch. A follow-up must not weaken an AGENTS.md §2
  invariant to make the chat or map appear functional.
- The original owner request IDs remain unknown because no HAR/header was
  preserved and the server log pattern omitted the MDC request ID.
- Central and attack-path semantic results are complete, but their canonical
  receipt adoption is still held. Read both hold documents before any write.
- The archived scripts contain the original machine path and write contracts.
  Never execute them directly inside docs/; rehydrate the bundle into an
  isolated scan workspace and retain the clean-worktree gates.
