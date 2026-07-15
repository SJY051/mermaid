# Track A final evidence audit

- Audit target: `654f906e00e81648d1482210b6a9171747dddd75`
- Draft audited: `diagnostic_drafts/final_diagnostic_report.md:24-195`
- Result: **usable only after the mandatory qualifications below**. The layer decisions are substantially correct, but the draft overstates the identity of the original owner sessions and the breadth of the A-1 hardening verdict.
- This audit did not edit the repository or the diagnostic draft.

## Evidence and revision boundaries

1. The four original product-owner requests have no retained response headers/HAR. Their original `request_id` values are therefore **unknown**.
2. `RequestIdFilter.java:29-45` puts the ID in MDC and the response header, and `GlobalExceptionHandler.java:166-179` copies it into error bodies. However, `application.yml:60-62` only configures log level; it does **not** configure a pattern containing `%X{requestId}`. The observed daemon lines contain no request ID. The draft's phrase “`application.yml:60-62`의 로그 패턴” is inaccurate.
3. For the fresh reproductions, the ID-to-stack association is temporal, not an MDC join: the response `Date` (UTC) matches the unique daemon/log event at the corresponding KST second. This is strong correlation, but the ID is not printed on the stack line itself.
4. The stale server was PID 8415, started `2026-07-15 00:16:45 KST`, with `DATA_MODE=fixture`, cwd/classpath under `.claude/worktrees/nifty-bohr-af339a/backend`; that pathname is now absent. Its exact Git revision is no longer recoverable. Runtime stack line numbers belong to that old binary, not automatically to the immutable audit target.
5. The clean comparison server was PID 68633 on port 18080, `DATA_MODE=live`, checkout `f68cc39948bdb11139af07017336e45ef07e2325`. The Track A backend source is byte-equivalent for purposes of these paths: `git diff 654f906..f68cc39 -- backend/src/main/java/com/mermaid/chat backend/src/main/java/com/mermaid/common backend/src/main/resources/application.yml` is empty. `ChatScreen.tsx` did change after the target, so all UI emitter line references below are to the immutable target.
6. None of the fresh request JSON bodies was retained. They prove the response/status/path associated with the recorded test, but do not independently prove that the sent prose was byte-for-byte the owner wording. This matters especially for A-1: at the target, explicit-none allergy wording triggers `AllergyDeclaration` before Pass 1a. The exact owner wording is redacted under AGENTS.md §2-5.

## A-1 — “I could not verify that answer against official data…”

### Exact emitting locations at the immutable target

- Terminal malformed/coercion branch: `backend/src/main/java/com/mermaid/chat/ChatProxyController.java:203-210`.
- Terminal validator branch using the same sentence: `ChatProxyController.java:211-219`.
- `StructuredOutputFallback.java:27-29` contains the same constant and uses it for a null-collection rejection at `:57-59`, but it is **not the terminal emitter for the observed `COERCION_FAILED` path**. On coercion exception, `:73-85` returns a `local-fallback`; the controller detects that ID and constructs the final sentence at `ChatProxyController.java:203-210`.

The draft should not present `StructuredOutputFallback.java:27-29` as the observed path's final emitter without this qualification.

### Original ID, reproductions, and mode

- Original owner session: `request_id` **unknown**; original response/HAR absent.
- Historical matching traces: PID 8415, `DATA_MODE=fixture`, no request ID in log.
  - `daemon-80182.out.log:27066-27069`: 2 ingredients → 3 drugs → Pass 2 model answer → `COERCION_FAILED`.
  - `daemon-80182.out.log:27070-27073`: an immediately repeated trace with the same terms/result and the same rejection.
- Clean same-output control: HTTP 200, `X-Request-Id: 760f2b10-6e11-422b-a644-acc9180c4b58`, `DATA_MODE=live`; `/private/tmp/chat-a1-rag.headers:1-5`, `.body:1`. Its request body was not retained.
- Old-runtime same-output/JFR control: HTTP 200, `X-Request-Id: ee642f10-2abf-46d6-9c59-6cf76a41f2eb`, `DATA_MODE=fixture`; `/private/tmp/chat-a2-old.headers`, `.body`; JFR `/private/tmp/old8080-chat-exceptions.txt:71-97` records `JsonParseException` at `StructuredOutputFallback.coerce:49`.

### Full stage trail

