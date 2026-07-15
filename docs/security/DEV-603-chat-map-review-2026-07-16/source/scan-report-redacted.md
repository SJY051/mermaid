# Round 6 1차 마감 — 채팅 거절·지도 공백 진단 및 적대적 보안 검토

> **상태:** Round 0~6 discovery, 188개 후보의 중앙 검증, 그중 eligible 123개의 attack-path 의미 판정과 독립 재감사를 반영한 1차 마감본이다. 사용자가 정한 deadline cap에 따라 Round 7 신규 탐색은 시작하지 않았다. 중앙 검증과 attack-path의 의미 결과는 동결됐지만, 외부 tracked dirty 상태와 `R05-CAN-004` provenance correction hold 때문에 receipt를 canonical ledger에 물리적으로 채택하지 않았다.

> **스냅샷 경계:** 이 문서군은 아래 기준 tree에 대해 메인테이너의 로컬 clone에서 수행한 특정 시점의 보안 검사 기록이다. 현재 `main`, 배포된 live 서비스, 자격 증명·quota, 외부 API의 현재 상태를 절대적으로 판정하지 않으며 현재 release/merge gate 자체도 아니다. 수정이나 현재 배포 판단에 사용하기 전 해당 target과 환경에서 각 finding을 다시 검증해야 한다.

- 기준 소스 리비전: `654f906e00e81648d1482210b6a9171747dddd75`
- 동일 트리의 main 도달 가능 리비전: `f4a2b6de89f5e4fa4ef5a81e5dafd54f8255367b`
- 기준 트리: `a14388f597c0c2a17e0dbcfc2d951a390c877214`
- 조사 기간: 2026-07-15~16 KST
- 범위: 진단과 적대적 보안 검토만 수행했다. 코드·설정·validator·grounding 동작은 변경하지 않았다.
- Round 6 종단 상태: `capped_by_user_deadline_after_round_06`
- 의미 포화: **입증되지 않음** (`saturation_proven=false`)
- canonical 후보: 188개, SHA-256 `274fd61156b7ae3ebeadb1c905258fbe001a5f81380891b9d2491b44652477b8`
- Round 6 authority manifest SHA-256: `6d040b4494fd6e4c2581d98cfedbf8287650e7a70206b76ac0849bb8a10b5e88`
- Round 6 terminal-state SHA-256: `01ce318541a4f770382088cfebd01f947d7313ac1e7a186920599d1f6620d9d6`

핵심 감사 근거:

- [Track A 최종 감사](/private/var/folders/df/8mq60fls7636g_3_n6wrmlf00000gn/T/codex-security-scans-4bFfeC/mermaid/654f906e00e81648d1482210b6a9171747dddd75_20260715T030819Z___0n3o4b/artifacts/finalization/track_a_final_audit.md)
- [Track B 최종 감사](/private/var/folders/df/8mq60fls7636g_3_n6wrmlf00000gn/T/codex-security-scans-4bFfeC/mermaid/654f906e00e81648d1482210b6a9171747dddd75_20260715T030819Z___0n3o4b/artifacts/finalization/track_b_final_audit.md)
- [Track C source/probe 감사](/private/var/folders/df/8mq60fls7636g_3_n6wrmlf00000gn/T/codex-security-scans-4bFfeC/mermaid/654f906e00e81648d1482210b6a9171747dddd75_20260715T030819Z___0n3o4b/artifacts/finalization/track_c_final_audit.md)
- [중앙 검증 batch 1](/private/var/folders/df/8mq60fls7636g_3_n6wrmlf00000gn/T/codex-security-scans-4bFfeC/mermaid/654f906e00e81648d1482210b6a9171747dddd75_20260715T030819Z___0n3o4b/artifacts/central_validation_round06/batch-01/report.md), [batch 2](/private/var/folders/df/8mq60fls7636g_3_n6wrmlf00000gn/T/codex-security-scans-4bFfeC/mermaid/654f906e00e81648d1482210b6a9171747dddd75_20260715T030819Z___0n3o4b/artifacts/central_validation_round06/batch-02/report.md), [batch 3](/private/var/folders/df/8mq60fls7636g_3_n6wrmlf00000gn/T/codex-security-scans-4bFfeC/mermaid/654f906e00e81648d1482210b6a9171747dddd75_20260715T030819Z___0n3o4b/artifacts/central_validation_round06/batch-03/report.md), [batch 4](/private/var/folders/df/8mq60fls7636g_3_n6wrmlf00000gn/T/codex-security-scans-4bFfeC/mermaid/654f906e00e81648d1482210b6a9171747dddd75_20260715T030819Z___0n3o4b/artifacts/central_validation_round06/batch-04/report.md), [batch 5](/private/var/folders/df/8mq60fls7636g_3_n6wrmlf00000gn/T/codex-security-scans-4bFfeC/mermaid/654f906e00e81648d1482210b6a9171747dddd75_20260715T030819Z___0n3o4b/artifacts/central_validation_round06/batch-05/report.md)
- [Evidence-backed hardening report](/private/var/folders/df/8mq60fls7636g_3_n6wrmlf00000gn/T/codex-security-scans-4bFfeC/mermaid/654f906e00e81648d1482210b6a9171747dddd75_20260715T030819Z___0n3o4b/hardening/hardening.md), SHA-256 `478fce208885565f5994bde9b09f3e651a0881991ebc812b2dd946b5d40fe78d`
- [Round 6 receipt adoption hold](/private/var/folders/df/8mq60fls7636g_3_n6wrmlf00000gn/T/codex-security-scans-4bFfeC/mermaid/654f906e00e81648d1482210b6a9171747dddd75_20260715T030819Z___0n3o4b/artifacts/finalization/round06_adoption_hold.md), SHA-256 `deb6b5c019f427c093f281ee019d30ec00d2789512e3af68039bcd6ed555c83c`
- [`R05-CAN-004` provenance correction hold](/private/var/folders/df/8mq60fls7636g_3_n6wrmlf00000gn/T/codex-security-scans-4bFfeC/mermaid/654f906e00e81648d1482210b6a9171747dddd75_20260715T030819Z___0n3o4b/artifacts/finalization/r05_can_004_provenance_correction_hold.md), SHA-256 `96eaa1fffea17d2389417b2ffbde922f901344320e859ffe7837f9e5d0e5202a`

## 최우선: 중앙 검증이 채택한 reportable P0 33개

AGENTS.md Review guidelines에 따라 §2 invariant 우회는 기능 버그보다 먼저 보고한다. 아래 33개는 188개 canonical 후보를 독립 검증한 결과 `reportable/P0`로 채택된 행이다.

