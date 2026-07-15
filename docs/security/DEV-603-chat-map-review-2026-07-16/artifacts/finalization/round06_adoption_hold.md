# Round 06 centralized-receipt adoption hold

## Outcome

The five centralized-validation batches are semantically frozen and independently checked, but their consolidated stream and 188 canonical-ledger suffixes were not written. The attack-path semantic batches are likewise being frozen without post-central ledger receipts. This is an intentional hold, not a claim that the phases never ran.

## Gate 1 — shared repository is not clean

At `2026-07-16 02:27:15 KST`, the shared checkout was:

- branch: `fix/DEV-202-pharmacy-radius-quality`
- HEAD: `d6a143a9b3fea571e84f0af12a03d3b5af3b6ee1`
- tree: `8b28dbd071149bf5cef9e87d06f934d48737c503`
- `git diff --quiet`: exit `1`
- `git diff --cached --quiet`: exit `0`

Tracked unstaged paths were:

```text
backend/src/main/java/com/mermaid/facility/FacilityController.java
backend/src/main/java/com/mermaid/facility/FacilityService.java
backend/src/main/java/com/mermaid/facility/PharmacyApiClient.java
backend/src/main/java/com/mermaid/facility/domain/Facility.java
backend/src/main/resources/application.yml
backend/src/test/java/com/mermaid/config/CacheConfigTest.java
backend/src/test/java/com/mermaid/facility/FacilityServiceTest.java
backend/src/test/java/com/mermaid/facility/PharmacyApiClientTest.java
```

The untracked `HiraPharmacyProperties.java` belongs to the same concurrent facility task. `.pnpm-store/` and the three worker briefs were already outside this scan's write scope. This diagnostic/security scan did not edit, stage, stash, revert, commit, or delete any of them.

Running the approved central builder from the scan root produced exactly:

```json
{
  "mode": "check-only",
  "errors": [
    "repository has tracked unstaged changes"
  ]
}
```

Exit status was `1`. The builder intentionally checks the real shared checkout and refuses both planning and writes while tracked or staged changes exist. That guard was not bypassed or weakened.

## Gate 2 — one frozen affected-location claim needs provenance correction

`R05-CAN-004` preserves `DrugService.java:236-253` for the `itemSeq` deduplication claim, but immutable-target inspection locates that predicate at `DrugService.java:214-219`. See [the correction hold](r05_can_004_provenance_correction_hold.md). Appending the prepared receipt unchanged would permanently seal a known inaccurate evidence range.

## Phase state and safe resume

- Central semantic judgments: complete for all 188 canonical rows.
- Consolidated central stream/manifest: absent by design under this hold.
- Canonical central-validation suffixes: absent.
- Attack-path semantic drafts/reports: prepared and independently re-audited batch by batch.
- Exact 35-key attack staging: materializer prepared but not executed because it must derive post-central ledger hashes.
- Canonical attack-path suffixes: absent.

Round 7 should first clear the shared-worktree ownership issue, apply a provenance-only correction under a new authority seal, rerun central check-only twice, adopt the exact accepted plan, materialize the attack staging, and then run attack check-only/write. No Round 0-6 discovery regeneration is required.
