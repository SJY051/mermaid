# Track C finalization audit

## Outcome

Track C의 일곱 항목은 모두 소스와 증거에 닿아 있지만, 현재 초안의 최종 P0/P1 표현은 그대로 확정할 수 없다. `C-2`, `C-4`, `C-6`에는 현재 증거만으로도 유지할 수 있는 P0/P1 부분이 있다. 반면 `C-1`, `C-5`, `C-7`은 구조적 경계 또는 후보가 확인됐을 뿐 실제 안전 영향/secret 노출/주입 성공이 아직 입증되지 않았고, `C-3`의 schema-key 노출은 현재 confidentiality 경계가 없어 P1로 보고할 근거가 없다.

이 감사는 저장소와 기존 초안을 수정하지 않았다. 최종화 전에 아래의 교체 문구를 초안에 반영해야 한다.

## Scope and authority

- Immutable target commit: `654f906e00e81648d1482210b6a9171747dddd75`
- Immutable target tree: `a14388f597c0c2a17e0dbcfc2d951a390c877214`
- Audited draft: `diagnostic_drafts/final_diagnostic_report.md`, SHA-256 `4f7c273ce2c0c45bcc38b81eb255712a770916509305998be3c5dd2af702c5a7`
- Audited section: draft lines 274–348, `Track C — 일곱 가지 적대적 보안 검토`
- Round-06 authority boundary: `artifacts/deep_merge/round-06_semantic_final_review.md:1-4,85-87` explicitly says this is discovery reconciliation and that centralized validation and centralized attack-path analysis have not run.
- Round-06 novel audit boundary: `artifacts/deep_merge/round-06_novel_residue_audit_a.md:1-4,12-20` says promotion means handoff to the builder while still pending centralized validation, not a final finding or severity decision.

`PRESENT` below means the stated source/control/sink behavior was observed at the immutable target, with a dynamic probe where one is cited. It does not by itself mean exploitability or final reportability. `ABSENT` is limited to the inspected target and paths. `UNCERTAIN` names a remaining proof gap. P0/P1 is assigned only where the evidence reaches the repository's `AGENTS.md` §2/review-guideline boundary; conditional tiers are not final findings.

## Five-point validation rubric

| Criterion | Result | Audit check |
|---|---|---|
| Exact claim coverage | PASS | All seven Track C headings and every material subclaim are classified. |
| Immutable source trace | PASS | Every positive claim is tied to target source locations; target commit/tree are pinned. |
| Probe support | PASS | Dynamic claims cite stable probe files, request IDs, and hashes where available. |
| Counterevidence and proof gaps | PASS | Each item records controls, negative probes, or unresolved provider/clinical/deployment facts. |
| Severity and replacement accuracy | PASS | Final P0/P1 is separated from conditional severity, and stale/unsupported draft language receives an exact replacement. |

## Executive disposition

| Item | Requested status | AGENTS §2 / review tier | Finalization action |
|---|---|---|---|
| C-1 client `system`/role smuggling | **PRESENT** for client-authored `assistant`; **ABSENT** for exact client `system`/`developer`/`tool`; **UNCERTAIN** for safety impact | No final P0/P1. Conditional **P0** only if central validation proves an unsafe medical-policy bypass. | Replace categorical `PRESENT — P0`; retain as a provisional assistant-role trust candidate. |
| C-2 triage order and OUT-04 | **ABSENT** for ordering bypass; **PRESENT** for affirmative category omissions and structural model-emergency-plus-drugs gap | Category omission is **P0** under §2-4. Emergency-plus-drugs is conditional **P0** until its reachable final output is validated. | Split the absent ordering bypass from the two fail-open conditions. |
| C-3 prompt/context/other-user leakage | **PRESENT** only for schema-key spill; **ABSENT** for app-side cross-user mixing and a current P0/P1 confidentiality leak; **UNCERTAIN** for provider retention/full model behavior | No P0/P1 on current evidence; Round-06 security coverage suppresses this confidentiality hypothesis. | Remove the draft's P1. Preserve a quality/control-gap note and reopen condition. |
| C-4 transcript/PII logs and persistence | **ABSENT** for normal well-formed transcript persistence; **PRESENT** for medicine-term and precise-coordinate logs; malformed-body health-text logging is **PROVISIONAL** | Derived terms/coordinates: **P1**. Malformed consultation text would be **P0** under §2-5 if centrally validated. | Qualify `원문 transcript 저장은 ABSENT`; it is not globally true across malformed input. |
| C-5 secrets and error verbosity | **PRESENT** for denylist coverage gap; **ABSENT** for an actual current-bundle/client-error secret leak | No current P0/P1. A real secret behind an accepted `VITE_*` name would be **P0** under §2-7. | Replace categorical `P0 구조적 우회`; retain conditional control defect and positive-allowlist recommendation. |
| C-6 fail-open/fail-closed symmetry | **PRESENT** overall; two P0 bases are directly supported, while three adjacent impacts remain partly uncertain | **P0** for reaction-only allergy miss (§2-2) and hybrid query/provenance mismatch (§2-9). Other bullets are conditional. | Retain P0 only with a narrowed, subclaim-by-subclaim basis. |
| C-7 public-API data injection | **PRESENT** for system-role elevation; **ABSENT** for inspected app-authored DOM XSS; **UNCERTAIN** for instruction success/unsafe impact | No final P0/P1. Conditional **P0** if central validation proves §2 medical-output impact. | Replace categorical `PRESENT — P0`; keep as a deferred injection candidate. |