| ID | 중앙 검증 제목 |
|---|---|
| `R01-CAN-001` | Composite lexical command-parser bypasses |
| `R01-CAN-005` | Composite Git workflow-policy gaps |
| `R01-CAN-008` | Codex command guard lets no-verify commits bypass the secret pre-commit hook |
| `R01-CAN-018` | Answer-level model prose lacks semantic medical grounding |
| `R01-CAN-019` | Outer drug-card translations are not semantically bound to retrieved facts |
| `R01-CAN-020` | Emergency answers can retain medication cards |
| `R01-CAN-021` | Government narrative is elevated into a privileged system message |
| `R01-CAN-027` | Client-authored assistant history bypasses safety screening |
| `R01-CAN-028` | Reaction-only allergy declarations bypass the fail-closed gate |
| `R01-CAN-029` | Acknowledged emergency categories bypass deterministic triage |
| `R01-CAN-030` | Emergency regexes fail on punctuation and whitespace variants |
| `R01-CAN-034` | Model answerId can invoke a server-only allergy clarification workflow |
| `R01-CAN-038` | Allergen option refresh silently deletes prior safety state |
| `R01-CAN-046` | Drug search silently drops exclusions it cannot normalize |
| `R01-CAN-069` | Accepted health-related search terms are logged |
| `R01-CAN-094` | Malformed hospital hours are collapsed to CLOSED instead of UNKNOWN |
| `R01-CAN-095` | Malformed pharmacy weekly hours are collapsed to CLOSED instead of UNKNOWN |
| `R01-CAN-098` | Cached pharmacy facts are restamped as freshly retrieved |
| `R01-CAN-105` | Composite outer hybrid fallback is stamped live and current |
| `R01-CAN-109` | Hybrid permission fallback binds a fixed fixture product to an arbitrary requested ID |
| `R01-CAN-110` | Hybrid EasyDrug fallback attaches a fixed product narrative to an arbitrary drug |
| `R01-CAN-113` | Outer DUR fallback warnings are not bound to the requested medicine |
| `R01-CAN-125` | Concurrent opt-out and allergy add can retain data after consent withdrawal |
| `R01-CAN-126` | A concurrent country update can stale-write allergy consent back to enabled after opt-out |
| `R01-CAN-127` | Consent migration retains legacy allergy rows under opt-out |
| `R02-CAN-002` | Facility modal hides persistent safety controls |
| `R02-CAN-003` | Explicit null urgency suppresses fail-upward emergency handling |
| `R04-CAN-001` | Nested .env paths bypass the pre-commit filename guard |
| `R04-CAN-002` | Whole-line placeholder suppression hides real credentials |
| `R04-CAN-011` | Chat open-now flow discards unknown-hours facilities |
| `R05-CAN-002` | Raw allergen labels enter server-stamped safety copy |
| `R05-CAN-023` | Cached drug facts are restamped as freshly retrieved |
| `R06-CAN-003` | Outer malformed chat JSON reaches the catch-all logger with attacker health text |

이 목록은 **canonical row 수**이지 33개의 완전히 독립된 exploit root 수가 아니다. 특히 `R01-CAN-001`과 `R01-CAN-005`는 여러 Git/command-policy 자식을 흡수한 composite이고 `R01-CAN-008`, `R04-CAN-001`, `R04-CAN-002`와 remediation이 겹친다. Provenance/fallback과 consent-race family에도 공통 release-boundary가 있다. 따라서 executive root-cause 순위에서는 같은 수정 경계를 묶되, ledger의 33개 receipt는 삭제하거나 이중으로 재분류하지 않는다.

## 중앙 검증 집계

| disposition | P0 | P1 | P2 | P3 | none | 합계 |
|---|---:|---:|---:|---:|---:|---:|
| `reportable` | 33 | 44 | 21 | 3 | 0 | **101** |
| `needs_review` | 9 | 7 | 6 | 0 | 0 | **22** |
| `not_reportable` | 0 | 0 | 0 | 0 | 65 | **65** |
| 합계 | 42 | 51 | 27 | 3 | 65 | **188** |

동결된 다섯 `validation.jsonl`의 연결 SHA-256은 `8b66c623fe1c6c4680e557407bc54f9cd956969b9e231a96218a60a34c983f68`이다. 이 semantic 결과는 완료됐지만 중앙 builder의 canonical receipt write는 clean tracked/staged worktree를 요구한다. 보고 시점의 checkout에는 이 scan과 무관한 시설 작업의 tracked 수정이 존재해 write transaction이 중단됐으며, 이 검토는 gate를 약화하거나 외부 변경을 stash/revert하지 않았다.

## 실행·증거 리비전 행렬

| 용도 | 리비전 / 프로세스 | 모드·주소 | 증거 귀속과 한계 |
|---|---|---|---|
| 보안 source/canonical 감사 | `654f906e…`, tree `a14388f…` | immutable Git object | 모든 Track C source line과 canonical 비교의 권위 기준 |
| 제품 소유자가 사용한 demo backend | PID 8415, 정확한 Git revision 복구 불가 | `DATA_MODE=fixture`, `localhost:8080` | 삭제된 `.claude/worktrees/nifty-bohr-af339a/backend` classpath를 계속 사용. runtime stack line은 이 오래된 binary 기준 |
| 깨끗한 live 비교 backend | 당시 PID 68633, `f68cc399…`; 증거 수집 뒤 종료 | `DATA_MODE=live`, `localhost:18080` | Track A 관련 backend 경로는 target과 byte diff 없음. 일부 raw upstream numeric status는 미보존 |
| 깨끗한 fixture 비교 backend | 당시 PID 39661, `f68cc399…`; 증거 수집 뒤 종료 | `DATA_MODE=fixture`, `localhost:18081` | fixture pharmacy/hospital/ER 직접 응답 근거 |
| 정확한 브라우저 재현 | Vite checkout `f68cc399…` | `http://localhost:5173` → PID 8415 | target 대비 MapScreen 표현과 disabled ER control 차이를 분리해 판정 |
| 보고서 최종 관찰 시 checkout | `4358efec58d7e18c6bdc1615886185f76d606c08`, tree `c76b0b072adf86ecf37a40c097cea108caf2e682` | repository worktree | scan target과 다르고 외부 시설 작업으로 staged·unstaged 상태. source 판정은 이 checkout에 귀속하지 않음 |

제품 소유자가 본 원본 네 chat 응답에는 보존된 response header/HAR가 없다. 따라서 **원본 `request_id`는 네 건 모두 unknown**이다. `RequestIdFilter.java:29-45`는 ID를 MDC와 response/header에 넣지만 `application.yml:60-62`는 MDC 출력 pattern을 설정하지 않으며 실제 default log에도 ID가 없다. Fresh 500의 ID와 stack은 response `Date` UTC와 유일한 KST 초 단위 log event를 맞춘 강한 시간 상관관계이지 stack line 자체의 request-ID join은 아니다. Fresh request JSON도 보존되지 않아 아래 “동등 재현”은 status/path/output을 증명하지만 owner 문구의 byte-exact 재현을 증명하지 않는다. 원본 상담 문구는 AGENTS.md §2-5에 따라 이 공개 archive에서 의미 보존형 라벨로 대체했다.

---

# Track A — chat 네 가지 실패

| owner 화면 | 원본 ID/status | 보존된 동등 재현 또는 matching path | 실제 결정 계층 |
|---|---|---|---|
| A-1 official-data refusal | ID/status unknown | clean same-output HTTP 200 `760f2b10…`; matching fixture trace | 우리 structured-output coercion boundary |
| A-2 full-sentence internal error | ID/status unknown | HTTP 500 `edf86577…` | stale/deleted-worktree linkage error |
| A-3 chest-pain internal error | ID/status unknown | HTTP 500 `73c4b0a5…` | triage 후 emergency DTO linkage error |
| A-4 first government-service error | ID/status unknown | ID-bearing 503 없음; matching fixture trace | local fixture 누락을 upstream outage로 오분류 |
| A-4 retry internal error | ID/status unknown | HTTP 500 `6d5a4f92…` | stale request-extension linkage error |

