# Sol Ultra brief — DEV-601 contract tests (backend)

Repo: mermaid. Create branch `test/DEV-601-answer-contract` off the latest `main`. Work in `backend/` only.

## Goal and why

The proxy's whole safety promise is that `AnswerValidator` catches what a JSON Schema cannot — a model can emit perfectly-shaped JSON that names a drug we never fetched. Today that promise rests on 10 tests. This task turns it into a **fixture battery**: a directory of valid and invalid answer payloads, each invalid one violating exactly one contract, so every invariant has a red-on-regression guard and the schema/validator round trip is pinned.

Read first (do not skip):
- `backend/src/main/java/com/mermaid/chat/AnswerValidator.java` — the 7 invariants (numbered in comments). This is the contract you are pinning; **do not modify it.**
- `backend/src/test/java/com/mermaid/chat/AnswerValidatorTest.java` — the existing 10 tests. Your work COMPLEMENTS these; do not duplicate or edit them.
- `backend/src/main/java/com/mermaid/chat/dto/MermAidAnswer.java` and `AllergyCheck.java`, `UiAction.java` — the payload shape.
- `backend/src/main/java/com/mermaid/chat/AnswerSchemaProvider.java` — the JSON Schema the model is held to (structured-output path).
- `docs/specs/001-foundation/spec.md` §2-15 (why cross-field invariants exist beyond the schema).

## What to create

1. **Fixture battery** under `backend/src/test/resources/contract/`:
   - `valid/*.json` — at least 6 well-formed answers that must pass BOTH schema validation and `AnswerValidator` (violations empty): a routine answer with drugs+sources, an emergency with the call action, a `no_match_found` allergy answer, a fixture-data answer correctly labelled, a multi-drug answer, a minimal answer with no drugs.
   - `invalid/*.json` — one file per violation, each named for what it breaks (e.g. `inv6-unretrieved-drug.json`, `inv2-blocked-no-ingredient.json`, `inv4-emergency-no-call.json`, `inv1-unknown-sourceref.json`, `inv3-live-on-fixture.json`, `inv5-official-no-source.json`, `inv7-url-in-summary.json`, `inv7-script-in-warning.json`). Add **schema-level** invalids too (missing required field, wrong enum, wrong type) under `invalid/schema/`.
2. **`backend/src/test/java/com/mermaid/chat/AnswerContractTest.java`**:
   - loads every `valid/*.json`, parses to `MermAidAnswer`, runs `AnswerValidator.validate(...)` with a retrieved-name set that matches the fixture's drugs, asserts **zero violations** — parameterized over the directory so adding a fixture adds a case.
   - loads every `invalid/*.json`, asserts the expected violation is present (assert on a substring the invariant emits, e.g. "never retrieved", "blocked but names no ingredient"). Name the invariant each targets in the `@DisplayName`.
   - schema invalids: assert they fail JSON Schema validation via the same validator the proxy uses (`AnswerSchemaProvider`); if the project has no standalone schema-validation entry point, note that in a comment and cover them at the DTO-parse level instead (a required-field violation surfaces as a parse/binding failure) — do not invent a new schema library.
3. If, while writing fixtures, you find an invariant the validator does NOT actually catch (a real gap), **do not patch AnswerValidator** — add a `@Disabled("DEV-601: validator gap, see report")` test documenting it and list it at the top of your final report. The reviewer decides whether the code or the expectation is wrong (a red test may mean the code is wrong, spec §10).

## Boundaries

- Create only test files and fixtures. The only production file you may READ but not edit: everything in `chat/`. Do NOT touch `AnswerValidator.java`, any DTO, `AnswerValidatorTest.java`, or anything under `chat/` main.
- Do NOT touch `backend/src/main/java/com/mermaid/chat/ChatProxyService.java` or the `security/` area — a parallel task (DEV-603) owns proxy-injection analysis. Your surface is the validator contract only.
- Do NOT edit `frontend/` or `docs/` (except your final report, see below).
- Match the existing test style (JUnit 5 `@Nested`/`@DisplayName`, AssertJ). Comments explain WHY.
- Commit on your branch; do NOT push to `main`. Do NOT open a PR — the reviewer will.

## Done means (run and show the output)

```
cd backend && ./gradlew clean test
```
Exit 0. Report: the new test count, the fixture count (valid/invalid/schema), and any validator gaps you parked as `@Disabled`. Write a short `docs/specs/worker-briefs/DEV-601-report.md` with those three things and the command output tail.