## C-1. Client `system` / role smuggling

### Verdict

- **PRESENT:** a caller can supply an `assistant` message that is forwarded as `assistant` in Pass 2.
- **ABSENT:** exact caller-supplied `system`, `developer`, `tool`, and nested-role values are not forwarded as those roles in the reproduced path.
- **UNCERTAIN:** the evidence does not show that the accepted `assistant` message changes the safety outcome, defeats a server-owned rule, leaks another user's data, or crosses a privilege boundary.
- **Severity:** no final P0/P1. If a central validation probe demonstrates that the role distinction bypasses §2-1/§2-2/§2-4 safety behavior, the result would be **P0**. That conditional tier must not be written as the current result.

### Proof

- `backend/src/main/java/com/mermaid/chat/ChatProxyService.java:151-163` builds pre-scan text from `user` roles only.
- `backend/src/main/java/com/mermaid/chat/ChatProxyService.java:195-227` accepts `user` and `assistant` messages and preserves their role; the other tested client roles are dropped.
- Probe request `X-Request-Id: 1c7336dc-7a3c-4f85-9eab-a9e6020179d6` produced Pass-2 roles `system, system, assistant, user, user` in `/private/tmp/mermaid-role-proof.cDxd35/fake-openai.log` (SHA-256 `edff3bbcfcdfac07f874efd352b85c627c0201f3814372e762e7cb19640459c8`). The crafted request is `/private/tmp/mermaid-role-proof.cDxd35/crafted-request.json` (SHA-256 `e5484f63f0ef5dc3f7bc3471ed842aab6e42e0a831660e14b0079563004451f2`).
- Frozen canonical `R01-CAN-027` records this as a plausible discovery candidate pending centralized validation. Round-06 Part 13 recurs to it as `R06W06-CAND-007`; its source-local disposition remains deferred.

### Counterevidence and proof gaps

- The direct probe proves that `system`, `developer`, and `tool` do not survive as privileged roles.
- `artifacts/deep_discovery/round-06/worker-05/reviewed_surfaces.md` does not promote the assistant-history case: the caller can place equivalent text in a user message, the request is stateless, and no cross-user or administrative boundary is shown.
- No paired probe demonstrates a safety-relevant output difference between identical text sent as `user` and as client-authored `assistant`.

### Replacement language

Replace draft C-1's verdict and explanatory paragraph with:

> **판정: PRESENT(클라이언트 `assistant` 역할 보존) / ABSENT(직접 `system`·`developer`·`tool` smuggling) / UNCERTAIN(안전 영향). 최종 P0/P1 미확정.** `ChatProxyService.java:151-163`의 선검사는 `user`만 모으고, `:195-227`은 클라이언트가 보낸 `assistant`를 Pass 2에 그대로 보낸다. 동적 probe에서도 Pass 2 역할이 `system, system, assistant, user, user`로 확인됐다. 그러나 직접 `system` 계열 역할은 제거됐고, 이 역할 차이가 서버 안전 규칙을 우회하거나 다른 사용자/권한 경계를 넘는 출력 차이를 만든다는 증거는 아직 없다. 따라서 `R01-CAN-027`의 구조적 trust candidate로 유지하되 중앙 검증 전에는 P0로 확정하지 않는다. 중앙 검증이 §2 안전 출력 우회를 재현하면 그때 P0로 승격한다.

The remediation direction may remain, but it must be labeled hardening/provisional rather than a validated P0 fix.

## C-2. Emergency-triage order and OUT-04

### Verdict