## A-1. “I could not verify that answer against official data…”

사용자 문구의 관찰된 최종 emitter는 `backend/src/main/java/com/mermaid/chat/ChatProxyController.java:203-210`이다. 같은 문장을 쓰는 validator branch는 `:211-219`다. `StructuredOutputFallback.java:27-29`도 같은 상수를 가지지만 관찰된 coercion exception에서는 `:73-85`가 `local-fallback`을 반환하고 controller가 최종 문장을 만든다.

- 원본 owner ID: **unknown**.
- matching 역사 trace: PID 8415, `DATA_MODE=fixture`, request ID 없음, `/Users/asqi/.gradle/daemon/8.14.5/daemon-80182.out.log:27066-27073`.
- clean same-output control: HTTP 200, `760f2b10-6e11-422b-a644-acc9180c4b58`, `DATA_MODE=live`.
- old-runtime/JFR control: HTTP 200, `ee642f10-2abf-46d6-9c59-6cf76a41f2eb`, `DATA_MODE=fixture`; `/private/tmp/old8080-chat-exceptions.txt`.

단계별 증거는 다음과 같다.

1. Triage는 단락하지 않아 Pass 1a로 갔다.
2. Pass 1a는 `Acetaminophen`, `Ibuprofen` 두 ingredient를 반환했다. Terminal LLM success-class는 확정되지만 정확한 numeric status와 schema-400 retry 여부는 로그에 없어 unknown이다.
3. Pass 1b는 fixture에서 3 drugs를 만들었다. Fixture mode이므로 정부 HTTP 요청과 upstream status 자체가 없다.
4. Pass 2는 8,179자 context로 model reply를 받았다. HTTP error라면 `retrieve()`에서 예외가 되므로 success-class는 확정되지만 numeric status는 unknown이다.
5. 다음 보존 log는 `model_answer_rejected code=COERCION_FAILED`다. 역사 raw model text와 exact coercion exception은 보존되지 않았다.
6. `local-fallback`이 `ground()`와 `AnswerValidator` 전에 반환됐다. Invariant 6, digit/dose grounding, `AnswerValidator`는 이 trace의 결정자가 아니다.

Clean `760f…` control은 Pass 1a non-JSON → 0 terms → Pass 1b skip → Pass 2 → `JsonParseException`/`COERCION_FAILED`였다. Request body가 없어 owner 요청의 exact reproduction이라고 부르지 않는다. Target에서 explicit-none allergy wording은 `AllergyDeclaration.java:38-56`의 negation 미처리 때문에 Pass 1a 전에 allergy clarification으로 빠지며 clean ID `e21ec535-075c-4a40-8c60-c5c574a9dc2d`가 이 별도 경로를 증명한다.

**결론:** 모델은 답했고 우리 structured-output coercion boundary가 `MermAidAnswer`로 수용하지 못했다. 식약처, invariant 6, digit rule, grounding, `AnswerValidator`가 A-1을 직접 거절했다는 가설은 **아니다**. Dose-forbidding prompt가 generation shape에 간접 영향을 주었는지는 provider A/B capture가 없어 uncertain이다. 즉 “최근 hardening 전체가 무관”이 아니라, **지목된 grounding/validator 검사는 관찰된 직접 trigger가 아니다**.

## A-2. “We could not get an answer… Something went wrong on our side.”

- Frontend title: `frontend/src/components/ChatScreen.tsx:84-90`; backend error 연결 `:90-118`.
- Catch-all mapping: `backend/src/main/java/com/mermaid/common/GlobalExceptionHandler.java:99-103`; exact text `:107-124`; HTTP 500/non-retryable `ErrorCode.java:33`; envelope `GlobalExceptionHandler.java:166-179`.
- 원본 owner ID: **unknown**.
- 동등 stale-runtime 재현: HTTP 500, `edf86577-f029-440f-bdcf-bd8bed90d18f`, `DATA_MODE=fixture`; `/private/tmp/track-a2-stale.{headers,body}`.

단계는 triage 미매칭 → request extension parse → Pass 1a 전 allergy declaration 감지 → server-authored clarification materialization이었다. 이때:

```text
NoClassDefFoundError: com/mermaid/chat/AllergyClarification
Caused by: ClassNotFoundException: com.mermaid.chat.AllergyClarification
  at DrugContextRetriever.retrieve(...)
  at ChatProxyController.answer(...)
  at ChatProxyController.completions(...)
```

Pass 1a/1b/2, coercion, grounding, validator, LLM, 정부 HTTP는 전부 미실행이다. Spring이 linkage `Error`를 `ServletException`으로 감싼 뒤 catch-all이 `INTERNAL_ERROR` 500을 만들었다. §11의 `IllegalArgumentException` client-error trap은 아니다. Target의 `GlobalExceptionHandler.java:76-88`도 broad `IllegalArgumentException`을 client error mapping에 넣지 않는다. Clean `e21ec…`는 HTTP 200 clarification을 반환해 stale classpath가 직접 원인임을 보이지만 explicit-none allergy wording의 negation false positive는 별도 기능 결함으로 남는다.

## A-3. Redacted owner emergency prompt → 500

사용자 error emitter는 A-2와 같다.

- 원본 owner ID: **unknown**.
- 동등 stale-runtime 재현: HTTP 500, `73c4b0a5-243f-4aff-8bff-5581a65e8aea`, `DATA_MODE=fixture`; `/private/tmp/track-a3-stale.{headers,body}`.

`EmergencyTriage`는 raw text의 `CHEST_PAIN`을 모델보다 먼저 감지했다. Ordering은 정상 작동했지만 code-authored emergency answer materialization에서 다음 linkage error가 났다.

```text
NoClassDefFoundError: com/mermaid/chat/dto/UiAction$EmergencyPayload
Caused by: ClassNotFoundException: com.mermaid.chat.dto.UiAction$EmergencyPayload
  at EmergencyTriage.emergencyAnswer(...)
  at ChatProxyController.completions(...)
```

Pass 1a/1b/2, coercion, grounding, validator, LLM, 정부 HTTP는 미실행이다. Clean `031413d3-cad4-4617-8f0b-6a64780d8bef`는 HTTP 200, `triage-chest_pain`, emergency 119 action, `drugs=[]`를 반환한다. 이 500의 원인은 model이나 validator가 아니라 stale demo artifact다.

## A-4. 첫 503과 retry 500

### 첫 503

문구 emitter는 `GlobalExceptionHandler.java:62-65`; exact government-service text `:107-110`; HTTP 503/retryable `ErrorCode.java:29-30`; envelope `GlobalExceptionHandler.java:166-179`다.

- 원본 owner ID: **unknown**.
- ID-bearing 503 reproduction: 없음.
- matching trace: PID 8415, `DATA_MODE=fixture`, `/Users/asqi/.gradle/daemon/8.14.5/daemon-80182.out.log:29544-29554`.