**Historical matching trace (the only trace with 2 terms and 3 drugs):**

1. Triage did not short-circuit; reaching extraction proves no matching red flag on that request.
2. Pass 1a returned `ingredients=[Acetaminophen, Ibuprofen]`, no product names (`daemon…:27066` / repeated at `:27070`). That establishes a terminal LLM success-class response for extraction; the exact numeric status was not retained (and no evidence shows whether a schema-400 retry preceded it).
3. Pass 1b completed and produced 3 drugs in fixture mode (`:27067` / `:27071`). There was no government HTTP request in fixture mode, so there is no government upstream status code.
4. Pass 2 completed through the LLM client's success path and logged “model answered” with 8,179 characters of context (`:27068` / `:27072`). `WebClient.retrieve().bodyToMono(...)` makes an HTTP error an exception, so the LLM returned a success-class response; the exact numeric status was not logged and must remain **unknown**.
5. Post-processing logged only `model_answer_rejected code=COERCION_FAILED count=1` (`:27069` / `:27073`). The historic log does not retain the exception type or raw model text.
6. In the corresponding code shape and in the immutable target, a `local-fallback` returns before `ground()` and `AnswerValidator`. There is no `answer_validation_failed` line. Therefore grounding, invariant 6, digit/dose grounding, and `AnswerValidator` did not decide this trace.

**Clean live same-output control (`760f…`):**

1. Pass 1a's model output was not JSON, so the extractor returned 0 terms (`/private/tmp/mermaid-diag-live-f68.log:70-72`). Reaching the parser establishes a terminal LLM success-class response, but not an exact numeric status.
2. Pass 1b was skipped because the query was empty; no government HTTP request occurred.
3. Pass 2 answered (`:73`). Exact LLM numeric status is not retained; only success-class completion is established.
4. `StructuredOutputFallback` logged `COERCION_FAILED exception=JsonParseException` (`:74`), and the HTTP 200 body is the local refusal.
5. Grounding and validator were bypassed by the `local-fallback` branch.

This clean control is **not an exact reconstruction of the owner sentence as written in the brief**. At the target, `AllergyDeclaration.java:38-56` matches the word “allergies” without negation handling, and `DrugContextRetriever.java:89-152` returns a server-authored clarification before extraction. The separate clean response `e21ec535-075c-4a40-8c60-c5c574a9dc2d` and `/private/tmp/mermaid-diag-live-f68.log:69` demonstrate that path. Because the `760f…` request body is absent and it reached extraction, it necessarily differed in wording or request state.

### Root layer and decisive tell

- Deciding layer for the matching trace: **our structured-output coercion boundary, reacting to an LLM response that could not be accepted as `MermAidAnswer`**.
- Not the deciding layer: government API, grounding, invariant 6, digit grounding, or `AnswerValidator`.
- Exact model-output content and exact historic coercion exception: unknown. It is safe to say “coercion failed”; it is too strong to say the historic raw answer was definitely medically legitimate or definitely malformed in the same way as the JFR control.

### Precise hardening verdict

The draft's unqualified “가설은 A-1에 대해 반증됐다” is too broad. Replace it with:

> **A-1 direct-trigger verdict:** No evidence shows the recent digit rule, invariant-6 grounding, or `AnswerValidator` rejecting this answer; the matching trace returns at `COERCION_FAILED` before those checks. The matching fixture trace also proves that Pass 1a and Pass 1b succeeded and that Pass 2 returned. The raw answer was intentionally not retained, so whether its medical prose was otherwise legitimate is unknown. The dose-forbidding system prompt is a generation-time influence, not the logged rejection rule; without an A/B provider capture its indirect effect on output shape is uncertain. Thus the named recent grounding/validator checks are **not the observed direct cause**, while the broader claim that recent prompt hardening had no indirect effect remains **uncertain**.

Also replace “model/provider … 파싱 가능한 응답을 주지 못했다” in the historical-trace verdict with:

> `StructuredOutputFallback` could not accept the model response as a `MermAidAnswer`. The fresh controls identify `JsonParseException`; the historic matching line records only `COERCION_FAILED`, so its exact exception and raw response are unavailable.

## A-2 — “We could not get an answer… Something went wrong on our side.”

### Exact emitting locations at the immutable target