- **ABSENT:** for expressions that match the current rules, a model-before-triage ordering bypass was not found.
- **PRESENT — P0:** affirmative severe-allergy/anaphylaxis, seizure, poisoning/overdose, and head-injury categories are omitted from the deterministic matcher, and an anaphylaxis probe fell through to routine model/allergy handling. This directly weakens §2-4.
- **PRESENT structurally / impact pending — conditional P0:** model-authored `emergency` output is checked for the 119 action but not canonicalized to `drugs=[]`. The source gap is present; a final model-emergency-plus-drugs response was not dynamically reproduced in this audit.

### Proof

- `backend/src/main/java/com/mermaid/chat/ChatProxyController.java:84-99` invokes deterministic triage before `answer()`/the LLM for the inspected JSON and stream branches.
- `backend/src/main/java/com/mermaid/chat/EmergencyTriage.java:29-54` explicitly leaves severe allergy/anaphylaxis, seizure, poisoning/overdose, and head injury/vomiting unimplemented.
- `backend/src/main/java/com/mermaid/chat/EmergencyTriage.java:75-96` authors the 119 response in code with an empty drug list for a deterministic hit.
- `backend/src/main/java/com/mermaid/chat/AnswerValidator.java:101-107` validates the call action for a model `emergency` result but does not require an empty drug list.
- `/private/tmp/anaphylaxis.body` (SHA-256 `0ebea579cc64b1715e7d3f0487e1de1287c4fdc16baf5570b6eafec561ca9356`, request ID `54ddbbdb-477c-4a24-8c3d-bd078a206c8f`) returned `answerId=allergy-clarification` and routine urgency for “anaphylactic reaction + throat swelling.”
- `/private/tmp/chat-a3.body` (SHA-256 `bd19c7ad66106d175e48f7f37aaf2a730811fdde5cec5e28447dfbf62815ff2e`, request ID `031413d3-cad4-4617-8f0b-6a64780d8bef`) returned the code-authored emergency response, 119 action, and `drugs=[]` for a matching chest-pain expression.
- Frozen candidates `R01-CAN-020` and `R01-CAN-029` cover the two source gaps, and Round-06 recurrence parts repeatedly preserve them.

### Counterevidence and proof gaps

- The positive chest-pain probe and controller ordering are material counterevidence to a general “triage runs after the model” claim.
- The missing-category proof is category-specific; it must not be generalized to every emergency expression.
- The model-emergency-plus-drugs path needs a controlled provider response to establish an actual final unsafe combination.
- Round-06 also promoted a distinct false-positive/context cluster. Its deterministic behavior is proven, but the target documents a deliberate false-positive preference; clinical/product review must decide its truth table and reportability.

### Replacement language

Replace draft C-2 with:

> **판정: 순서 우회는 ABSENT; 응급 카테고리 누락은 PRESENT — P0; 모델 emergency+drug 결합은 구조적으로 PRESENT이며 동적 영향은 중앙 검증 대기.** `ChatProxyController.java:84-99`은 현재 규칙에 매칭되는 입력을 모델보다 먼저 단락하며, chest-pain probe도 119와 `drugs=[]`를 반환했다. 반면 `EmergencyTriage.java:29-54`가 severe allergy/anaphylaxis·seizure·poisoning/overdose·head injury를 포함하지 않아 실제 anaphylaxis 입력이 routine 흐름으로 떨어졌다. 이는 §2-4의 P0 위반이다. 별도로 `AnswerValidator.java:101-107`은 모델이 `emergency`를 선택했을 때 119 action만 검사하고 `drugs=[]`를 강제하지 않는다. 이 source gap은 존재하지만 실제 unsafe final response는 아직 재현하지 않았으므로 중앙 검증 전에는 조건부 P0로 표기한다.

## C-3. Prompt/context/other-user leakage

### Verdict

- **PRESENT:** one normal response exposed internal response-schema field names in `clarifyingQuestions`.
- **ABSENT:** the direct exfiltration probe did not return the full prompt/context; no application path was found that mixes another user's transcript into the current model request; the observed schema names are repository-public and do not establish a meaningful confidentiality boundary.
- **UNCERTAIN:** full provider/model behavior, retention, and tenant isolation cannot be established from this repository or the single refusal probe.
- **Severity:** no P0/P1. The draft's “observed internal schema key leak is P1” is unsupported and conflicts with the Round-06 suppression decision.

### Proof