Triage 후 Pass 1a는 wrong-shape ingredient 하나를 버리고 2 terms를 유지했다. Pass 1b에서 `DrugPermissionApiClient`가 `FixtureLoader.read`로 local `/fixtures/permission_ibuprofen.json`을 찾지 못했다. 정부 HTTP 요청은 없었고, 따라서 upstream 429/503도 없었다. Pass 2 이후 단계도 미실행이다. `FixtureLoader.java:38-50`가 local resource 실패를 `PublicApiException`으로 만들고 handler가 정부 장애용 `SOURCE_UNAVAILABLE` 503으로 오분류했다. 원본 ID/HAR가 없어 그날의 모든 503으로 일반화하지는 않는다.

### Retry 500

사용자-facing catch-all text와 frontend emitter는 A-2의 `ChatScreen.tsx:84-118`, `GlobalExceptionHandler.java:99-124,166-179`, `ErrorCode.java:33`과 같다.

- 원본 owner ID: **unknown**.
- 동등 stale-runtime 재현: HTTP 500, `6d5a4f92-528a-4a6b-9562-0f0974451544`, `DATA_MODE=fixture`; `/private/tmp/track-a4-stale.{headers,body}`.

Pass 1a 전에 `MermaidRequestExtension.excludedIngredients`의 nested `ParsedList` materialization이 실패했다.

```text
NoClassDefFoundError: com/mermaid/chat/MermaidRequestExtension$ParsedList
Caused by: ClassNotFoundException: com.mermaid.chat.MermaidRequestExtension$ParsedList
  at MermaidRequestExtension.parseList(...)
  at MermaidRequestExtension.excludedIngredients(...)
  at ChatProxyController.answer(...)
```

LLM·정부 HTTP·모든 post-processing은 미도달했고 catch-all이 500을 만들었다. Clean `f91a6d67-4478-410b-a1dc-df52b9ab6cb3`는 HTTP 200이지만 extractor가 0 terms를 내 lookup을 건너뛰었으므로 successful government lookup 증거로 쓰지 않는다.

### Track A 최종 판정

세 재현 500은 stale/deleted-worktree classpath의 `NoClassDefFoundError`/`ClassNotFoundException`이다. 어느 것도 `IllegalArgumentException` trap이 아니다. A-1은 grounding/validator보다 앞선 structured-output coercion, A-4 첫 503은 실제 upstream 장애가 아닌 local fixture 누락의 잘못된 mapping이다. **최근 digit/invariant-6/AnswerValidator hardening이 A-1 legitimate answer를 직접 fail-close했다는 가설은 No**이며, broader answer release boundary에는 별도의 중앙 검증 P0(`R01-CAN-018`, `019`, `020`, `034`)가 존재한다.

---

# Track B — 지도와 시설

## B-1. 정확한 `http://localhost:5173` Naver load

**판정: accepted/rendered. `key rejected`, `never appended`, `late callback`이 아니다.**

[정확한 console/network capture](/private/var/folders/df/8mq60fls7636g_3_n6wrmlf00000gn/T/codex-security-scans-4bFfeC/mermaid/654f906e00e81648d1482210b6a9171747dddd75_20260715T030819Z___0n3o4b/diagnostic_drafts/evidence/naver_5173_console_network_20260716.md)는 다음을 함께 증명한다.

- `maps.js?ncpKeyId=<public-id>&language=en`: HTTP 200. Parameter는 올바른 `ncpKeyId`다.
- `url=http://localhost:5173/` origin-auth: HTTP 200.
- `navermap_authFailure`는 append 전에 function으로 등록됐고 발동하지 않았다. `nav__authFailure`도 발동하지 않았다.
- `window.naver.maps` defined, `mapError=null`, `mapLoading=false`, canvas children 4.
- base/terrain/satellite styles와 tiles가 HTTP 200이고 `/private/tmp/mermaid-map.png`에 `© NAVER Corp.`가 렌더됐다.
- console error 두 건은 facility 500뿐이고 Naver exception이 아니다.

Bad key도 `maps.js` 200/onload/map construction까지 갈 수 있으므로 script 200만으로 판정하지 않았다. **두 auth callback 미발동 + style/tile 성공 + 실제 canvas**가 accepted를 확정한다. Immutable source도 `useNaverMap.ts:48-53` callback 등록 → `:69-85` `ncpKeyId` append → `:110-121` listener 선등록 → `:123-135` `tilesloaded` gate 순서다. Worker의 `:5174` allowlist 문제는 정확한 `:5173` 결과와 무관하다.

## B-2. Browser가 실제 받은 facilities 응답

| Browser request | HTTP | request ID | body |
|---|---:|---|---|
| `type=pharmacy` | 500 | `33bcc094-7d03-4f32-9826-e329efc9dc8a` | `{"error":{"code":"INTERNAL_ERROR","message":"Something went wrong on our side.","retryable":false,"request_id":"33bcc094-7d03-4f32-9826-e329efc9dc8a"}}` |
| `type=hospital` | 500 | `13f9d64a-ba4e-4a32-8a34-aba78a162ba9` | `{"error":{"code":"INTERNAL_ERROR","message":"Something went wrong on our side.","retryable":false,"request_id":"13f9d64a-ba4e-4a32-8a34-aba78a162ba9"}}` |

둘 다 `200 []`가 아니다. Vite `/api`는 PID 8415로 갔고 runtime은 `DATA_MODE=fixture`였다. Controlled repeat(`7a987d59…`, `e2fe6a75…`)와 `/private/tmp/old8080-facility-exceptions.txt`는 다음 working exception을 보존한다.

```text
NoClassDefFoundError: com/mermaid/facility/FacilityService$1
  at FacilityService.findNearby(FacilityService.java:44)  # stale loaded class line
  at FacilityController.nearby(FacilityController.java:39)
Caused by: ClassNotFoundException: com.mermaid.facility.FacilityService$1
```

Exact browser IDs는 log에 ID가 없어 stack과 직접 join되지 않는다. 같은 runtime·endpoint·response shape와 지속 누락된 synthetic class가 강한 causal inference를 제공하고 stack 권위는 repeat/JFR에 있다. Immutable target의 switch는 `FacilityService.java:93-99`다. Provider dispatch 전에 실패했으므로 pharmacy list/detail, HIRA hospital list/detail 어느 upstream operation도 호출되지 않았고 이 browser 500에 429는 없다.

## B-3. clean control, DATA_MODE, upstream 한계

| mode | type | application HTTP | request ID | count | provenance |
|---|---|---:|---|---:|---|
| fixture | pharmacy | 200 | `a2bfc663-5742-489c-af77-73ed3e8179d4` | 3 | all `fixture` |
| fixture | hospital | 200 | `d5848cff-9266-4747-9476-662f27938196` | 2 | all `fixture` |
| live | pharmacy | 200 | `e2ddcd04-7c90-4a82-9b70-d039e7aeb262` | 50 | all `live` |
| live | hospital | 200 | `7b4dea90-7732-458f-9309-8f357c928a5e` | 50 | all `live` |

이는 application status/body다. Adapter가 successful raw upstream code를 log하지 않고 cache가 답할 수 있어 각 data.go.kr operation의 numeric status로 바꿔 쓰지 않는다. Live pharmacy list `getParmacyLcinfoInqire` 실패는 `PublicApiException` → 503이며 `200 []`로 숨기지 않는다. Pharmacy weekly-hours detail 실패는 row를 유지하고 hours unknown으로 낮춘다. Hospital list/detail 실패는 request 전체를 실패시킬 수 있다. Broken browser path는 fixture라 이 구분 이전에 끝났다.

## B-4. pharmacy / hospital / ER