- Frontend title: `frontend/src/components/ChatScreen.tsx:84-90` (`title="We could not get an answer."`).
- Frontend concatenation of backend text: `ChatScreen.tsx:90-118`, specifically `:117`.
- Catch-all response mapping: `backend/src/main/java/com/mermaid/common/GlobalExceptionHandler.java:99-103`.
- Exact backend text: `GlobalExceptionHandler.java:107-124`, specifically `:123`.
- HTTP 500/non-retryable enum: `backend/src/main/java/com/mermaid/common/ErrorCode.java:33`.
- Error envelope and request ID: `GlobalExceptionHandler.java:166-179`.

### Original ID, reproduction, and mode

- Original owner session: `request_id` **unknown**.
- Stale-runtime equivalent reproduction: HTTP 500, `request_id=edf86577-f029-440f-bdcf-bd8bed90d18f`, PID 8415, `DATA_MODE=fixture`; `/private/tmp/track-a2-stale.headers:1-6`, `.body:1`.
- The header time `05:51:52 GMT` equals log time `14:51:52 KST`. Because request IDs are absent from the log pattern, this is a unique-second temporal correlation, not an ID-bearing log trail.
- Clean comparison: HTTP 200, `request_id=e21ec535-075c-4a40-8c60-c5c574a9dc2d`, PID 68633, `DATA_MODE=live`; `/private/tmp/chat-a1.headers`, `.body`; log `/private/tmp/mermaid-diag-live-f68.log:69`.

### Full stage trail and stack

1. `EmergencyTriage` ran first and did not match; otherwise `answer()` would not execute.
2. Request-extension parsing completed and `DrugContextRetriever` detected an allergy declaration before Pass 1a.
3. The log says `Allergy declared ... returning server clarification` (`daemon-80182.out.log:40447`).
4. While materializing that direct server answer, the JVM threw `NoClassDefFoundError: com/mermaid/chat/AllergyClarification`, with `ClassNotFoundException` cause (`:40448-40516`), through `DrugContextRetriever.retrieve` → `ChatProxyController.answer` → `completions`.
5. Pass 1a, Pass 1b, Pass 2, coercion, grounding, and validator did not run. LLM and government upstream HTTP calls: **none**.
6. Spring wrapped the linkage `Error` in `ServletException`, the catch-all handler emitted `INTERNAL_ERROR`, HTTP 500, `retryable=false`.

### Root layer and caveat

- Reproduced root: **our stale/deleted runtime classpath**, not model, public API, validator, or user input.
- It is specifically `NoClassDefFoundError` caused by `ClassNotFoundException`; it is **not** `IllegalArgumentException`, and the target explicitly leaves `IllegalArgumentException` out of client-error mapping at `GlobalExceptionHandler.java:76-88`.
- The clean server's explicit-none allergy clarification exposes a separate negation false positive (`AllergyDeclaration.java:38-56`). That is not the cause of HTTP 500; it explains why an intact current server still stops before recommending drugs.
- Request prose was not retained, so label the request “equivalent reproduction,” not “byte-exact reproduction.”

Suggested replacement for the draft's root paragraph:

> The stale-runtime reproduction reached the server-authored allergy-clarification branch and then failed to load `AllergyClarification`. Its causal chain is `NoClassDefFoundError` → `ClassNotFoundException`, temporally correlated with response ID `edf86577-…`; it is not `IllegalArgumentException`. No LLM or government request was made. The original owner's ID is unavailable, so this proves the reproduced 500 and strongly explains the owner symptom, but does not cryptographically join the historic owner request to that stack.

## A-3 — chest pain → 500

### Exact emitting locations at the immutable target

Same error surface as A-2:

- `frontend/src/components/ChatScreen.tsx:84-90,117-124`
- `GlobalExceptionHandler.java:99-103,123,166-179`
- `ErrorCode.java:33`

### Original ID, reproduction, and mode

- Original owner session: `request_id` **unknown**.
- Stale-runtime equivalent reproduction: HTTP 500, `request_id=73c4b0a5-243f-4aff-8bff-5581a65e8aea`, PID 8415, `DATA_MODE=fixture`; `/private/tmp/track-a3-stale.headers:1-6`, `.body:1`.
- Header time `05:51:59 GMT` equals unique log event `14:51:59 KST`; temporal correlation only.
- Clean comparison: HTTP 200, `request_id=031413d3-cad4-4617-8f0b-6a64780d8bef`, PID 68633, `DATA_MODE=live`; `/private/tmp/chat-a3.headers`, `.body`; log `/private/tmp/mermaid-diag-live-f68.log:75`.