- `/private/tmp/chat-leak.body` (SHA-256 `89de7f7afdcebc43b26cb8b26b13ecd07a015424279fc210ca5edcf2756f7593`, request ID `36ca90e7-6f96-4221-8b56-eb4db66a0fac`) refused the direct request to disclose the prompt/context.
- `/private/tmp/chat-a4.body` (SHA-256 `757d510fe1461ab2deb08f3eef65c331dc293a3a69a56861a6bdfaf74c24ba8f`, request ID `f91a6d67-4478-410b-a1dc-df52b9ab6cb3`) included schema names such as `dataStatus`, `drugs`, `guidance`, `schemaVersion`, `sourceRefs`, `uiActions`, and `urgency` in a user-visible question.
- `backend/src/main/java/com/mermaid/chat/AnswerValidator.java:32-34,141-204` has markup checks but no prompt/context repetition detector.
- `artifacts/deep_discovery/round-06/worker-05/repository_coverage_ledger.jsonl:27` (`coverage-027`) suppresses the confidentiality hypothesis: the prompt is repository-public, government context is request-scoped, and no secret or other-user data enters the inspected context. It gives an explicit reopen condition for secrets, private profiles, or other-user context.

### Counterevidence and proof gaps

- A single model refusal does not prove universal absence; it does prove that this exact probe did not produce the claimed leak.
- Field names alone may be undesirable output quality, but they are not confidential data in this public repository.
- No provider-side retention policy or tenant behavior was observed. That uncertainty must not be converted into a positive leak finding.

### Replacement language

Replace draft C-3 with:

> **판정: schema-key 노출은 PRESENT(품질/제어 공백), P0/P1 confidentiality 누출은 현재 증거상 ABSENT, provider 전체 동작은 UNCERTAIN.** 직접 prompt/context 탈취 probe는 거부됐고, 앱이 다른 사용자 transcript를 합치는 경로도 찾지 못했다. 한 정상 응답의 `clarifyingQuestions`에 내부 schema field 이름이 노출됐으며 validator에는 반복 탐지 gate가 없다. 그러나 이 이름과 prompt는 공개 저장소 정보이고 현재 context에 secret·private profile·타 사용자 데이터가 들어간다는 증거가 없어 Round-06 `coverage-027`은 보안 누출 가설을 suppress했다. 따라서 P1은 제거한다. secret, private profile, 또는 다른 사용자 context가 모델 메시지에 들어가면 이 판정을 다시 연다.

## C-4. Transcript and PII logging/persistence

### Verdict

- **ABSENT:** normal, well-formed chat transcripts are tab-scoped in `sessionStorage`; no backend chat-transcript repository was found; opt-in allergy memory is dropped on read/write while consent is off.
- **PRESENT — P1:** derived medicine/product terms and precise facility-query coordinates are written to persistent application logs.
- **PROVISIONAL / pending centralized validation:** Round-06 promoted a separate malformed outer JSON path in which attacker-supplied health-text fragments can enter the catch-all Throwable log. Therefore the draft's global “raw transcript persistence is absent” statement is too broad.
- **Conditional severity for the provisional path:** if central validation adopts it as consultation-text persistence, it is **P0** under §2-5. Round-06 promotion itself did not assign severity.

### Proof

- `frontend/src/lib/storage.ts:72-139` uses `sessionStorage` for chat/session state.
- `frontend/src/lib/storage.ts:202-234` enforces allergy-memory opt-in and removes the list when consent is off.
- `backend/src/main/java/com/mermaid/chat/DrugContextRetriever.java:155-180` logs exact extracted ingredient/product values and suppressed model proposals.
- `/private/tmp/mermaid-diag-chat500-f68.log` (SHA-256 `8ba49a755aca4317e513eefc990b830e7d31fa2729870eb50205929c1b92cfcb`) contains `terms=[Acetaminophen]`.
- `backend/src/main/java/com/mermaid/facility/PharmacyApiClient.java:97-105` and the corresponding hospital error path include exact coordinates in exceptions; `backend/src/main/java/com/mermaid/common/GlobalExceptionHandler.java:62-65` logs the exception.
- `/private/tmp/mermaid-upstream-secret-0w9zwK/backend-18377-live.log` (SHA-256 `4123e3e253916f1f0de03fe11bc723348f411de149fd64f63c40086c5ed29157`) contains `37.5665,126.978`.
- Frozen candidates `R01-CAN-069`, `R01-CAN-070`, and `R01-CAN-071` preserve these logging instances; Round-06 recurrences retain their source-specific boundaries.
- `R06-CAN-003` in `artifacts/deep_merge/round-06_semantic_final_review.md:59-75` traces malformed JSON through the missing `HttpMessageNotReadableException` classification to the full Throwable logger.

### Counterevidence and proof gaps

- `artifacts/deep_discovery/round-06/worker-05/repository_coverage_ledger.jsonl:28` suppresses a general persistent-transcript hypothesis for the normal path because transcript and opt-in controls work as designed.
- Generic client error responses do not expose the logged values; the issue is server-side retention/access, not response disclosure.
- The malformed-body candidate has discovery-level source/framework support, but the requested finalization boundary requires it to remain provisional until central validation.