- **Pharmacy:** 구현됨. Fixture 3, live application control 50. Zero pins는 stale runtime 500이며 expected-empty가 아니다.
- **Hospital:** 구현됨. `FacilityType.java:12-14`, `FacilityService.java:95-98`; fixture 2, live control 50. Zero pins는 regression/runtime failure다. `frontend/src/lib/facilities.ts:18-19`의 “hospital은 DEV-203 전까지 501” 주석은 stale claim이다.
- **ER:** backend enum/adapter/dispatch가 없어 expected unsupported다. `type=er`는 HTTP 400, ID `9187988f-072e-4c00-ab37-65a46a327163`, `INVALID_REQUEST`; formal `type=emergency_room`도 HTTP 400, ID `ace6bc42-e1f8-47da-a9d0-7c4d7abfcf45`다. Hospital row의 `emergencyDay`/`emergencyNight`는 별도 ER marker가 아니다.

## B-5. upstream 실패와 빈 결과의 정직성

Main `MapScreen`은 non-2xx를 `facilities.ts:33-37`에서 throw하고 `MapScreen.tsx:133-176`이 `fetchError`를 보존한다. Empty 조건은 `!loadingFacilities && !fetchError && facilities.length===0`(`:244-252`)이고, 실제 browser도 map + error를 보였지 no-results를 보이지 않았다. 성공한 `200 []`만 “No … found”가 된다.

반면 assistant-opened `NearbyFacilities`는 `:59-70`에서 rows를 비우고 loading state 없이 request를 시작하며 `:127-132`에서 pending 중에도 `error==null && rows.length==0`을 no-results로 그린다. 이 false-empty는 중앙 검증 `R04-CAN-011`의 broader unknown-hours exclusion과 함께 care-information truthfulness boundary에서 다뤄야 한다.

Pharmacy/hospital list/detail cache key에는 `DATA_MODE`가 없어 shared Redis에서 fixture/live row가 섞일 수 있다(`PharmacyApiClient.java:72-79,175-176`; `HospitalApiClient.java:72-76`; `HospitalDetailApiClient.java:69-71`). Row provenance는 유지되지만 mode isolation이 없고, 이번 zero-pin의 직접 원인은 아니다.

---

# Track C — 일곱 가지 적대적 보안 검토

## C-1. Client-sent 역할 smuggling (§2-1)

**판정: PRESENT/P0 — client-authored `assistant` 역할 보존(`R01-CAN-027`); ABSENT — 직접 `system`·`developer`·`tool`과 nested-role 전달.**

`ChatProxyService.java:151-163`은 pre-scan용 text를 `user`에서만 모으지만 `:195-227`은 client `assistant` history를 Pass 2에 그대로 보낸다. Probe `1c7336dc-7a3c-4f85-9eab-a9e6020179d6`에서 provider roles가 `system, system, assistant, user, user`였다. 직접 `system`/`developer`/`tool`은 privileged role로 살아남지 않았다. 중앙 검증은 사용자 지시의 명시적 기준, 즉 **client-controlled text가 model role을 steer하면 P0**라는 §2-1 boundary에 따라 assistant preservation 자체를 `R01-CAN-027 reportable/P0`로 채택했다. Fix 방향은 client-authored history를 user data로 낮추거나 server-authored transcript와 cryptographically/structurally 분리하는 것이며 §2 human gate가 필요하다.

## C-2. Emergency triage ordering과 OUT-04 (§2-4)

**판정: ordering bypass ABSENT; category·matcher coverage PRESENT/P0; emergency+drug co-render PRESENT/P0.**

- `ChatProxyController.java:84-99`은 raw newest user text를 모델보다 먼저 triage한다. Chest-pain control `031413d3…`도 119 action과 `drugs=[]`를 반환했다. Retry와 drug lookup도 이 controller ordering 뒤에 있으므로 “LLM call before triage”는 발견되지 않았다.
- `EmergencyTriage.java:29-54`는 acknowledged severe allergy/anaphylaxis·seizure·poisoning/overdose·head injury를 빠뜨린다. 실제 anaphylaxis 입력 `54ddbbdb-477c-4a24-8c3d-bd078a206c8f`가 routine allergy 흐름으로 갔다. `R01-CAN-029`는 reportable/P0이고 punctuation/whitespace variant 누락도 `R01-CAN-030` reportable/P0다.
- `AnswerValidator.java:101-107`은 model-authored `emergency`에 119 action만 요구하고 cards를 비우지 않는다. Sealed controlled-provider ID `71ed3b69-2065-4d4e-831c-204c6a8aff26`은 HTTP 200 응답에 emergency urgency, 119 action, grounded acetaminophen card를 동시에 보존했다. `R01-CAN-020` reportable/P0이며 OUT-04 위반이다.

Ordering 자체의 fix가 아니라, server-owned triage category/truth table과 final emergency-output `drugs=[]` invariant를 각각 red-before/green-after로 고정해야 한다. 두 변경 모두 §2-4/clinical-product human gate가 필요하다.

## C-3. Prompt/context leakage

**판정: schema-key spill PRESENT; 현재 P0/P1 confidentiality leak과 app-side cross-user mixing은 ABSENT; provider retention/tenant handling은 UNCERTAIN.**

Direct exfiltration probe `36ca90e7-6f96-4221-8b56-eb4db66a0fac`는 prompt/context 공개를 거부했다. 반면 `f91a6d67-4478-410b-a1dc-df52b9ab6cb3`의 `clarifyingQuestions`에는 `dataStatus`, `drugs`, `guidance`, `sourceRefs`, `urgency` 같은 schema key가 노출됐고 `AnswerValidator.java:32-34,141-204`에는 repetition detector가 없다. 이 schema/prompt는 공개 저장소 정보이고 inspected context에 secret·private profile·다른 사용자 data가 든 증거는 없다. 따라서 current confidentiality P0/P1로 보고하지 않는다. 외부 provider retention과 tenant isolation은 repository·single probe로 확인할 수 없어 uncertain이며 운영 계약/설정 증거가 필요하다.

## C-4. Transcript와 PII logging/persistence (§2-5)

**판정: 정상 transcript/localStorage persistence ABSENT; health-term과 malformed-body logging PRESENT/P0; device ID PRESENT/P1; 정밀 coordinates PRESENT/P2.**

- Frontend chat은 `storage.ts:72-139`의 `sessionStorage`에만 있고 chat을 `localStorage`에 쓰는 경로와 backend transcript repository는 발견되지 않았다. Allergy memory는 consent off에서 `:202-234`의 read/write가 모두 빈다.
- `DrugContextRetriever.java:155-180`이 accepted medicine/search term 값을 INFO log에 쓴다. 실제 `terms=[Acetaminophen]`가 보존됐고 중앙 검증은 `R01-CAN-069 reportable/P0`로 채택했다.
- Malformed outer JSON은 sanitized `HttpMessageNotReadableException` branch가 없어 `GlobalExceptionHandler.java:99-102`의 full-Throwable logger로 간다. Clean reproduction ID `fc4b4a52-d125-4c3d-8c09-8df64fe74ae2`는 client에 generic 500을 반환했지만 server log lines 1-3,64-65에 synthetic `chest_pain_and_cannot_breathe_R06CAN003` token을 두 번 남겼다. `R06-CAN-003 reportable/P0`다.
- `R01-CAN-059` device identifier logging은 reportable/P1, `R01-CAN-061` precise facility coordinates logging은 reportable/P2다. C-4의 P1/P2는 P0 term/body findings와 구분한다.

