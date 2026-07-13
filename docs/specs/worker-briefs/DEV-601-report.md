# DEV-601 report

## Validator gaps (`@Disabled`)

1. Invariant 6 checks only the retrieved Korean product name, so fabricated ingredient details on a retrieved product pass validation.
2. Invariant 7 does not scan `urgency.message`, so a model-authored URL there passes validation.
3. Invariant 7's pattern does not reject generic HTML such as `<b>` even in a field it scans.

All three tests fail when temporarily enabled and are parked with `@Disabled("DEV-601: validator gap, see report")` for reviewer disposition.

## Battery result

- New tests: 20 cases — 17 passing and 3 disabled. Full suite: 296 tests, 3 skipped, 0 failures, 0 errors.
- Fixtures: 20 total — 6 valid, 8 validator-invalid, 3 schema-invalid, and 3 disabled-gap fixtures.
- Schema path: the proxy has no standalone JSON Schema engine. Model-facing fixtures keep `sourceRefs` empty, assert the top-level `AnswerSchemaProvider` contract and constants, and pass direct DTO binding before server sources are injected. Schema-invalid cases assert their provider-schema constraint and fail direct DTO binding, as allowed by the contract. This fallback does not claim full recursive schema validation; without adding a forbidden new validator, future nested `required` or `additionalProperties` drift remains provider-enforced rather than locally executable.
- Invariant 3 input: the harness intentionally preserves `dataStatus=live` while injecting a fixture source. Production grounding normally corrects that label; the inconsistent state exists here to pin the validator's regression guard.

## Required command

`cd backend && ./gradlew clean test` exited 0.

```text
> Task :test

[Incubating] Problems report is available at: file:///private/tmp/mermaid-dev601/backend/build/reports/problems/problems-report.html

BUILD SUCCESSFUL in 11s
6 actionable tasks: 6 executed
```