### Replacement language

Replace draft C-4's verdict and first three bullets with:

> **판정: 정상 well-formed transcript 지속 저장은 ABSENT; 파생 약물 term과 정밀 좌표 로그는 PRESENT — P1; malformed outer JSON의 건강 텍스트 로그는 Round-06 provisional candidate다.** 정상 frontend chat은 `sessionStorage`에만 있고 backend transcript repository도 찾지 못했으며 allergy memory는 consent off에서 read/write 모두 비운다. 그러나 `DrugContextRetriever`가 실제 term 값을 INFO에 남기고 facility client/전역 handler 조합이 정밀 좌표를 남긴 동적 증거가 있다. 또한 Round-06 `R06-CAN-003`은 malformed JSON token의 건강 텍스트가 catch-all Throwable log에 도달할 수 있음을 별도 승격했다. 따라서 “원문 transcript 저장은 ABSENT”는 정상 well-formed 경로로 한정해야 한다. 이 malformed 경로는 중앙 검증 전에는 provisional이며, 검증되면 §2-5에 따라 P0다.

The existing value-free logging remediation remains appropriate.

## C-5. Secrets and error verbosity

### Verdict

- **PRESENT:** the Vite secret-name denylist does not match benign-looking secret names such as `VITE_LLM_API_KEY` or `VITE_DATA_GO_KR_SERVICE_KEY`.
- **ABSENT:** no actual secret-bearing `VITE_*` variable or matching server-secret literal was found in the inspected target bundle; the reproduced 500/503 client bodies were generic; the tested Spring request-exception path did not log the query-string service key.
- **Severity:** no current P0/P1. A real secret configured behind any accepted `VITE_*` name would be **P0** under §2-7. The control gap should be fixed, but “P0 structural bypass” overstates the observed target state.

### Proof

- `frontend/vite.config.ts:18-31` blocks only names matching `SECRET|PASSWORD|PASSWD|PRIVATE_KEY|TOKEN|CREDENTIAL`, leaving common `API_KEY`/`SERVICE_KEY` forms uncovered.
- The independent name check reported `blocked=false` for `VITE_LLM_API_KEY` and `VITE_DATA_GO_KR_SERVICE_KEY`.
- `/private/tmp/mermaid-chat500-response.json` (SHA-256 `2bfe0a36ee3a5f0d50ff2dc4104e8b59704341c5113b4defc153848aa03578d7`, request ID `cb0e83b0-2805-4517-a221-121748607b92`) contains a generic failure response rather than stack/class/URL/query/secret detail.
- Fake-upstream request `77f02d20-5976-4ce2-af3b-b7331cf722ab` produced generic client body `/private/tmp/mermaid-upstream-secret-0w9zwK/client-body.json` (SHA-256 `6df360a5ada1b941b6af97033ba2f5b2e3b97831a8ffbd6707e1f839961cb306`). The fake upstream confirmed that the request carried the expected key without the key appearing in the client response.
- Frozen candidates `R01-CAN-014`/`R01-CAN-015` preserve the discovery hypotheses, but they remain pending centralized validation.

### Counterevidence and proof gaps

- `artifacts/deep_discovery/round-06/worker-05/repository_coverage_ledger.jsonl:24` (`coverage-024`) suppresses a current target leak: only the public client ID is `VITE_*`, and gitleaks/build controls are present. It reopens specifically for a secret with a benign-looking `VITE_*` name or a literal secret.
- `coverage-029` and Round-06 Parts 3/4/14 record Spring 6.2.19 counterevidence: the inspected response-exception message renders the request path without the query string containing `serviceKey`.
- The denylist gap remains real, but it is a latent configuration hazard, not evidence that a secret currently ships.

### Replacement language

Replace draft C-5 with:

> **판정: Vite guard coverage gap은 PRESENT; 현재 bundle·client error·검사한 server log에서 실제 secret 누출은 ABSENT; 최종 P0/P1은 없음.** `vite.config.ts:18-31`의 denylist는 `VITE_LLM_API_KEY`와 `VITE_DATA_GO_KR_SERVICE_KEY` 같은 이름을 잡지 못하므로 positive allowlist로 바꾸는 것이 맞다. 그러나 immutable target에는 secret-bearing `VITE_*`가 확인되지 않았고 현재 bundle 및 재현 500/503 응답에서도 secret이 새지 않았다. 따라서 “P0 구조적 우회”는 제거한다. 향후 실제 secret이 허용된 `VITE_*` 이름 뒤에 배치되면 그 시점에는 §2-7의 P0다.