Fix 방향은 values 대신 request ID·stage·count·fixed code만 남기는 typed/value-free telemetry와 retention/접근 정책이다. §2-5 human privacy gate가 필요하다.

## C-5. Secrets와 error verbosity (§2-7, §2-8, §11)

**판정: actual VITE secret, inspected bundle secret, client stack/class/URL/service-key leak는 ABSENT; Vite guard coverage gap PRESENT/P2; commit-secret guard bypass family PRESENT/P0.**

`vite.config.ts:18-31` denylist는 `SECRET|PASSWORD|PASSWD|PRIVATE_KEY|TOKEN|CREDENTIAL`만 잡아 `VITE_LLM_API_KEY`, `VITE_DATA_GO_KR_SERVICE_KEY` 같은 API-key 이름을 허용할 수 있다. Immutable target에는 그런 secret-bearing `VITE_*`가 없고 inspected bundle에도 actual secret이 없었다. Generic 500 `cb0e83b0-2805-4517-a221-121748607b92`와 fake-upstream 503 `77f02d20-5976-4ce2-af3b-b7331cf722ab`의 body는 stack, class, upstream URL/query, service key를 반환하지 않았다. 검사한 Spring exception 경로에서는 query-string `serviceKey`가 server log에 남지 않았다. 이는 검사한 경로에 한정된 ABSENT 판정이며 모든 logger/운영 인프라 경로의 일반적 부재를 증명하지 않는다. 이 Vite gap은 `R01-CAN-014 reportable/P2`이고 방향은 browser-public 변수 positive allowlist다.

반면 secret commit boundary에는 P0가 있다. `R01-CAN-001`, `R01-CAN-005` composite와 `R01-CAN-008`, `R04-CAN-001`, `R04-CAN-002`는 alternate command spelling/no-verify/nested `.env`/whole-line placeholder suppression으로 §2-8 마지막 방어선을 우회한다. 실제 secret을 읽거나 commit/push하지 않았지만 control bypass가 deterministic이어서 reportable/P0다. Fix는 shell spelling denylist가 아니라 normalized operation semantics와 path/content-aware secret scanner를 아래 privilege boundary에서 강제하는 것이다.

## C-6. Fail-open / fail-closed symmetry

**판정: unsafe fail-open PRESENT/P0; 동시에 working fail-closed controls도 PRESENT.**

Validated fail-open paths:

- `R01-CAN-018`: final model prose에 diagnosis/curative/dangerous word-form dosing semantic gate가 없다. Sealed ID `59cda66b-eaf7-4422-978d-0224eecc2f65`는 HTTP 200으로 해당 prose를 빈 drug list와 함께 그대로 반환했다 — reportable/P0.
- `R01-CAN-028`: “ibuprofen gives me hives” 같은 reaction-only statement를 `AllergyDeclaration.java:29-56`이 선언으로 인식하지 못해 SA-08을 켜지 않는다 — reportable/P0.
- `R01-CAN-034`: model-authored `answerId=allergy-clarification`이 server-only clarification workflow를 연다 — reportable/P0.
- `R01-CAN-020`: model emergency와 drug card co-render — reportable/P0, ID `71ed…`.
- `R01-CAN-019`, `R01-CAN-098`, `R01-CAN-105`, `R01-CAN-109`, `R01-CAN-110`, `R01-CAN-113`, `R05-CAN-023`: translated facts, cached freshness, hybrid fixtures, requested product identity, DUR warnings의 provenance/binding이 release boundary에서 검증되지 않거나 잘못 stamp된다 — 모두 reportable/P0 family.
- `R01-CAN-038`, `R01-CAN-046`, `R01-CAN-125`, `R01-CAN-126`, `R01-CAN-127`: allergy exclusion/consent state가 refresh, normalization failure, concurrent update, migration에서 삭제·복원될 수 있다 — reportable/P0 family.

Working fail-closed controls도 확인됐다. Malformed model JSON은 `COERCION_FAILED`로 release되지 않고, current-turn drug cards는 retrieved set grounding을 거치며, recognized allergy declaration과 unresolved allergen은 server-owned clarification/short-circuit로 모델 drug proposal을 차단한다. 따라서 fix 방향은 이 controls를 완화하는 것이 아니라 typed answer-release gate에 semantic prose, server-only state ownership, immutable product/source identity, emergency-card exclusion을 추가하는 것이다. §2-1/2-2/2-4/2-9 human gate가 필요하다.

## C-7. Public-API data injection

**판정: DOM XSS ABSENT; upstream narrative의 privileged `system` 승격 PRESENT/P0(`R01-CAN-021`).**

`DrugContextRetriever.java:377-435,444-464`가 government/manufacturer narrative를 JSON context로 만들고 `ChatProxyService.java:191-208`이 두 번째 `system` message로 넣는다. 공개 API string은 untrusted data인데 역할 boundary에서 privileged instruction과 같은 위치를 얻는다. 중앙 검증은 이 trust-boundary violation을 `R01-CAN-021 reportable/P0`로 채택했다.

UI 쪽에서는 React drug/facility fields를 text로 렌더하고 `FacilityMap.tsx:119-164`가 marker HTML의 `& < > " ' `를 escape한다. Hostile `<img onerror>` test도 element 생성을 막아 inspected card/map-popup DOM XSS는 absent다. Fix 방향은 public API data를 structured user/tool-data envelope로 격리하고 system instruction과 role-separated channel에서만 전달하는 것이며 §2 의료-output boundary human gate가 필요하다.

## Round 6 신규 canonical 최종 상태

| canonical | 중앙 검증 | 결론 |
|---|---|---|
| `R06-CAN-001` | `needs_review/P1` | Negated/resolved/quoted/hypothetical red-flag mention의 false-positive는 구조적으로 존재하지만 affirmative/mixed-clause safety를 훼손하지 않는 임상 truth table 승인이 필요하다. |
| `R06-CAN-002` | `needs_review/P0` | In-code `FORM_QUALIFIERS`가 signed synonym metadata 밖에서 `EXACT`→server-authored `BLOCKED`로 이어지는 구조는 있으나, 현재 qualifier의 임상적 오류를 qualified reviewer가 확정해야 한다. |
| `R06-CAN-003` | `reportable/P0` | Clean HTTP reproduction `fc4b…`와 sealed server log가 malformed health token persistence를 증명했다. §2-5 우회다. |

## 검증 provenance correction — `R05-CAN-004`

`R05-CAN-004`는 `needs_review/P0`로 유지한다. Canonical/validation receipt가 “retrieval deduplicates by itemSeq” 근거를 `DrugService.java:236-253`로 보존했지만 immutable target의 실제 dedup은 **`DrugService.java:214-219`**다. `236-253`은 groundable batch 처리와 detail method Javadoc 시작 부분으로 해당 claim을 지지하지 않는다. Frozen canonical SHA를 Round 6 마감 중 임의 변경하지 않았으며, Round 7 provenance-correction 단계에서 row/receipt authority를 재발행해야 한다. 최종 보고의 사실 판단은 correct range `214-219`만 사용한다.

---

# 기능·보안 통합 root-cause 순위와 수정 방향

