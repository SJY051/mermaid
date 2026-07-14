# Worker brief — DEV-603 / DEV-601 backend follow-up (bounded, mechanical)

**For:** Sol Ultra (backend). **Orchestrator/verifier:** 한결.
**Base:** current `main` (`4b89bac` or later). Branch `fix/DEV-603-followup-validator-and-proxy` off `main`.
**Why bounded here:** these are deterministic backend fixes with clear right answers. The *architectural* residuals (OUT-02/03/04, EX-02) are deliberately NOT in this brief — they need design decisions and live in #55/#56. Do not touch them.

## Goal

Close the mechanically-fixable residuals from the DEV-603 audit
(`docs/security/DEV-603-injection-review.md`) plus the DEV-601 validator gaps (#50).
Every item is fail-closed: when in doubt, refuse, don't guess.

## Scope — do exactly these, nothing more

### A. AnswerValidator gaps (DEV-601, #50) — un-disable and make pass
The three `@Disabled` contract fixtures in `AnswerContractTest` describe real gaps. Implement the checks, then remove `@Disabled` and confirm red-before/green-after.
1. **inv6 ingredient mismatch** — validator must reject a drug card whose ingredient set does not match the retrieved record (not only the product name).
2. **inv7 urgency.message unscanned** — extend the URL/markup scan to `urgency.message` (and any rendered field currently omitted; cross-check `userVisibleText` coverage against `ChatScreen.tsx` sinks).
3. **inv7 generic HTML not rejected** — reject HTML-looking strings, not only `<script>`.

### B. AnswerValidator mechanical hardening (OUT-05/06/07)
4. **OUT-05** — validate every guidance `sourceRefId` against server-owned sources (membership, not just non-empty).
5. **OUT-06** — preserve a server-owned product→source map; validate the *pair*, not two independent sets (A's card must not cite B's source).
6. **OUT-07** — reject `null` elements in `drugs[]`/`guidance[]` during coercion, or fail closed to the fixed refusal. Keep the two list instances as separate regression cases (both crashed a harness with NPE).

### C. Proxy / DTO hardening (OUT-08, SM-02, LEAK-01)
7. **OUT-08** — validate `UiAction` payloads after deserialization; reject/strip an action with `types:null` (client currently indexes `types[0]`). `dto/UiAction.java:83-84`.
8. **SM-02** — build the upstream request body from an explicit top-level **allowlist**, and rebuild each allowed message from only its exact `role` + normalized text `content`. Set generation/tool/retention (`n`, token limits, `store`, `tools`/`functions`, `tool_calls`, `function_call`, `name`) **server-side**, never passed through from the client. `ChatProxyService.java:157-234`. NOTE: this is an API-compatibility-sensitive change — keep the first-party frontend working (`pnpm test` + a real browser round-trip is the gate). If a field's client-vs-server ownership is unclear, refuse the passthrough and document it.
9. **LEAK-01** — replace value-bearing log messages (rejected extractor terms, model-derived validation text) with stable **violation codes + counts**; neutralize control characters (strip `\r`/`\n`) before logging. `SearchTermExtractor.java:163-178`, `ChatProxyController.java:172-175`.

### Out of scope (do NOT touch)
- OUT-02, OUT-03, OUT-04 (→ #55, architecture), EX-02 (→ #56, clinical/design), ET-03 (→ DEV-405 clinical review).
- Frontend beyond confirming SM-02 didn't break the client.
- The `reviewer` column in `synonyms.tsv` (human-only, §2-6).

## Completion criteria (show the output)
- `cd backend && ./gradlew clean test` → exit 0, all green (report the count). Every new check has a test that is **red before the fix, green after** — name the mutation for each.
- `cd frontend && pnpm test && pnpm build` → both pass (SM-02 must not break the first-party client).
- No new value-bearing log line carries symptom/identity/prompt text or a raw newline.
- Commit the scaffold before implementing so each fix's diff is auditable. One commit, English, Conventional Commits.
- Report: which findings closed, the mutation that turns each new test red, and anything you refused as out-of-scope or unclear.