## C-6. Fail-open / fail-closed symmetry

### Verdict

**PRESENT overall — P0, but only on the demonstrated bases below.** The draft correctly identifies a cluster of asymmetric controls, but it must not present every bullet as equally proved.

| Subclaim | Status | Tier basis |
|---|---|---|
| Prose validator lacks diagnosis/unretrieved-drug/`safe`/`cure` semantics | **PRESENT** control gap; actual unsafe prose result **UNCERTAIN** | Conditional **P0** if a paired provider probe passes harmful §2-1/§2-2 content. |
| “ibuprofen gives me hives” does not declare an allergy | **PRESENT** | **P0** under §2-2 because SA-08 does not activate for a direct reaction statement. |
| Hybrid fallback returns unrelated ibuprofen and stamps it live | **PRESENT**, dynamically reproduced | **P0** under §2-9: query/result binding and server-owned provenance are false. |
| Model `answerId` controls frontend allergy-clarification state | Structural path **PRESENT**; final cutoff-loss impact **UNCERTAIN** | Conditional **P0** if it bypasses §2-2 handling in a controlled end-to-end probe. |
| Model emergency output can retain drug cards | Structural validator gap **PRESENT**; unsafe final output **UNCERTAIN** | Conditional **P0** under §2-4; tracked with C-2. |

### Proof

- `backend/src/main/java/com/mermaid/chat/AnswerValidator.java:101-148,176-204` scans formatting/markup and selected structure, but has no semantic rule for diagnosis, unretrieved medicine claims, `safe`, or `cure` in general prose.
- `backend/src/main/java/com/mermaid/chat/AllergyDeclaration.java:29-56` recognizes a limited declaration vocabulary and explicitly leaves “ibuprofen gives me hives” unmatched. The existing test preserves `false`, so this is deterministic behavior, not inference.
- `backend/src/main/java/com/mermaid/drug/DrugPermissionApiClient.java:51-75,100-117` uses the fixed `permission_ibuprofen.json` fallback for any failed ingredient query.
- `backend/src/main/java/com/mermaid/drug/DrugService.java:116-124` does not bind fallback product identity to the query; `:355-362` assigns non-fixture app mode as live provenance.
- `/private/tmp/hybrid-prov.body` (SHA-256 `52ecdf75b1354f62cc12855e6abec7a711ce7a8e73be17ebdf8a2d2e332d98e1`, request ID `6118f5c6-30e0-4fcf-b75d-ae407311f4b5`) returned three ibuprofen products marked live for an acetaminophen ingredient query.
- `backend/src/main/java/com/mermaid/chat/SystemPromptProvider.java:49-52` permits short model-authored `answerId`; `StructuredOutputFallback.java:89-104` preserves it; `frontend/src/components/ChatScreen.tsx:244-256` treats `allergy-clarification` as a safety/UI state.
- The canonical and Round-06 recurrence evidence preserves the corresponding roots, including `R01-CAN-018`, `R01-CAN-020`, `R01-CAN-028`, `R01-CAN-034`, and the hybrid/provenance family (`R01-CAN-105`, `R01-CAN-112`, `R01-CAN-114`).

### Counterevidence and proof gaps

- Malformed model JSON is rejected.
- Product/source/ingredient cards that do not match the current retrieval are rejected as a whole on the inspected normal path.
- A recognized allergy declaration short-circuits before model extraction.
- These working controls do not cure the reaction-only declaration miss or the hybrid query/provenance mismatch, but they do prevent a claim that every boundary fails open.
- No paired provider probe in the evidence bundle establishes harmful prose, model-selected `answerId` cutoff loss, or model-emergency-plus-drugs as a final response. Those impacts remain conditional.

### Replacement language

Replace draft C-6's verdict and numbered list introduction with:

> **판정: PRESENT — P0, 단 P0의 직접 근거는 두 가지로 한정한다.** 첫째, `AllergyDeclaration`이 “ibuprofen gives me hives” 같은 직접 반응 진술을 놓쳐 SA-08이 켜지지 않는 것은 §2-2 위반이다. 둘째, hybrid fallback이 acetaminophen 질의에 ibuprofen fixture를 반환하고 실제 origin과 무관하게 live로 표시한 동적 재현은 §2-9 위반이다. Prose 의미 gate 부재, 모델 `answerId`의 frontend 상태 전이, 모델 emergency의 drug-card 허용도 구조적으로 존재하지만 실제 안전 출력 영향은 아직 중앙 검증되지 않았으므로 각각 조건부 P0로 분리한다. malformed JSON 거절, 현재 turn card grounding, 인식된 allergy 선단락은 작동하는 반대 경계로 유지한다.