수정은 수행하지 않았다. Composite/가족형 finding은 remediation boundary 기준으로 묶었고, §2 행은 worker가 독자적으로 바꾸면 안 되는 human judgment gate다.

| 순위 | 묶인 root cause / 영향 | 대표 근거 | 후속 수정 방향 | human gate |
|---:|---|---|---|---|
| 1 | Model→server answer release가 prose 의미, card identity, server-only state, emergency-card 조합을 하나의 typed invariant로 검증하지 못함 | `R01-CAN-018/019/020/034`, ID `59c…`, `71ed…` | 기존 fail-closed를 유지한 typed release gate; semantic/identity/emergency invariants를 server-owned로 추가 | **§2-1/2-4/2-9 필수** |
| 2 | Emergency matcher의 category·syntax coverage가 incomplete | `R01-CAN-029/030`, anaphylaxis `54dd…` | 임상 검토 truth table, positive/mixed/negated/history corpus, deterministic pre-model tests | **§2-4 필수** |
| 3 | Allergy declaration/exclusion/consent state가 reaction grammar·refresh·race·migration에서 fail-open | `R01-CAN-028/038/046/125/126/127` | Server-owned versioned state machine과 atomic consent-off tombstone; ambiguous reaction은 safe로 간주하지 않음 | **§2-2/2-5 필수** |
| 4 | Fallback/cache/product facts가 requested identity와 origin/freshness에 결합되지 않음 | `R01-CAN-098/105/109/110/113`, `R05-CAN-023` | Immutable itemSeq/sourceRef identity와 origin envelope; fixture/live namespace 분리; mismatch block/degrade | **§2-9 필수** |
| 5 | Health/search term, malformed body, device/coordinates가 value-bearing log에 남음 | `R01-CAN-069`, `R06-CAN-003`, `R01-CAN-059/061` | Value-free structured telemetry와 retention/access policy | **§2-5 필수** |
| 6 | Public government narrative와 client assistant text가 privileged model role에 들어감 | `R01-CAN-021/027` | Server instruction, server transcript, untrusted tool/public data를 role/typed envelope로 분리 | **§2-1 및 의료-output 필수** |
| 7 | Hours parsing/unknown and chat-facility filtering이 “모름”을 closed/absent로 바꿈 | `R01-CAN-094/095`, `R04-CAN-011` | Result algebra(`known open/closed/unknown/error`)를 backend→UI까지 보존 | **§2-3 필수** |
| 8 | Secret/command controls가 lexical spelling과 filename/line suppression에 의존 | `R01-CAN-001/005/008`, `R04-CAN-001/002` | Normalized operation policy, path-aware scanner, unavoidable commit/CI enforcement | **§2-8 필수** |
| 9 | Demo process가 삭제된 worktree의 부분 classpath를 계속 실행 | A-2/A-3/A-4 retry 및 Track B `NoClassDefFoundError` | Immutable built artifact로 원자 배포, worktree lifecycle과 process 종료 연동, startup integrity attestation | 일반 deploy, 단 safety response 영향 검토 |
| 10 | Provider reply를 `MermAidAnswer`로 coercion하지 못하면 정상 chat 전체가 generic refusal로 fail-close | A-1 `COERCION_FAILED` | 실제 provider/model schema contract test, bounded repair/coercion telemetry. Raw prose release·validator 완화 금지 | 안전 release boundary human review |
| 11 | Local fixture integrity error가 government outage 503으로 mapping됨 | A-4 first trace | Fixture completeness를 startup에서 검증하고 local/config/upstream error taxonomy를 분리 | 오류 진실성 |
| 12 | Allergy negation parser가 explicit-none wording을 declaration으로 오인 | `e21ec…` | Clinically reviewed explicit-none/negation grammar; ambiguous cases는 fail-open하지 않음 | **§2-2 필수** |
| 13 | Main map은 오류를 정직하게 보이지만 assistant facility surface는 pending false-empty, ER은 unsupported | Track B-5, `NearbyFacilities.tsx:59-70,127-132` | Loading/error/empty 분리; ER은 adapter 전까지 unavailable로 명시 | Care-information UX/product |
| 14 | Response request ID가 default server log에 없어 owner event와 stack을 직접 join할 수 없음 | Track A 원본 IDs 모두 unknown | PII 없는 MDC request ID + fixed stage/error codes | **§2-5 telemetry review** |

[Hardening report](/private/var/folders/df/8mq60fls7636g_3_n6wrmlf00000gn/T/codex-security-scans-4bFfeC/mermaid/654f906e00e81648d1482210b6a9171747dddd75_20260715T030819Z___0n3o4b/hardening/hardening.md)는 이 순위를 여섯 구조적 proposal과 18개 before/local/structural Mermaid diagram으로 확장한다. Diagram은 static contract validation만 했으며 renderer validation을 했다고 주장하지 않는다.

## 중앙 attack-path 최종 요약

중앙 검증의 eligible 123개(`reportable` 101 + `needs_review` 22)를 5개 배치로 나누어 의미 판정하고 각 배치를 독립 재감사했다. 승인된 35-key assembler는 [build_attack_path.mjs](/private/var/folders/df/8mq60fls7636g_3_n6wrmlf00000gn/T/codex-security-scans-4bFfeC/mermaid/654f906e00e81648d1482210b6a9171747dddd75_20260715T030819Z___0n3o4b/artifacts/attack_path_round06/build_attack_path.mjs), SHA-256 `1421765ba8a03ca0a3f67e98c448520e23462a47781f8e04174bd0cc8397e7f6`다.

| 배치 | 행 | semantic draft SHA-256 | materializer report-bundle SHA-256 | 독립 재감사 |
|---|---:|---|---|---|
| 01 | 33 | `2b417d775da3a54a46740289357c70281bc373d06147db60283c45cf62be672c` | `1d63478b5c738ef7d240d8c524f2127dc5b733319b92633297f4d043eda443aa` | PASS |
| 02 | 10 | `b19fae12e9b2260900f6b09b95263b54a217acbd3c51c081d21b4471cf7c4381` | `c7e8b4c0826cbb17c1502c40a61444c7be07c9dea533f0e655f8048b505c4f5f` | PASS |
| 03 | 24 | `6b0edebc34ad40cc470b142f9548af7b5ed619b3f40e061631dc79619f7bfaa5` | `0c31d3f40b9b9d01d8480ce17f4eae0a1ca0c278b59e71f56c34f8fd2aa806fb` | PASS |
| 04 | 23 | `78d51d018f6049efa66af3aa97e5ab081d6ce3edf8408c5c0a277465ac57cf5e` | `4009560d5e7f7bf16845506970163b7d1a7a2ac29c0e3a43b7c0ba5e706f778d` | PASS |
| 05 | 33 | `6912468eb296915c9a034250ecde308a3afa75464863558aa0b47454ae57bc48` | `0d5f3197d4fba327535a36298bdb3964fdaeee900f5c19b5f7bdedd99f2fddc4` | PASS |

최종 합계는 다음과 같다.

- 정책 판정: `reportable 79 / deferred 23 / ignore 21`
- generic severity: `critical 1 / high 6 / medium 53 / low 42 / ignore 21`
- generic priority: `P0 1 / P1 6 / P2 53 / P3 42 / none 21`
- 승인 스키마의 hard suppression: 123개 모두 `none`. `ignore 21`은 구조화 사실에 대한 impact×likelihood matrix 결과이지, 과거의 삭제된 `self_only`·`privileged_only` 같은 비표준 suppression이 아니다.

