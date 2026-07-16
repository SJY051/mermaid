---
title: PR #100 public-history cleanup decision packet
status: awaiting SJY051 authorization — no remote mutation performed
created: 2026-07-16 KST
owner: SJY051 (윤서진)
repository: SJY051/mermaid
---

# PR #100 public-history cleanup decision packet

## Confirmed current state

- Current `main` is `3d586695c46815998fa073e4e9d63d51de27fbc5` and its archived files use the
  redacted wording.
- The earlier unredacted archive commit `941210a9f0eeba3417a173a7e04541625081d683` is **not** an
  ancestor of current `main`. Rewriting or force-pushing `main` would therefore add risk without
  removing this exposure path.
- The merged PR's source branch still exists at
  `docs/DEV-603-chat-map-security-review-archive`, currently pointing to
  `ece088764b7ea0719391dc6a0a77c6ad951e6e86`. The earlier commit remains reachable through that
  branch history and PR references.
- The earlier commit contained user health/session wording that should not remain in public audit
  history. This packet intentionally does not repeat that wording.
- Current `main` also contains an inaccurate provenance statement: the archive README says the
  report checksum changed only because links were rewritten, although redaction also changed the
  content. PR #100's description similarly claims a byte-exact original was preserved.

## What branch deletion does and does not do

GitHub permits deleting the head branch of a merged PR. Deleting this source branch removes the
ordinary branch reference and prevents it from being restored accidentally during routine work:

<https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-branches-in-your-repository/deleting-and-restoring-branches-in-a-pull-request>

It is not complete erasure. GitHub documents that sensitive commits may remain reachable by cached
SHA views and pull-request references. GitHub Support can, when it accepts the material as sensitive,
remove affected PR references and cached views and run server-side garbage collection:

<https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository>

No repository operation can remove copies already present in someone else's clone or downloaded
archive.

## Recommended bounded cleanup

1. **Delete only the merged PR #100 source branch.** Do not rewrite or force-push `main`.
2. **Open a GitHub Support request** identifying `SJY051/mermaid`, PR #100, and first affected commit
   `941210a9f0eeba3417a173a7e04541625081d683`; ask Support to remove PR references and cached commit
   views and run garbage collection because the commit contains personal health/session information.
3. **Correct the durable record separately:**
   - change the archive README to state that the checksum differs because both redaction and link
     rewriting changed the repository copy;
   - edit PR #100's description so it no longer claims byte-exact preservation of the unredacted
     report;
   - keep the corrected, redacted current-main archive intact.
4. Record the Support ticket identifier and final response without copying the sensitive wording
   into an issue or public PR comment.

## Suggested Support request

> Repository: `SJY051/mermaid`  
> Affected pull request: #100  
> First affected commit: `941210a9f0eeba3417a173a7e04541625081d683`  
> The commit was removed from the merged `main` history by the PR's corrected squash result, and the
> PR source branch will be deleted. The commit nevertheless remains reachable through PR references
> or cached SHA views and contains personal health/session information. Please dereference or delete
> the affected PR refs, remove cached views, and run repository garbage collection. No credential
> rotation is applicable; the exposure is personal health information rather than a reusable secret.

## Decision record

- Delete the PR #100 source branch now: **DONE — 2026-07-16.** SJY051 authorized; deleted via
  `gh api -X DELETE .../git/refs/heads/docs/DEV-603-chat-map-security-review-archive` and confirmed
  absent from `git ls-remote`. Restorable through the merged PR if ever needed.
- Submit the GitHub Support request: **SKIPPED — 2026-07-16, SJY051 decision.** SJY051 confirmed the
  redacted wording was synthetic test input typed to exercise the triage and negation paths during
  the diagnostic session, not a statement about anyone's actual health. The suggested Support request
  claims "personal health information"; sending it for confirmed-synthetic phrases would misstate the
  facts to Support and is disproportionate. This downgrades the residual exposure from personal-data
  to §2-5 class hygiene, which the branch deletion and main-side redaction already address.
- Prepare the README correction PR and edit PR #100's description: **PARTIALLY OBSOLETE / DONE —
  2026-07-16.** Re-verified against current `main`: the inaccurate provenance sentence ("changes only
  its local Markdown links") is absent — the corrected squash already ships redaction-aware wording in
  both the bundle README and `docs/security/README.md`, so no README correction PR is needed. PR #100's
  description was edited in place: the "byte-exact 보존" bullet now describes the privacy-redacted
  copy, and a dated 정정 section records the correction, the synthetic-input confirmation, and the
  branch deletion.

## Post-decision verification — 2026-07-16

- Redaction completeness on `main`: `git grep -E 'shallow chest pain|no allergies' origin/main -- docs/security`
  returns zero hits. In the unredacted commit `941210a9` the phrases appear only in
  `track_a_final_audit.md`, `report.md`, and `source/scan-report-original.md` (4 hits each); every
  other bundle file is byte-identical between the two versions, and the archive itself records that
  original request bodies were never retained.