The existing server-ownership/query-binding remediation direction is supported, provided it is mapped to the exact subclaim rather than used as proof of the unvalidated impacts.

## C-7. Public-API data injection

### Verdict

- **PRESENT:** government/manufacturer narrative fields are serialized into a context block that is sent as a second privileged `system` message.
- **ABSENT:** the inspected React and Naver-marker rendering paths do not provide an app-authored DOM-XSS sink for these strings.
- **UNCERTAIN:** no evidence demonstrates attacker control of a live upstream field at the required strength, successful instruction following by the configured provider/model, or a resulting unsafe/leaking answer.
- **Severity:** no final P0/P1. If central validation proves that malicious upstream content causes §2 medical-safety output, the result is **P0**. The current source-local candidate remains deferred.

### Proof

- `backend/src/main/java/com/mermaid/chat/DrugContextRetriever.java:377-435` serializes government/manufacturer narrative content into JSON context.
- `backend/src/main/java/com/mermaid/chat/DrugContextRetriever.java:444-464` exposes the serialized context for prompt construction.
- `backend/src/main/java/com/mermaid/chat/ChatProxyService.java:191-208` inserts it as an additional `system` message rather than a lower-authority data/tool message.
- React drug/facility components render external strings as text rather than with `dangerouslySetInnerHTML`.
- `frontend/src/components/FacilityMap.tsx:119-164` escapes `& < > " '` in marker HTML, and the hostile `<img onerror>` test verifies that no image element is created.
- Frozen candidate `R01-CAN-021` tracks the prompt-injection hypothesis. Round-06 Part 13 `R06W06-CAND-008` recurs to it but keeps the source-local disposition deferred.

### Counterevidence and proof gaps

- The main system prompt precedes the context; JSON delimiters separate records; server-side grounding controls product/source/allergy/provenance fields. These controls do not lower the message role, but they are material counterevidence to guaranteed exploitation.
- No live-government-data mutation or controlled malicious fixture/provider experiment establishes a successful instruction or unsafe output.
- The XSS checks prove only the inspected browser sinks; they do not prove LLM prompt safety.

### Replacement language

Replace draft C-7 with:

> **판정: privileged `system` context 승격은 PRESENT; 검사한 앱 DOM XSS는 ABSENT; 실제 prompt-injection 성공과 안전 영향은 UNCERTAIN. 최종 P0/P1 미확정.** `DrugContextRetriever.java:377-435,444-464`가 공공 API narrative를 context로 만들고 `ChatProxyService.java:191-208`이 두 번째 `system` 메시지로 넣는 구조는 확인됐다. 반면 React text rendering과 marker `escapeHtml()`은 검사한 DOM sink를 닫고, 카드 grounding과 JSON delimiters도 일부 결과를 제한한다. 공격자가 필요한 upstream 값을 제어하고 provider가 명령을 따르며 §2 안전 출력이 훼손된다는 동적 증거는 없다. 따라서 `R01-CAN-021`의 deferred candidate로 유지하고 중앙 검증 전에는 P0로 확정하지 않는다. 검증이 실제 §2 영향을 재현하면 P0다.

## Round-06 promoted clusters: provisional only

The following three clusters must appear in finalization material, but only as **provisional, pending centralized validation**. Their Round-06 ordering is first semantic-part occurrence, explicitly not severity order.

| Provisional cluster | Discovery proof | Counterevidence / required gate | Severity treatment |
|---|---|---|---|
| `R06-CAN-001` — Negated or non-current red-flag text can lock a conversation into emergency-only responses | `EmergencyTriage.java:36-67` uses context-free lexical matches across concatenated user turns; controller short-circuits before ordinary care; worker test `negatedRedFlagStillTriggersEmergencyTriage` passed. Source member `R06-W03-009`, Part 05 row 9 (`3d553756…`). | The code deliberately prefers false positives. Qualified clinical/product review must approve the context truth table and preserve positive/mixed-clause escalation. | **No final tier.** Provisional pending clinical/product review and centralized validation. |
| `R06-CAN-002` — Unreviewed in-code form qualifiers collapse into exact blocking identity | `IngredientNormalizer.java:62-92,182-207,245-255` strips `FORM_QUALIFIERS`; `AllergyChecker.java:50-68` can turn resulting `EXACT` into server-authored `BLOCKED`; worker probe demonstrates “Acetaminophen Granules” → exact/block. Source member `R06-W03-014`, Part 06 row 4 (`abc58798…`). | The probe does not establish that any current qualifier is clinically wrong. Every provider-side and user-side qualifier needs qualified human review. | **No final tier.** Provisional pending qualified clinical review and centralized validation. |
| `R06-CAN-003` — Outer malformed chat JSON reaches the catch-all logger with attacker health text | `GlobalExceptionHandler.java:79-89` lacks a sanitized `HttpMessageNotReadableException` branch; `:99-102` logs the full Throwable; Spring/Jackson parser messages can quote the attacker token. Source member `R06W05-C003`, Part 11 row 3 (`22e0e528…`). | Client response remains generic, which limits response disclosure but not log persistence. Round-06 says no discovery-scope proof gap, but centralized validation has not run. | **No final tier yet.** If centrally accepted as consultation-text persistence, §2-5 makes it **P0**. |

