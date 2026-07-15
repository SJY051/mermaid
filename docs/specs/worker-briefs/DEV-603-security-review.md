# Sol Ultra brief — DEV-603 security & prompt-injection review (backend)

Repo: mermaid. Create branch `chore/DEV-603-security-review` off the latest `main`. This is a **read-heavy audit**: the primary deliverable is a report; code changes are minimal and only for defects you can prove.

## Goal and why

mermAid proxies an English speaker's symptoms to an LLM and shows medicine facts verified against government data. The threat that matters most is **prompt injection**: a user (or a crafted symptom string) steering the model into diagnosing, into naming a drug we never fetched, or into leaking the system prompt / the retrieved DRUG_CONTEXT. The proxy already drops client `system` messages and runs `AnswerValidator` after generation. Your job is to audit those defenses as an attacker would, find the gaps, and report them ranked by severity — patching only what is clearly, provably wrong.

Read first (trace the full request path):
- `backend/src/main/java/com/mermaid/chat/ChatProxyService.java` — `prepare()` / the sanitize loop (client `system` messages dropped; `extraSystemMessages` = retrieved DRUG_CONTEXT injected after our prompt, before the conversation). This is the core.
- `backend/src/main/java/com/mermaid/chat/SystemPromptProvider.java`, `MermaidRequestExtension.java` (the `mermaid.*` extension field), `SearchTermExtractor.java` (pass-1 ingredient extraction — the model's output here is a QUERY, not a fact), `DrugContextRetriever.java`, `EmergencyTriage.java` (runs BEFORE the model), `AnswerValidator.java` (runs AFTER).
- `docs/specs/001-foundation/spec.md` §2-1 (never diagnose), §7 (AR invariants), and `AGENTS.md` §2 (the safety invariants).

## Attacker questions to answer (structure the report around these)

1. **System-message stripping**: is dropping `role: "system"` sufficient? What about a `system` message nested in a tool/assistant turn, an array-of-parts content, a `developer` role, or case/whitespace variants of "system"? Does the extension field (`mermaid.*`) offer any injection surface once removed?
2. **DRUG_CONTEXT boundary**: the retrieved context is injected as a system message. Can user text convince the model the context is untrustworthy, or can a crafted product name in the retrieved data (it comes from a government API, but still) carry instructions? Is the context clearly delimited/labeled so the model treats it as data?
3. **Pass-1 extractor**: `SearchTermExtractor`'s output drives which drugs get fetched. Can a user steer it to fetch nothing (suppressing safety info) or to over-fetch? Its output is a query — is it treated as one everywhere downstream?
4. **Emergency triage bypass**: `EmergencyTriage` screens text before the model. Can input dodge the rules yet still be an emergency (the model is the fallback, but invariant 4 only fires if the model says emergency)? Note residual risk; do not redesign it.
5. **Output side**: can any model output reach the UI unvalidated (a path that skips `AnswerValidator`)? Streaming vs non-streaming — do both validate? (Note: the frontend parses only after the stream completes; confirm the backend contract matches.)
6. **Secrets / prompt leakage**: can the system prompt or the service key surface in an answer, an error message, or a log line?

## Deliverable

`docs/security/DEV-603-injection-review.md`:
- a one-paragraph threat model,
- a findings table: id, severity (P0/P1/P2), the attack, the evidence (file:line + why), the recommendation,
- an explicit "what is already solid" section (so the team knows what NOT to churn),
- residual-risk notes for anything out of scope (e.g. triage completeness).

## Boundaries

- **Patch only provable defects**, and only in `chat/` main files, each with a test that goes red without the fix. Anything requiring judgment or spec change → recommendation in the report, not a code change.
- Do NOT touch `backend/src/test/resources/contract/`, `AnswerContractTest.java`, or `AnswerValidatorTest.java` — a parallel task (DEV-601) owns the validator-contract fixtures. If you add a security test, name it for its attack (e.g. `ProxyInjectionTest`) and keep it separate.
- Do NOT edit `frontend/` or any spec other than your report.
- Commit on your branch; do NOT push to `main` or open a PR — the reviewer will.

## Done means

- `docs/security/DEV-603-injection-review.md` exists with the sections above.
- If you patched anything: `cd backend && ./gradlew clean test` exits 0, and each patch has a red-without-it test. Report the command output tail and list every finding by severity.