Generic P0는 `R01-CAN-018`(model-authored medical prose semantic release) 하나다. Generic P1은 `R01-CAN-020/023/046/082/105`와 `R05-CAN-002`다. 이 generic exploitability matrix는 AGENTS.md의 의료·§2 review 축과 **별개**다. 따라서 중앙 검증의 `reportable/P0 33`, `reportable/P1 44`, 그리고 `needs_review/P0 9`를 낮추지 않는다. 현재 assembler/report는 각 행의 repository disposition과 repository priority를 눈에 보이게 보존하고 generic priority를 별도 축으로 제시하지만, generic priority 계산을 repository priority에 기계적으로 결합하지는 않는다. 후속 triage는 이 한계를 알고 repository 축을 우선해야 한다.

[최종 materializer](/private/var/folders/df/8mq60fls7636g_3_n6wrmlf00000gn/T/codex-security-scans-4bFfeC/mermaid/654f906e00e81648d1482210b6a9171747dddd75_20260715T030819Z___0n3o4b/artifacts/attack_path_round06/materialize_staging.mjs)은 SHA-256 `11831264ea29865e0b02efefb2eb099f29ac3cabf3f36500a283047537823002`이며 `node --check`와 5개 배치 pin 재계산을 통과했다. 그러나 check-only는 정확히 `repository has tracked unstaged changes`로 exit 1했다. 중앙 consolidated stream과 post-central ledger가 아직 없고, 알려진 `R05-CAN-004` provenance 오류도 seal 전 교정해야 하므로, 5개 `attack_path.jsonl`, materialization manifest, canonical attack receipt는 의도적으로 생성하지 않았다. 이 phase boundary는 [materialization contract](/private/var/folders/df/8mq60fls7636g_3_n6wrmlf00000gn/T/codex-security-scans-4bFfeC/mermaid/654f906e00e81648d1482210b6a9171747dddd75_20260715T030819Z___0n3o4b/artifacts/attack_path_round06/MATERIALIZE_STAGING_PREPARATION.md), SHA-256 `b1e29880c637450f71f8d256cd422a771b0f6189d18a97c824fdc6af0aa72ae0`에 동결돼 있다.

---

# Round 6 cap과 Round 7 정확한 재개점

[Round 6 terminal state](/private/var/folders/df/8mq60fls7636g_3_n6wrmlf00000gn/T/codex-security-scans-4bFfeC/mermaid/654f906e00e81648d1482210b6a9171747dddd75_20260715T030819Z___0n3o4b/artifacts/deep_merge/round-06_terminal_state.json)는 `state=capped`, `reason=capped_by_user_deadline_after_round_06`, `saturation_proven=false`, `centralized_validation_may_begin=true`를 기록한다. Round 6 merge는 185-row prefix를 byte-preserve하고 `R06-CAN-001..003`을 붙여 188개를 만들었으며 postwrite 재감사는 PASS다.

이번 1차 마감은 사용자 deadline에 따른 **탐색 중단**이지 semantic saturation 선언이 아니다. 같은 세션에서 Round 7을 재개할 때:

1. Recorded target `654f906e00e81648d1482210b6a9171747dddd75`와 동일한 main-reachable commit `f4a2b6de89f5e4fa4ef5a81e5dafd54f8255367b`가 소유한 tree `a14388f597c0c2a17e0dbcfc2d951a390c877214`를 유지한다.
2. `artifacts/04_reconciliation/deduped_candidates.jsonl` 188-row, SHA-256 `274fd61156b7ae3ebeadb1c905258fbe001a5f81380891b9d2491b44652477b8`를 frozen prior canonical로 사용한다.
3. Authority manifest `6d040b4494fd6e4c2581d98cfedbf8287650e7a70206b76ac0849bb8a10b5e88`와 terminal state `01ce318541a4f770382088cfebd01f947d7313ac1e7a186920599d1f6620d9d6`를 handoff로 쓰고 Round 0~6을 재생성하지 않는다.
4. `R05-CAN-004`의 affected-location provenance를 `DrugService.java:214-219`로 authority-preserving correction하고 새 authority seal과 receipt/hash를 발행한다. 외부 tracked changes가 끝나 clean gate가 열리면 중앙 validation check-only를 두 번 byte-compare한 뒤 manifest-bound write와 postwrite audit를 수행한다. Gate를 약화하거나 외부 diff를 stash/revert하지 않는다.
5. 이미 동결된 5개 attack semantic draft/report와 최종 materializer를 그대로 사용한다. 중앙 receipt 채택 뒤 materializer check-only를 두 번 비교하고 materialize한 다음, attack assembler도 같은 check-only → sealed write → postwrite audit 순서로 물리 채택한다. 의미 판정을 다시 만들지 않는다.
6. 그 뒤 새 independent discovery를 **Round 7 blind-first**로 시작해 188개 prior canonical과 비교한다. Remediation-distinct residue만 Round 7 reconciliation/merge에 넣고, 바뀐 canonical 집합에 대해서만 validation/attack-path를 증분 재실행한다.

---

# Repository source 무변경·clean-gate 상태

이 scan은 repository source, test, fixture, config, validator, grounding을 수정하지 않았다. Scan-root discovery/canonical ledger는 Round 0~6에서 생성·갱신됐지만, 중앙 validation/attack receipt는 아직 물리 채택되지 않았다. 작성물은 위 scan-root artifact와 hardening 디렉터리에만 있다.

그러나 보고 시점 repository의 `git diff`는 비어 있지 않다. Adoption hold 작성 당시에는 checkout `d6a143a9…`, tree `8b28dbd…`, tracked-unstaged만 존재했으나, 최종 관찰 시각 `2026-07-16 02:57:08 KST`에는 외부 작업이 checkout을 `4358efec…`, tree `c76b0b0…`로 전진시켰다. `FacilityController.java`는 staged `M`이고, `FacilityService.java`, `PharmacyApiClient.java`, `Facility.java`, `CacheConfigTest.java`, `FacilityServiceTest.java`, `PharmacyApiClientTest.java`는 `MM`, `application.yml`은 staged `M`이다. unmerged entry는 없었다. 따라서 `git diff --quiet`와 `git diff --cached --quiet`가 모두 exit 1이다. `HiraPharmacyProperties.java`, `.pnpm-store/`, 세 worker-brief 문서도 untracked다. 이 scan은 그 내용을 작성·수정·resolve·stash·revert·stage·commit하지 않았다.

따라서 Done 조건의 “`git diff` empty”는 **현재 충족됐다고 주장할 수 없다**. 중앙/attack builder가 이를 감지해 physical adoption을 거부한 것은 의도된 transaction safety다. 중앙 검증과 attack-path의 의미 결과, Track A/B/C evidence, hardening report는 scan-root에 동결됐다. Round 6 1차 마감 이후 남은 것은 (1) provenance correction과 clean gate 이후 중앙/attack receipt의 물리 채택, 그리고 사용자가 재개할 때만 수행할 (2) Round 7 blind-first 신규 탐색이다. Round 7을 실행하지 않았으므로 의미 포화를 주장하지 않는다.