Authoritative receipts: `artifacts/deep_merge/round-06_novel_residue_audit_a.md:12-38` and `artifacts/deep_merge/round-06_semantic_final_review.md:20-75`. The latter's `FINAL_SEMANTIC_AUTHORITY` label is discovery-reconciliation authority, not central validation authority.

## Global replacement for the Track C preamble

Insert this paragraph immediately below the Track C heading before C-1:

> **검증 경계.** 아래 판정은 immutable target `654f906e00e81648d1482210b6a9171747dddd75`의 source-local 동작과 명시된 probe를 감사한 결과다. Round-06은 discovery reconciliation까지 완료했지만 centralized validation과 centralized attack-path analysis는 실행되지 않았다. 따라서 `PRESENT`는 source/control/sink 또는 재현된 동작의 존재를 뜻할 뿐 exploitability·최종 reportability·severity 확정을 자동으로 뜻하지 않는다. 각 항목은 `PRESENT`/`ABSENT`/`UNCERTAIN`을 분리하며, 조건부 P0/P1은 중앙 검증 결과가 해당 §2 invariant의 실제 약화를 입증할 때만 승격한다. Round-06의 세 promoted cluster도 모두 provisional이며 pending centralized validation이다.

## Finalization requirements

Before the diagnostic report can be treated as final:

1. Replace C-1, C-3, C-5, and C-7 categorical P0/P1 verdicts with the language above.
2. Split C-2 into absent ordering bypass, present P0 category omission, and conditional model-emergency-plus-drugs impact.
3. Narrow C-4's transcript-absence claim to normal well-formed paths and add provisional `R06-CAN-003`.
4. Retain C-6's P0 only on the directly supported allergy and hybrid/provenance bases; label the prose/`answerId`/model-emergency impacts conditional.
5. Include all three Round-06 promoted clusters as provisional pending centralized validation, with no invented final severity.
6. Do not describe discovery-canonical or Round-06 semantic promotion status as centralized validation.

## Probe integrity receipts

| Evidence | SHA-256 |
|---|---|
| `/private/tmp/mermaid-role-proof.cDxd35/fake-openai.log` | `edff3bbcfcdfac07f874efd352b85c627c0201f3814372e762e7cb19640459c8` |
| `/private/tmp/anaphylaxis.body` | `0ebea579cc64b1715e7d3f0487e1de1287c4fdc16baf5570b6eafec561ca9356` |
| `/private/tmp/chat-a3.body` | `bd19c7ad66106d175e48f7f37aaf2a730811fdde5cec5e28447dfbf62815ff2e` |
| `/private/tmp/chat-leak.body` | `89de7f7afdcebc43b26cb8b26b13ecd07a015424279fc210ca5edcf2756f7593` |
| `/private/tmp/chat-a4.body` | `757d510fe1461ab2deb08f3eef65c331dc293a3a69a56861a6bdfaf74c24ba8f` |
| `/private/tmp/mermaid-diag-chat500-f68.log` | `8ba49a755aca4317e513eefc990b830e7d31fa2729870eb50205929c1b92cfcb` |
| `/private/tmp/mermaid-upstream-secret-0w9zwK/backend-18377-live.log` | `4123e3e253916f1f0de03fe11bc723348f411de149fd64f63c40086c5ed29157` |
| `/private/tmp/mermaid-chat500-response.json` | `2bfe0a36ee3a5f0d50ff2dc4104e8b59704341c5113b4defc153848aa03578d7` |
| `/private/tmp/mermaid-upstream-secret-0w9zwK/client-body.json` | `6df360a5ada1b941b6af97033ba2f5b2e3b97831a8ffbd6707e1f839961cb306` |
| `/private/tmp/hybrid-prov.body` | `52ecdf75b1354f62cc12855e6abec7a711ce7a8e73be17ebdf8a2d2e332d98e1` |