### Full stage trail and stack

1. `EmergencyTriage` ran on raw user text and matched `CHEST_PAIN` before any model call (`daemon-80182.out.log:40518`).
2. While constructing the code-authored emergency answer, `UiAction.ShowEmergencyCall.korea119` attempted to load `UiAction$EmergencyPayload`.
3. `NoClassDefFoundError: com/mermaid/chat/dto/UiAction$EmergencyPayload`, caused by `ClassNotFoundException`, propagated through `EmergencyTriage.emergencyAnswer` to `ChatProxyController.completions` (`:40519-40585`).
4. Pass 1a, Pass 1b, Pass 2, coercion, grounding, and validator did not run. LLM and government upstream HTTP calls: **none**.
5. The catch-all handler emitted `INTERNAL_ERROR` HTTP 500.
6. Clean comparison returned `triage-chest_pain`, emergency, 119 action, and `drugs=[]`; this confirms the target-equivalent backend's intended direct path.

### Root layer and unsupported draft detail

- Root: **stale/deleted runtime classpath**, not model/upstream/post-processing.
- Exception: **`NoClassDefFoundError`/`ClassNotFoundException`**, not `IllegalArgumentException`.
- `/private/tmp/chat-a3.headers` and `.body` do not preserve a duration. The draft's “약 17ms” has no retained supporting artifact and should be deleted unless a timing capture is added.

Exact replacement sentence:

> The clean comparison returned HTTP 200 with request ID `031413d3-…`, `triage-chest_pain`, an emergency 119 action, and `drugs=[]`. The retained artifacts do not establish the claimed 17 ms latency.

## A-4 — first 503, then retry 500

### First 503: exact emitting locations

- `PublicApiException` handler: `backend/src/main/java/com/mermaid/common/GlobalExceptionHandler.java:62-65`.
- Exact user text: `GlobalExceptionHandler.java:107-110`.
- HTTP 503/retryable contract: `backend/src/main/java/com/mermaid/common/ErrorCode.java:29-30`.
- Error envelope: `GlobalExceptionHandler.java:166-179`.

### First 503: original ID, stage trail, mode, and cause

- Original owner session: `request_id` **unknown**; original headers/body were not retained.
- Historical matching trace: PID 8415, `DATA_MODE=fixture`, `daemon-80182.out.log:29544-29554`.
- Stages:
  1. Triage did not short-circuit; extraction ran.
  2. Pass 1a rejected one wrong-shape ingredient element but retained 2 ingredient terms (`:29544-29545`). The extraction LLM therefore reached a terminal success-class response, but its exact numeric status (and any preceding schema-retry status) was not logged.
  3. Pass 1b entered `DrugPermissionApiClient.findByIngredient` and failed at local `FixtureLoader.read` for `/fixtures/permission_ibuprofen.json` (`:29546-29554`). It did not complete a retrieved context.
  4. No government HTTP request was sent; therefore there is **no actual government upstream status code**, 429 or otherwise.
  5. Pass 2, coercion, grounding, and validator did not run.
  6. `FixtureLoader.java:38-50` throws/wraps this local resource failure as `PublicApiException`; `GlobalExceptionHandler.java:62-65` maps that type to `SOURCE_UNAVAILABLE` HTTP 503, producing the misleading government-service sentence.
- Deciding layer: **local fixture/classpath integrity plus an over-broad server error mapping**, not government outage/quota and not the model's answer.

The draft must qualify identity. Replace “관찰된 demo 503은 …” with:

> The only preserved trace matching the demo 503 is fixture-only and contains no government HTTP call: a missing local `permission_ibuprofen.json` was mapped to the same 503 used for government outages. Because the original response ID/HAR is absent, this is a strong symptom/time/runtime match rather than a direct request-ID join. It disproves a live 429 for the matching trace, not every possible 503 seen that day.

### Retry 500: exact emitter, ID, trail, and cause

- Error emitter: same catch-all/frontend locations as A-2/A-3.
- Original owner retry ID: **unknown**.
- Stale-runtime equivalent reproduction: HTTP 500, `request_id=6d5a4f92-528a-4a6b-9562-0f0974451544`, PID 8415, `DATA_MODE=fixture`; `/private/tmp/track-a4-stale.headers:1-6`, `.body:1`.
- Header time `05:52:04 GMT` matches unique log event `14:52:04 KST`; temporal correlation only.
- Stage trail:
  1. Triage ran and did not match.
  2. Before Pass 1a, `MermaidRequestExtension.excludedIngredients` attempted to materialize nested `ParsedList`.
  3. `NoClassDefFoundError: com/mermaid/chat/MermaidRequestExtension$ParsedList`, caused by `ClassNotFoundException`, propagated through `parseList` → `excludedIngredients` → `ChatProxyController.answer/completions` (`daemon…:40587-40654`).
  4. Pass 1a, Pass 1b, Pass 2, coercion, grounding, validator, LLM HTTP, and government HTTP: **not reached**.
  5. Catch-all mapping returned `INTERNAL_ERROR` HTTP 500.
- Root: **stale/deleted runtime classpath**. It is `NoClassDefFoundError`, not `IllegalArgumentException`.
- Clean comparison: HTTP 200, `request_id=f91a6d67-4478-410b-a1dc-df52b9ab6cb3`, PID 68633 `DATA_MODE=live`; `/private/tmp/chat-a4.headers`, `.body`, log `mermaid-diag-live-f68.log:76-79`. That control had 0 terms, so it skipped government retrieval and does not itself measure a public-API status.

Suggested replacement for the clean-control paragraph:

> The target-equivalent clean backend returned HTTP 200 for the comparison request (`f91a6d67-…`). Its extractor produced 0 terms, so Pass 1b made no government call; it proves the 500 is not inevitable in current backend code, but it is not evidence of a successful government drug lookup. The matching historic 503 is the evidence that the demo failure was local fixture lookup, not a live 429.

## Mandatory draft corrections summary

1. Change “`application.yml:60-62`의 로그 패턴” to “no MDC pattern is configured there, and the observed default log lines omit request ID.”
2. State that every original owner `request_id` is unknown; fresh IDs belong only to reproductions/controls.
3. State that fresh IDs are joined to stacks by unique-second timestamp because the stack logs themselves lack request IDs.
4. Do not call `760f…` an exact A-1 reproduction. The retained request body is absent, and explicit-none allergy wording takes a different target path (`e21ec…`).
5. Narrow the A-1 verdict: digit/invariant-6/validator were not the direct trigger; medical legitimacy of raw prose and indirect prompt influence remain unknown.
6. Clarify that `StructuredOutputFallback.java:27-29` is not the terminal `COERCION_FAILED` emitter; the controller's `local-fallback` branch at `:203-210` is.
7. For A-4 first 503, explicitly list Pass 1a partial success, Pass 1b local fixture failure, and non-execution of Pass 2/post-processing.
8. Remove the unsupported “17ms” A-3 latency.
9. Treat unpreserved `check-api-access.py` output as non-evidence or omit it; it is unnecessary to the Track A layer verdict.
10. Preserve the central 500 conclusion exactly: all three reproduced 500s are `NoClassDefFoundError` with `ClassNotFoundException` causes, and none is the §11 `IllegalArgumentException` client-error trap.

## Audited Track A one-line verdicts

| Owner message | Original ID | Reproduction/control ID | `DATA_MODE` | True deciding layer established by retained evidence |
|---|---|---|---|---|
| A-1 refusal | unknown | `760f…` live; `ee642…` fixture | matching history fixture | structured-output coercion before grounding/validator; exact owner-session join and raw content unavailable |
| A-2 500 | unknown | `edf86577…` fixture | fixture | stale runtime `NoClassDefFoundError: AllergyClarification`; clean current backend instead exposes an explicit-none allergy negation false positive |
| A-3 500 | unknown | `73c4b0a5…` fixture | fixture | triage fired first, then stale runtime `NoClassDefFoundError: UiAction$EmergencyPayload`; no LLM/upstream |
| A-4 first 503 | unknown | no ID-bearing reproduction of 503 | matching history fixture | missing local `permission_ibuprofen.json` misclassified as government outage; no government HTTP status exists for matching trace |
| A-4 retry 500 | unknown | `6d5a4f92…` fixture | fixture | pre-Pass-1 stale runtime `NoClassDefFoundError: MermaidRequestExtension$ParsedList`; no LLM/upstream |
