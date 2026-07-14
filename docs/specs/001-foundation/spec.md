---
title: mermAid 기반 스펙 (Foundation)
status: draft
version: 2.0
created: 2026-07-10
updated: 2026-07-10
owner: 윤서진
tags: [foundation, architecture, public-api, llm, spring-boot, react, safety]
---

# mermAid 기반 스펙 (Foundation)

> 이 문서는 팀이 작성한 **요구사항 명세서 v0.1**·**프로젝트 수행계획서**와,
> 별도로 작성된 **Sol Pro 개발 문서 패키지**를 **하나로 합친 결과**입니다.
> 두 문서의 의도를 유지하되, **조사로 확인된 사실에 맞게 교정**했습니다.
> 원본과 달라진 부분은 §2에 근거와 함께 모두 명시했습니다.

## 변경 이력

| 버전 | 날짜 | 내용 |
|---|---|---|
| 1.0 | 2026-07-10 | 최초 작성. 원본 docx의 충돌 9건 해소 |
| 2.0 | 2026-07-10 | DUR API 추가, Sol Pro 문서 병합, astryx 채택, 안전 모델 강화 |
| 2.1 | 2026-07-10 | 계열 교차반응 원인 재특정(LLM이 대체재를 골랐다). SA-08 생성기 제한, §7-1 수용된 위험 AR-01·AR-02 신설, §8 페르소나 개정 |

## v2.0에서 바뀐 것 (요약)

1. **DUR 품목정보 API 추가** — 병용금기·연령금기·임부금기·노인주의. §2-10
2. **`map` 필드 → `ui_actions[]` 배열** — Sol Pro의 설계가 더 낫습니다. §2-11
3. **알레르기 판정을 4-state로** — `blocked / warning / no_match_found / unknown`. §2-12
4. **`is_open_now`를 nullable로** — v1의 `boolean`은 "모름"을 "닫힘"으로 말합니다. **v1 코드의 버그.** §2-13
5. **`data_mode: live | hybrid | fixture`** — 발표 안전망 + 호출 한도 대응. §2-14
6. **서버 후처리 불변조건** — 스키마가 못 잡는 환각을 코드로 막습니다. §2-15
7. **채팅은 `sessionStorage`로** — LocalStorage도 XSS에 노출됩니다. §2-16
8. **UI는 `facebook/astryx` 0.1.4** (버전 고정). §2-17
9. 공통 에러 계약, provider namespace ID, `ITEM_SEQ` 마스터 조인 키.

---

## Context & problem

한국은 공공 의료 인프라가 촘촘하지만, **사전 정보가 없으면 접근 자체가 어렵습니다.** 언어 장벽이 있는 외국인, 의료 시스템에 익숙하지 않은 사람, 야간·휴일에 갑자기 아픈 사람이 그렇습니다.

mermAid는 **로그인 없이** 영어로 증상을 말하면 (1) 공공 데이터로 검증된 한국 의약품 정보를 구조화된 형태로 보여주고, (2) 지금 영업 중인 근처 약국·병원을 지도에 띄워주는 서비스입니다.

대상 사용자는 **한국에 체류·여행 중인 영어 사용자**와 **의료 정보 접근이 어려운 내국인**입니다.

## Goals / non-goals

**Goals**
- 로그인 없이 즉시 대화 시작
- LLM 응답을 **JSON Schema로 강제**하고, **서버가 사실을 교차검증**한 뒤에만 화면에 보냅니다
- 의약품 정보의 **출처를 공공 API로 고정**하여 환각을 구조적으로 차단
- 반경·기관 종류·**현재 영업 여부**로 필터링한 의료기관을 지도에 표시
- 알레르기·기피 성분과 **DUR 금기 정보**를 결합해 경고
- 채점 산출물(ERD, 테이블 명세서, API 명세서, 테스트 케이스)을 코드에서 자연스럽게 도출

**Non-goals**
- 영어 외 다국어 UI
- **진단.** 우리는 정보를 제공하고 전문가 상담을 권할 뿐입니다 (§7)
- 시니어 데일리 케어 + 보호자 FCM 알림 (원본 FR-06) → **Could, MVP 제외**
- 서버에 의료 상담 대화 내용을 영속 저장하는 것
- 동물병원, 비용 예측 (Sol Pro NORM-006, NORM-013)

---

## 1. 아키텍처

```
┌──────────────────────────────────────┐
│ React 19 · Vite 8 · TS · Tailwind v4 │
│  + astryx 0.1.4 (UI 컴포넌트)         │
│  - openai JS SDK (baseURL → 자기 오리진)│
│  - naver.maps (raw API, language=en) │
│  - sessionStorage: 채팅 (탭 종료 시 소멸)│
│  - localStorage:   즐겨찾기 스냅샷      │
└──────────────┬───────────────────────┘
               │ POST /api/v1/chat/completions   (OpenAI 호환)
               │ GET  /api/v1/facilities | /drugs | /profiles
               ▼
┌──────────────────────────────────────┐
│ Spring Boot 3.5 · Java 21            │
│  ChatProxyController  키 은닉, 프롬프트 주입 방어 │
│  AnswerAssembler      2-패스 RAG + 후처리 불변조건 │
│  FacilityService      반경·영업중 계산 (공공 API엔 없음)│
│  DrugService          e약은요 + 허가정보 + DUR 병합 │
│  ProfileService       CRUD (JPA → ERD 산출물)     │
└──┬───────────────────────────────┬───┘
   │                               │
   ▼                               ▼
LLM Provider              공공데이터포털 6종
(OpenAI 호환 endpoint)     (CORS 없음 → 서버에서만)
```

**왜 Spring Boot인가:** 팀원 4명이 수업에서 배운 스택입니다. 채점 항목에 "커밋/이슈/PR 기록"이 있어 **팀원이 기여할 수 있는지**가 스택 선택의 1순위 기준이었습니다. 부수적으로, 공공 API가 CORS 헤더를 보내지 않아 서버 프록시가 어차피 필수입니다.

**왜 Spring AI를 안 쓰는가:** 우리 백엔드는 OpenAI 형식 요청을 받아 상류로 릴레이합니다. Spring AI를 끼우면 두 번 변환됩니다. `WebClient` 직접 릴레이가 더 단순하고 팀원에게 익숙합니다. (또한 Spring AI 2.0은 Spring Boot 4를 요구합니다.)

---

## 2. 원본에서 달라진 점 (근거 포함)

### 2-1. 툴 콜링으로 지도를 띄우는 설계 → 응답 스키마의 액션 필드

**사실:** 모델이 툴을 호출하면 그 메시지의 `content`는 비어 있습니다. **툴 콜과 구조화 출력은 같은 메시지에 공존할 수 없습니다.**

**교정:** 브라우저에 툴 콜을 노출하지 않습니다. 지도 표시는 응답 스키마의 `ui_actions[]`로 전달합니다 (§2-11). 서버 내부에서 검색 의도를 뽑는 단계는 별도 왕복이므로 무관합니다.

### 2-2. "네이티브 서치 그라운딩 강제 활성화" → 공공 API 기반 2-패스 RAG

**사실 둘.** ① **웹 검색 그라운딩은 강제할 수 없습니다** — 모델이 검색할지 스스로 정합니다. ② Gemini 2.5 이하는 그라운딩과 `responseSchema`를 같은 요청에 쓰면 `400 INVALID_ARGUMENT`로 거부합니다.

**교정:** 2-패스로 갑니다. (2026-07-10 구현 완료 — DEV-403)

1. **Pass 1a (추출):** 규칙 기반 안전 선별 → `SearchTermExtractor`가 **별도 LLM 왕복**으로 사용자 발화에서 영문 성분명·제품명을 뽑음
2. **Pass 1b (조회):** `DrugService.retrieve()`가 **서버에서** 식약처 API(허가정보 + e약은요 + DUR)를 조회
3. **Pass 2 (요약):** 조회 결과를 `DRUG_CONTEXT` **system 메시지**로 주입 → LLM은 **그 안에 있는 사실만** 요약 → `StructuredOutputFallback` → **서버가 출처를 덮어씀** → **후처리 불변조건 검증** (§2-15)

Sol Pro도 같은 결론입니다: *"의료기관·의약품 사실은 서버가 공공 API에서 조회하고, LLM은 조회 결과만 요약"* (NORM-010).

#### 왜 추출에 LLM을 한 번 더 쓰는가

**허가정보의 제품명 검색은 부분 문자열 일치입니다.** `item_name="I have a headache"`는 `totalCount: 0`을 반환합니다 (실측). 영어 증상 문장은 **영문 성분명으로 바꾸기 전에는** 이 데이터베이스에서 아무것도 찾지 못합니다.

**추출기의 출력은 사실이 아니라 질의입니다.** 모델이 `"Acetaminophen"`을 제안하면, 그런 제품이 존재하는지·무엇이 들었는지·무슨 효능으로 허가됐는지는 **정부 DB가 결정**합니다. 모델이 엉뚱한 걸 제안하면 검색 결과가 비고, 답변은 "약국에 가세요"가 됩니다. 모델이 약을 존재하게 만들 수는 없습니다 — 불변조건 6이 조회되지 않은 제품명을 전부 거부합니다.

따라서 추출 결과는 **형태만 검증**합니다: 영문 1~3단어(각 3자 이상, 숫자 없음), 성분 최대 3개, 제품명 최대 2개. 실패·타임아웃·프로즈 응답은 전부 **빈 컨텍스트**로 수렴합니다. 빈 컨텍스트는 안전한 컨텍스트입니다.

#### DRUG_CONTEXT에 무엇을 넣는가 (그리고 왜 그것만)

| 규칙 | 근거 (전부 실측) |
|---|---|
| **`수출용` 제품 제외** | 국내 약국에서 살 수 없습니다. `Acetaminophen` 1페이지에 4건. **`수출명`은 다릅니다** — 게보린정(수출명:돌로린정)은 국내 판매품이라 남깁니다 |
| **e약은요 항목이 없으면 제외** | 휴리스틱이 아니라 **구조적 강제**입니다. 모델은 공식 문언만 요약할 수 있으므로, 공식 문언이 없는 제품은 설명할 방법이 없습니다. (`수출용` 4건 전부 e약은요 없음) |
| **성분별로 순위를 매기고 라운드로빈** | 한 풀에 몰아 정렬하면 최종 tie-break인 제품명 가나다순이 성분 순서를 덮어씁니다. `[Acetaminophen, Ibuprofen, Naproxen]`을 넣었더니 나르펜정·나로펜정·나프록신정 — **NSAID 3개, 아세트아미노펜 0개**가 나왔습니다 |
| **일반의약품 우선, 성분 수 적은 것 우선** | 여행자는 전문의약품을 살 수 없습니다. 두통에 6성분 종합감기약보다 단일 성분 정제가 낫고, 묻지 않은 알레르겐이 적습니다 |
| **알레르기 차단 성분은 제외, 단 사용자가 이름을 댄 제품은 남김** | "부루펜 먹어도 되나요?"에는 답해야 하고, 두통약으로 부루펜을 권해서는 안 됩니다. 목록 조회(`ITEM_INGR_NAME`)와 상세 조회(`MAIN_INGR_ENG`)는 **다른 필드**라 상세 조회 뒤 한 번 더 판정합니다 |
| **병용금기는 개수만** | 병용금기는 *약 하나*가 아니라 *약 쌍*의 속성입니다. 나르펜정400밀리그램에는 20건이 달려 있고, 전부 넘겼더니 모델이 **경고 26개짜리 카드**를 만들었습니다. 사용자가 무엇을 복용 중인지 묻지 않았으므로 어느 것이 해당되는지 알 수 없습니다. 개수와 "약사에게 복용 중인 약을 말하라"만 전달하고, 전체 목록은 `GET /drugs/{id}`가 그대로 반환합니다 |
| **공식 문언은 자르지 않음** | 반 토막 난 주의사항은 아무도 보지 못하는 금기입니다. 3제품 × 약 1,200자 = 3.5k자, 감당 가능합니다 |

#### 출처는 모델이 쓰지 않습니다

`source_refs`와 `data_status`는 **서버가 덮어씁니다**. 모델은 그 레코드가 라이브 API에서 왔는지 네트워크 장애로 재생한 픽스처에서 왔는지 알 방법이 없고, 손으로 베낀 `retrievedAt`은 아무도 검증하지 않은 타임스탬프입니다. 모델은 `drugs[].source_ref_id`로 **참조만** 하고, 불변조건 1·5가 그 참조가 실재하는지 검사합니다.

이로써 불변조건 3(`data_status=live`인데 픽스처 출처)은 모델 게이트가 아니라 **우리 라벨링에 대한 회귀 가드**가 됩니다.

#### 비용과 지연 — 실측 (2026-07-10, opencode zen · glm-5.2)

턴당 **LLM 왕복 2회**(추출 + 요약)입니다. 그리고 **상류 지연이 지배적이며 편차가 극심합니다.**

| 관측 | 값 |
|---|---|
| pass 2, 작은 컨텍스트 | 13.7초 |
| pass 2, 실제 컨텍스트(3제품) | 40~120초+ |
| **완전히 같은 웜 캐시 요청, 몇 분 간격** | **215초 → 87초** |
| pass 1a 추출 | 5~21초 |
| **pass 1b 조회 (병렬화 후, 콜드)** | **4.7초** |
| pass 1b 조회 (웜) | 0.15초 |

**이건 우리 코드가 느린 게 아니라 프로바이더가 느린 것입니다.** 조회는 이제 전체의 4%도 안 됩니다.
다만 우리가 왕복을 2회로 늘려 노출을 두 배로 만들었습니다. 그래서:

- `mermaid.llm.timeout` = **120초**. 60초는 경계선에 정확히 걸려, 같은 질문이 캐시 상태에 따라 답하거나 500을 내던 값입니다.
- `mermaid.llm.extraction-timeout` = **60초** (2026-07-14 개정. 원래 30초). 짧은 배열 두 개를 받는 호출에 답변과 같은 예산을 주면, 대기열에 걸린 프로바이더가 **조회를 시작하기도 전에** 3분을 태웁니다. 타임아웃 시 컨텍스트가 비고, 답변은 약을 하나도 지목하지 않은 채 "약국에 가세요"가 됩니다 — **degraded지, wrong이 아닙니다.**
  - **그런데 30초는 응답의 *크기*를 보고 정한 값이었고, 프로바이더는 크기로 과금하지 않습니다.** 2026-07-14 브라우저 실측: **같은 추출 호출이 성공할 때 24.4초**, 같은 턴의 답변 호출이 100초. 예산이 실측 지연 **바로 위에** 얹혀 있어서, 상류가 조금만 느려지면 추출이 죽었습니다. 그리고 추출이 죽는 것은 작은 degradation이 아닙니다 — 검색어가 없으니 정부 조회가 0건이고, 모델은 빈 컨텍스트로 답을 쓰고, 검증기가 그걸 거부합니다. **두통을 물은 사람이 약을 하나도 못 받습니다.** 예산 안에 들어오면 같은 질문이 검증된 약 3종을 돌려줍니다. 느린 턴의 벽시계 시간이 조금 늘고, 옛 천장은 약을 잃었습니다.
- 상류 실패는 **500이 아니라** 안전한 답변으로 내려갑니다. 느린 모델이 아픈 사람에게 "서버가 고장났다"고 말해선 안 됩니다.
- `RAG pass 1` / `RAG pass 2` 로그가 각 구간의 밀리초를 찍습니다. 다음에 타임아웃을 만난 사람이 공공 API부터 의심하지 않도록.

**조회 병렬화 완료 (2026-07-10).** 성분 검색·안내문 프로브·상세 조립을 `Parallel.map`으로 묶었고, `detail()`은 허가정보·e약은요·DUR을 `Mono.zip`으로 동시에, DUR은 네 종류를 동시에 부릅니다.

| | 이전 | 이후 |
|---|---|---|
| `GET /drugs/{seq}` 콜드 | 8.79초 | **4.23초** |
| `retrieve()` 콜드 | 순차 약 26회 호출 | **4.68초** |

**더 늘려도 소용없습니다.** 실측: DUR 4회를 순차로 5.77초, 동시에 2.70초 — **2.1배지 4배가 아닙니다.** 식약처가 서비스키 단위로 동시 요청을 조입니다. `UPSTREAM_CONCURRENCY = 4`가 곡선이 평평해지는 지점입니다.

**그래도 챗은 느립니다.** 조회는 이제 전체의 4%가 안 되고, 나머지는 전부 LLM 왕복 2회입니다. FE는 **로딩 상태를 반드시 그려야 합니다.** 더 줄이려면 모델을 바꾸거나 `MAX_CONTEXT_DRUGS`를 줄여야 합니다 — 둘 다 품질을 건드리는 결정이라 팀이 정할 일입니다.

> **기각한 방안: 추출 결과를 Redis에 캐시하기.** 캐시 키가 사용자의 증상 문장 그 자체가 됩니다. 의료 상담 텍스트를 서버에 남기지 않는다는 §2-4의 결정과 정면으로 충돌합니다. 빠르지만 하지 않습니다.

### 2-3. "Vercel AI SDK를 그대로 재사용" → `openai` 공식 JS SDK + `baseURL`

**사실:** Vercel AI SDK의 `useChat`은 각 SSE 줄을 자체 `UIMessageChunk` 스키마로 검증합니다. **OpenAI SSE를 읽지 못합니다.** 백엔드를 표준에 맞출수록 오히려 멀어집니다.

**교정:** `openai` 공식 JS SDK. `baseURL`을 우리 서버로, `dangerouslyAllowBrowser: true`, `apiKey`는 더미(`'not-needed'` — 빈 문자열은 생성자가 거부).

> **함정:** 이 SDK는 `Authorization`과 `x-stainless-*` 헤더를 붙이며 전부 non-simple 헤더라 **CORS preflight**를 유발합니다. Spring CORS에 헤더를 일일이 허용하는 대신 **Vite dev proxy로 same-origin**을 만들어 preflight 자체를 없앱니다.

### 2-4. CRUD·ERD ↔ "DB 없음" 충돌 → 하이브리드 저장

**충돌:** 원본 §7과 Sol Pro Part 6은 모두 "서버 DB 없음"입니다. 그런데 수행계획서 §5 산출물에는 **ERD(6번)와 테이블 명세서(7번)가 필수**입니다.

Sol Pro도 이 축을 `OQ-007`(*"수업에서 LocalStorage CRUD를 인정하나요?"*)로 열어뒀습니다. **우리는 답을 압니다.** 산출물 목록에 명시돼 있으므로 서버 DB가 필요합니다.

**교정:** 3층 저장 (§4). 민감한 의료 상담은 서버에 오지 않습니다.

### 2-5. "로그인 없음" ↔ "사용자 프로필" → 익명 디바이스 ID + 세션 기본

**교정:** 브라우저가 최초 진입 시 UUID(`deviceId`)를 생성해 헤더로 보냅니다. 계정 없이 서버 프로필이 성립합니다.

**단, Sol Pro의 프라이버시 감각을 받습니다** (NORM-004). **알레르기는 기본적으로 세션 입력**이며, 서버 저장은 **명시적 opt-in**일 때만 합니다. 기본값 OFF.

### 2-6. FR-06(시니어 + 보호자 FCM) → Could, MVP 제외

계정 + 스케줄러 + 푸시 인프라가 필요하고 FR-01의 로그인 없음과 충돌합니다. Sol Pro는 더 강하게 P2(과제 제외)로 뒀습니다.

### 2-7. `DELETE /api/v1/drugs/{id}` → 삭제

GET을 복사한 오기입니다. 의약품은 우리가 소유하지 않는 참조 데이터라 Delete 대상이 아닙니다. CRUD의 D는 **즐겨찾기·알레르기**에서 시연합니다.

### 2-8. FR-04는 API를 하나 더 요구합니다

**사실:** e약은요(`DrbEasyDrugInfoService`)의 응답은 전부 `*Qesitm` 서술형 안내문이며 **성분 필드가 없습니다.**

**교정:** **식약처 의약품 제품 허가정보**(`DrugPrdtPrmsnInfoService07`) 추가. 2026-07-10 실제 호출로 확인한 정확한 경로:

| 목적 | 오퍼레이션 | 필드 |
|---|---|---|
| 성분 → 약 목록 (알레르기 필터) | `getDrugPrdtPrmsnInq07?item_ingr_name=Acetaminophen` | `ITEM_INGR_NAME` (영문, `/` 구분) |
| 약 → 성분 (카드 표시) | `getDrugPrdtPrmsnDtlInq06?item_seq=…` | `MAIN_INGR_ENG`, `MAIN_ITEM_INGR` (`[M######]…`) |

**함정 넷:**
1. 성분 검색은 **대소문자를 구분하는 부분 문자열** 매칭입니다(정확 일치가 아님). `Ibuprofen` → 282건, `ibuprofen` → **142건이지만 전부 Dex*ibuprofen***. `Acetaminophen` → 1,357건, `acetaminophen` → 0건. **사용자 입력을 그대로 넘기면 알레르기 질문에 엉뚱한 약이 나옵니다.**
2. 상세 오퍼레이션은 **`getDrugPrdtPrmsnDtlInq06`**입니다(서비스는 07). `…DtlInq07`은 **404**.
3. 목록 조회에 **`item_seq` 파라미터가 없습니다.** 넣어도 무시하고 전체 43,064건을 반환합니다.
4. 성분명에 **제형 수식어**가 붙습니다(`Acetaminophen Granules`, `Acetaminophen Micronized`). 벗겨내지 않으면 같은 알레르겐이 `blocked`가 아니라 `warning`이 됩니다 — **안전 결함**입니다. 반면 염 형태(`Chlorpheniramine Maleate`)는 벗기면 안 됩니다.

> Sol Pro는 이 사실에 도달하지 못하고 "성분 필드가 있는지 spike로 확인"이라는 리스크로 남겨뒀습니다. FR-04(Must)의 성립 여부가 프로젝트 중반까지 열려 있게 되는 구조였습니다. (그리고 조사 보고서는 `MAIN_INGR_ENG`가 목록에 있다고 했는데, 실제로는 상세에만 있습니다. **실측이 이깁니다.**)

### 2-9. FR-02의 `radius` / `open_now`는 우리가 계산합니다

**사실 (2026-07-10 실제 호출로 확인, `backend/src/main/resources/fixtures/` 참조):**
- 약국 좌표 조회 `getParmacyLcinfoInqire`의 파라미터는 `serviceKey, WGS84_LON, WGS84_LAT, pageNo, numOfRows`뿐. **radius 파라미터가 없습니다.**
- **어떤 공공 API에도 "현재 영업 중" 필터가 없습니다.**
- **좌표 조회는 주간 시간표를 주지 않습니다.** 실제 필드는 `hpid, dutyName, dutyAddr, dutyTel1, latitude, longitude, distance, startTime, endTime`뿐입니다. (v1 스펙은 `dutyTime1s`~`8c`가 함께 온다고 적었으나 **사실이 아닙니다.**)
  - `dutyTime1s`~`dutyTime6c`는 **`getParmacyBassInfoInqire`**(HPID 단건)에 있습니다. → **약국도 N+1**.
  - `distance`는 **km 단위**입니다(`0.14` = 140m). Haversine(미터)과 섞지 마세요.
  - 쉬는 요일은 필드 자체가 없습니다(예: 일요일 휴무면 `dutyTime7*` 없음).
- 병원은 심평원 **두 서비스를 엮어야** 합니다. 둘은 짝이고, 하나만으로는 아무것도 못 합니다.

  | 서비스 | 경로 | 역할 |
  |---|---|---|
  | 병원정보서비스 | `B551182/hospInfoServicev2/getHospBasisList` | `xPos`/`yPos`/`radius`(m)로 검색. **`ykiho`를 발급하는 유일한 곳** |
  | 의료기관별상세정보서비스 | `B551182/MadmDtlInfoService2.8/`**`getDtlInfo2.8`** | `ykiho`로 진료시간 조회. **검색 오퍼레이션이 없음** |

  **2026-07-10: 둘 다 승인되어 200입니다.** 실측 픽스처 `hospital_list.json`, `hospital_detail.json`.

  - **버전 접미사가 서비스와 오퍼레이션 둘 다에 붙습니다**: `MadmDtlInfoService2.8/getDtlInfo2.8`. `…2.8/getDtlInfo`(접미사 없는 오퍼레이션)는 **404 `API not found`**입니다.

    > ⚠ **404는 "이 오퍼레이션 이름이 없다"는 뜻이지, "서비스가 없다"는 뜻이 아닙니다.**
    > 우리는 `…2.8/getDtlInfo`의 404를 보고 "2.8은 없다"고 단정한 뒤 설정을 `2.7`로 "고쳤습니다".
    > `2.7`은 실재하지만 승인되지 않은 구버전이라 403을 냈고, 우리는 그 403을 다시 "승인 대기"의 증거로 읽었습니다.
    > **틀린 전제가 그럴듯한 증거를 만들어냅니다.** 경로가 죽었다고 선언하기 전에 오퍼레이션 이름을 버전 접미사까지 바꿔가며 확인하세요.

  - **목록** (`hospital_list.json`): `ykiho`, `yadmNm`, `addr`, `telno`, `XPos`(경도)/`YPos`(위도), `distance`, `clCd`/`clCdNm`(종별), `postNo`, `estbDd`, 의사 수 계열.
    - **`radius`는 필수입니다.** 빼면 좌표를 줘도 전국 79,727건이 옵니다.
    - 진료시간은 목록에 **없습니다.** 상세를 불러야 합니다 → **N+1. `ykiho`로 캐싱하세요.**
  - **상세** (`hospital_detail.json`): `trmtMonStart`~`trmtSatEnd`, `lunchWeek`, `noTrmtSun`, `noTrmtHoli`, `emyDayYn`, `emyNgtYn`, `rcvWeek`, 주차 정보. **문서에 적혀 있던 필드명이 다 맞았습니다.**
    - **일요일은 필드 자체가 없습니다.** `trmtSunStart`가 없고 `noTrmtSun: "휴진"`만 옵니다. 약국의 `dutyTime7*` 부재와 같은 패턴입니다.
    - 시각은 `"0830"` 문자열, `lunchWeek`은 `"12:30 ~ 13:30"` 자유 텍스트입니다.

  #### `distance` 단위가 기관마다 다릅니다 (실측)

  | 기관 | `distance` | 좌표 필드 |
  |---|---|---|
  | 심평원 `B551182` | **미터** (`865.68…`, 39자리 문자열) | `XPos`(경도) / `YPos`(위도) |
  | 국립중앙의료원 `B552657` | **km** (`0.91`) | `latitude` / `longitude` |

  둘 다 Haversine으로 대조했습니다(오차 0.6% 이내). **하나를 보고 다른 하나를 짐작하지 마세요.**

  #### 국립중앙의료원 쪽도 열렸습니다

  | 서비스 | 쓸모 |
  |---|---|
  | `B552657/HsptlAsembySearchService/getHsptlMdcncListInfoInqire` | 병·의원 목록. **`dutyTime1s`~ 주간 진료시간을 바로 줍니다.** 단 **좌표 검색이 아닙니다**(`Q0` 시도 / `Q1` 시군구). 좌표만 주면 78,628건 전부 |
  | `B552657/ErmctInfoInqireService/getEgytLcinfoInqire` | **응급실 좌표 검색.** `distance`(km), `latitude`/`longitude`, `dutyTel1`. 응급 선별(SA-03)이 걸렸을 때 가장 가까운 응급실을 보여줄 수 있습니다 |

  > **그래서 지금 병원은 전부 됩니다.** 위치·이름·전화·종별·거리·**진료시간**까지.
  > 다만 시간표가 없는 기관은 여전히 `isOpenNow: null`(모름)이어야 합니다. `false`(닫힘)로 단정하면 §2-13에서 고친 그 버그를 되풀이하는 것입니다.

  > **진단 도구.** data.go.kr 게이트웨이는 `401` = 키를 모름, `403` = 키는 알지만 이 서비스에 미승인, `404` = 경로/오퍼레이션 없음, `500 Unexpected errors` = 존재하지 않는 서비스 경로로 라우팅 실패(**틀린 키로도 500**이므로 인증 이전 단계)로 답합니다. 이 네 가지를 구분하면 승인을 기다리지 않고도 경로가 맞는지 알 수 있습니다.

**교정:** 백엔드가 Haversine 거리 + KST 시각/요일/공휴일 비교로 구현합니다. **야간 약국은 자정을 넘깁니다**(`2100~0200`) — 오늘 행만 보면 새벽 1시의 야간 약국을 통째로 놓칩니다.

**미해결:** 좌표 조회의 `startTime`/`endTime`이 "오늘의 영업시간"인지 "대표 영업시간"인지 확인되지 않았습니다. 확정 전에는 `getParmacyBassInfoInqire`의 주간 시간표를 신뢰하세요.

### 2-10. **[신규] DUR 품목정보 API 추가**

식약처 **DUR 품목정보** (`1471000/DURPrdlstInfoService03`, data.go.kr 15059486). 오퍼레이션 9개 전부 확인:

| 오퍼레이션 | 용도 | 관련 페르소나 |
|---|---|---|
| `getUsjntTabooInfoList03` | 병용금기 | 사후피임약, 복수 복용 |
| `getSpcifyAgrdeTabooInfoList03` | 특정연령대금기 | 14세 자녀 |
| `getPwnmTabooInfoList03` | 임부금기 | 임신 가능성 |
| `getOdsnAtentInfoList03` | 노인주의 | 68세 독거 남성 |
| `getCpctyAtentInfoList03` | 용량주의 | |
| `getMdctnPdAtentInfoList03` | 투여기간주의 | |
| `getEfcyDplctInfoList03` | 효능군중복 | |
| `getSeobangjeongPartitnAtentInfoList03` | 서방정분할주의 | |
| `getDurPrdlstInfoList03` | DUR 품목 메타데이터 | |

**왜 이게 안전성을 높이는가:** 정부가 고시한 금기 정보를 그대로 전달하므로, "이 약 드세요"(의료 조언)가 아니라 **"이 약에는 12세 미만 투여 금기가 고시돼 있습니다. 약사에게 확인하세요"**(정보 제공)가 됩니다. §7의 정책 리스크를 줄입니다.

**`ITEM_SEQ`가 마스터 조인 키입니다.** 품목기준코드가 e약은요·허가정보·DUR 셋에서 동일합니다.

```
허가정보 (성분)  ─┐
e약은요 (안내문)  ├─ ITEM_SEQ ─→ 하나의 약
DUR (금기·주의)  ─┘
```

### 2-11. **[신규] `map` 필드 → `ui_actions[]`** (Sol Pro 채택)

v1의 단일 `map` 필드를 Sol Pro의 액션 배열로 일반화합니다. allowlist:

`OPEN_FACILITY_MAP` · `APPLY_FACILITY_FILTERS` · `OPEN_DRUG_DETAIL` · `SHOW_EMERGENCY_CALL` · `ASK_CLARIFYING_QUESTION`

특히 `SHOW_EMERGENCY_CALL`은 후처리 불변조건 4번(§2-15)과 맞물립니다.

### 2-12. **[신규] 알레르기 판정 4-state** (Sol Pro 채택)

v1의 `allergyWarning: string | null`은 **"경고 없음"과 "확인 불가"가 같은 `null`**이었습니다. 알레르기가 있는 사람에게 그 둘은 완전히 다른 말입니다.

| 상태 | 의미 |
|---|---|
| `blocked` | 정확 일치 또는 검증된 동의어 일치 |
| `warning` | 부분 일치, 복합 성분, 변환 불확실 |
| `no_match_found` | 제공된 성분 목록에서 일치 없음. **안전 보장이 아님** |
| `unknown` | 성분 또는 입력을 비교할 수 없음 |

**금지사항** (Sol Pro Part 6.5):
- LLM만 사용해 성분 동의어를 자동 확정하지 않습니다.
- 철자가 비슷하다는 이유만으로 `blocked` 처리하지 않습니다.
- **`no_match_found`를 "복용 가능"으로 표시하지 않습니다.**

> **알려진 한계 — 약물 계열 교차반응 (2026-07-10 실측. 2026-07-10 원인 재특정 + 조치 반영).**
> "두통이 있는데 이부프로펜 알레르기가 있어요"에 실제로 아세트아미노펜 2종과 **나프록센 1종**이 나왔습니다.
> 우리 판정은 **성분 단위 정확 일치(+검토된 동의어)** 이므로 나프록센은 `no_match_found`입니다. 그 표시 자체는
> 정직합니다 — "일치 없음, 보장 아님, 약사에게 확인하세요". 하지만 **이부프로펜과 나프록센은 같은 NSAID 계열**이고,
> 한쪽에 반응하는 사람이 다른 쪽에도 반응하는 경우가 임상적으로 알려져 있습니다.
>
> **원인은 `AllergyChecker`가 아닙니다.** 나프록센을 고른 것은 `SearchTermExtractor`의 LLM입니다. 그 프롬프트는
> "증상에 맞는 OTC 성분을 넣되, 알레르기라고 말한 성분은 빼라"고 지시하고, 모델은 이부프로펜을 빼는 대신
> **자기 약리학 지식으로 대체재를 골라 넣습니다.** 검색·병합·검증 계층은 전부 정직하게 동작했습니다.
> 불변조건 6번은 *존재하지 않는 제품명*을 막지, *부적절한 선택*을 막지 못합니다. 나프록센은 실존합니다.
> **이 파이프라인의 유일한 임상 판단을 모델이 내리고 있었습니다.**
>
> 계열 교차반응 표는 **임상 지식**이며, 개발자도 LLM도 지어내서는 안 됩니다. 그리고 표만으로는 부족합니다 —
> **닫힌 표로 열린 생성기를 막을 수 없습니다.** 완벽히 서명된 NSAID 표가 있어도 모델이 표에 없는 계열을
> 제안하면 그대로 통과하고, 표의 존재 자체가 "우리는 교차반응을 본다"는 잘못된 안심을 줍니다.
>
> **조치 (2026-07-10, 임상 검토자 부재 하에 채택):** 표를 만들지 않고 **생성기를 묶었습니다.**
> `AllergyDeclaration`이 걸리면 (`mermaid.exclude_ingredients`가 비어 있지 않거나, 사용자 문장이 알레르기를
> 선언하면) `DrugContextRetriever`가 **모델이 제안한 성분을 전부 버립니다.** 사용자가 직접 타이핑한 제품명만
> 남습니다. 이는 임상 판단이 아니라 **범위 축소**이므로 검토자가 필요 없습니다. 수용된 위험은 §7 AR-01.
>
> **여전히 남는 것:** "이부프로펜 먹으면 두드러기가 나요"처럼 알레르기라는 단어 없이 선언하면 규칙이 걸리지
> 않습니다. 다만 그 경우 우리는 알레르기를 **애초에 알지 못하므로**, 서명된 계열 표가 있었어도 막지 못했습니다.
> 구멍은 우리가 아는 것이 아니라 **듣는 것**에 있습니다. 규칙은 영어 전용입니다 (TODO DEV-405).
>
> 검토자가 확보되면 ① 서명된 계열 표를 `ingredients/`에 추가해 `warning`(차단 아님)을 띄우는 안이 여전히
> 유효합니다. 단, **생성기 제한을 대체하는 것이 아니라 그 위에 얹는 것**이어야 합니다.

### 2-13. **[신규] `is_open_now`를 nullable로 — v1 코드의 버그**

v1의 `Facility.openNow`는 `boolean`이라 **운영시간 데이터가 없는 약국을 "닫힘"으로 단정**합니다. 교정:

```
is_open_now:       boolean | null
status:            open | closed | unknown
status_confidence: official_realtime | official_schedule | inferred | unknown
verified_at:       ISO 8601
```

우리는 시간표에서 계산하므로 대개 `official_schedule`입니다. **"실시간 운영 보장"이라고 말하지 않습니다** (Sol Pro NORM-003).

`open_now=true` 질의의 기본 정책은 **`status=open`만 반환**입니다. `unknown`을 노출하려면 별도 UI 구역에 "운영 확인 필요"를 붙입니다.

### 2-14. **[신규] `data_mode: live | hybrid | fixture`** (Sol Pro 채택)

- `live` — 공공 API 실패 시 오류 표시
- `hybrid` — 실패 시 fixture로 자동 fallback (로그·UI 메타데이터에 기록)
- `fixture` — 네트워크 없이 동작

**약국 API는 개발계정 하루 1,000회입니다.** 네 명이 개발하면 점심 전에 소진됩니다. 그리고 발표 당일 공공 API가 죽어도 `fixture`로 시연이 굴러갑니다.

**fixture를 live로 위장하지 않습니다.** 모든 사실 카드에 `source{provider, record_id, retrieved_at, data_mode}`를 붙이고 화면에 표시합니다.

### 2-15. **[신규] 서버 후처리 불변조건** (Sol Pro 채택)

JSON Schema로는 못 잡는 교차검증을 **코드로** 합니다. 스키마를 통과했지만 **내용이 날조된** 응답을 여기서 거부합니다.

1. 모든 `drug.source_ref_id`는 `source_refs[].id`에 존재해야 한다
2. `allergy_check.status=blocked`면 `matched_ingredients`가 1개 이상이어야 한다
3. `data_status=live`면 모든 사실 source가 `data_mode=live`여야 한다
4. **`urgency.level=emergency`면 `SHOW_EMERGENCY_CALL` 액션이 있어야 한다**
5. `evidence=official_data`인 guidance는 `source_ref_ids`가 비어 있으면 안 된다
6. **`product_name_ko`·성분·기관 정보는 우리가 조회한 정규화 레코드와 일치해야 한다** ← 환각 차단의 코어
7. 모델이 반환한 임의 URL·HTML·script는 허용하지 않는다

위반 시 `AI_SCHEMA_INVALID`로 safe fallback (§7).

### 2-16. **[신규] 채팅은 `sessionStorage`** (Sol Pro NORM-005 채택)

원본은 *"LocalStorage에 보관하여 보안 이슈를 원천 차단"*이라 했지만, **LocalStorage도 기기 접근자와 XSS에 노출됩니다.** 채팅은 `sessionStorage`(탭 종료 시 소멸) 또는 메모리, 즐겨찾기만 `localStorage`.

브라우저 저장소는 `schema_version`을 갖고, 손상 시 앱 크래시 대신 백업 후 초기화합니다.

### 2-17. **[신규] UI 컴포넌트 `facebook/astryx` 0.1.4** (버전 고정)

Meta의 오픈소스 디자인 시스템. **React 19를 요구**(우리와 일치)하고 **Vite에 플러그인이 필요 없으며**(미리 빌드된 CSS 배포) **Tailwind v4와 cascade layer로 공존**합니다.

- 얻는 것: 150+ 접근성 내장 컴포넌트, 한국어 IME 조합 처리, 기계 판독 문서(`docs.mjs`)
- 리스크: **0.1.4 베타, 공개 릴리스 2주** — `^` 없이 정확히 고정하고 **발표 전까지 업그레이드하지 않습니다**
- 갭: **바텀시트 컴포넌트가 없습니다.** UI-02의 하단 슬라이드 목록은 `Overlay`/`Layer`로 직접 조립

---

## 3. 검증된 외부 제약 (구현 전 반드시 읽을 것)

> **2026-07-10, 실제 키로 모든 API를 한 번씩 호출했습니다.** 아래는 문서가 아니라 응답에서 나온 사실입니다.
> 실제 응답은 `backend/src/main/resources/fixtures/`에 있습니다. **그것으로 개발하세요.**

### 3-0. 실측이 조사 문서를 뒤집은 것들

| 항목 | 조사 문서 | 실제 |
|---|---|---|
| 응답 봉투 | "균일하다" | **두 종류.** 식약처(`1471000/…`)는 최상위 `{header, body}`. 약국·심평원(`B5…`)은 `{response:{header, body}}` |
| 약국 좌표 조회 | "주간 시간표를 함께 반환" | **아니다.** `startTime`/`endTime`뿐. 시간표는 `getParmacyBassInfoInqire`에 |
| `distance` | "있음(단위 미상)" | **km 단위** (`0.14` = 140m) |
| `MAIN_INGR_ENG` | "목록 조회에 있음" | **상세 조회(`…Dtl Inq06`)에만** |
| 상세 오퍼레이션 | `getDrugPrdtPrmsnDtlInq07` | **`…Inq06`** (07은 404) |
| 필드 타입 | (언급 없음) | **한 응답 안에서 섞임.** `"dutyTime1s": "0900"` (문자열) / `"dutyTime1c": 1900` (정수) |
| LLM `[DONE]` | "스트림 종료" | 맞다. 단 opencode zen은 그 직전에 `{"choices":[],"cost":…}` 청크를 하나 더 보낸다 |
| 아스피린 성분명 | (우리 사전) `Acetylsalicylic Acid` | **허가정보엔 0건.** 실제 표기는 `Aspirin` (119건). `toSearchTerm("Aspirin")`이 존재하지 않는 문자열을 내놓아 성분 검색이 빈 목록. 표기법 사실이지만 사전 행이라 서명 전엔 못 고친다 → **DEV-309 / §7-1 AR-02** |

### 3-1. LLM 엔드포인트 (opencode zen, 실측)

| 항목 | 사실 |
|---|---|
| **User-Agent** | **Cloudflare error 1010이 익명 클라이언트를 차단합니다.** `Python-urllib/3.14` → 403, curl → 200. `WebClient`에 UA를 명시하지 않으면 **백엔드 전체가 403**입니다 |
| `response_format` | **모델마다 다릅니다.** `glm-5.2`는 `MermAidAnswer` 전체 스키마(`$defs`/`$ref`/`const`/nullable union)를 strict로 통과시킴 — 실측. `deepseek-v4-flash`는 동일 요청에 **HTTP 400**. `minimax-m3`는 200이지만 `<think>` 추론을 평문으로 반환. → **허용목록**(`mermaid.llm.structured-output-models`)이며, 400이 나면 스키마 없이 1회 재시도 (DEV-102) |
| 권장 모델 | **`glm-5.2`** — FR-03을 진짜로 강제할 수 있는 몇 안 되는 모델 |
| 응급 인지 | **모델을 믿을 수 없습니다.** `deepseek-v4-flash`는 "crushing chest pain, cannot breathe"에 `urgency: unknown`을 반환했습니다. → SA-03의 규칙 기반 선별이 **필수** |

| 항목 | 사실 | 대응 |
|---|---|---|
| 약국 API 한도 | 개발계정 **1,000회/일** — 가장 빡빡 | Redis 캐시 + fixture 모드 필수 |
| 심평원 병원정보 | **HTTP 403 Forbidden** (2026-07-10 기준) | **활용신청 승인 필요.** 윤서진 확인 |
| 기타 한도 | 심평원 10,000 / e약은요 10,000 / **DUR 10,000** / 허가정보 100,000 | |
| serviceKey 인코딩 | Encoding 키를 `UriComponentsBuilder`에 넣으면 `%`→`%25` 이중 인코딩 → `SERVICE_KEY_IS_NOT_REGISTERED_ERROR` | **Decoding 키** + `.build(true).toUri()` |
| JSON 파라미터명 | 약국·심평원 `_type=json` / **식약처 3종(e약은요·허가정보·DUR) `type=json`** | 언더스코어 하나 차이로 조용히 XML |
| DUR 성분코드 | DUR `INGR_CODE`는 `D######`. 허가정보 `MAIN_ITEM_INGR`은 `[M######]`. **다른 코드 체계라 조인 안 됨** | 성분 조인은 DUR의 `MAIN_INGR`(M-코드) 사용. 제품 조인은 `ITEM_SEQ` |
| DUR 병용금기 | **두 약을 한 번에 묻는 엔드포인트가 없음** | 약 A로 조회 → `MIXTURE_ITEM_SEQ` 목록에서 약 B 탐색 |
| DUR 연령·임부 | **구조화된 나이 필드가 없음.** "12세 미만"은 `PROHBT_CONTENT` 한국어 자유 텍스트 안 | 기계 판독 임계값이 필요하면 DUR **성분정보** 서비스(15056780)의 `AGE_BASE` |
| CORS | 공공 API는 CORS 헤더 없음 | 서버에서만 호출 (설계상 이미 그러함) |
| 네이버맵 키 | 파라미터는 `ncpKeyId`, 그 자리에 들어가는 값은 콘솔의 **Client ID**. 호스트 `oapi.map.naver.com` | 인터넷 예제 대부분이 구버전(`ncpClientId`) |
| 네이버맵 **Client Secret** | **브라우저로 보내지 않습니다.** 지도 SDK는 쓰지 않고, 서버가 지오코딩·길찾기를 부를 때 `X-NCP-APIGW-API-KEY` 헤더로만 씁니다 | `VITE_` 접두사를 붙이면 **번들에 그대로 박힙니다.** 실제로 한 번 그랬고, 시크릿을 폐기했습니다 |
| 네이버맵 영어 | `&language=en` → 지도 라벨 전체 영어화 | 카카오엔 없는 기능 |
| 네이버맵 무료량 | "대표 계정" **1개**에만 (개인은 전화번호당 1계정) | 기존 계정 이력이 있으면 첫 호출부터 과금될 수 있음 |

#### 네이버맵 키는 curl로 검증할 수 없습니다 (실측, 2026-07-10)

일부러 틀린 키로 실제 브라우저에서 관측한 것:

1. `maps.js`가 **200**으로 내려옵니다. 정상 키와 **바이트 수까지 같습니다**(333,436).
2. `script.onload`가 **발동합니다.**
3. `window.naver.maps`가 **정의됩니다.**
4. `new naver.maps.Map(...)`이 **예외 없이 생성됩니다.**
5. **그 뒤에야** SDK가 `window.navermap_authFailure()`를 호출합니다.

서버에서 할 수 있는 검사는 전부 통과합니다. **그 콜백만이 유일한 신호**이며, 스크립트를 붙이기 **전에** 등록해야 합니다. 무시하면 사용자는 회색 빈 상자를 보고 "지도가 로딩 중"이라고 생각합니다.

에러 코드로 원인을 가릴 수 있습니다.

| 파라미터 | 값 | 에러 |
|---|---|---|
| `ncpKeyId` | 인식 못 하는 값 | `500 / Internal Server Error` |
| `ncpClientId` | (구 파라미터) | `200 / Authentication Failed` |

**진단법:** 같은 길이의 무작위 문자열로 한 번 더 불러보세요. 에러가 **똑같으면** 키 값 자체가 인식되지 않는 것이고(잘못된 값·잘못된 종류), **다르면** 키는 인식되되 URL allowlist 문제입니다.
| 상류 SSE 종료 | `data: [DONE]` — **JSON이 아님** | 파싱 전에 필터링 |
| `MermAidAnswerV1` 스키마 | `$defs`·`$ref`·`const`·nullable union | **검증용 스키마 ≠ 프로바이더 강제용 스키마.** 별도 파일(`schemas/mermaid-answer.provider.schema.json`)이며, 서버 런타임 검증기가 여전히 source of truth |
| DNS (macOS/Apple Silicon) | Gradle이 `netty-resolver-dns-native-macos`의 **`osx-x86_64`** 빌드를 끌어옴 → arm64에서 로드 실패 → Netty가 AAAA를 먼저 씀 → IPv6 경로 없는 호스트에서 `No route to host` | JDK 리졸버(`DefaultAddressResolverGroup`)로 교체. curl은 IPv4로 폴백하지만 Netty는 안 함 |
| astryx | 0.1.4 베타, breaking change 있음 | `^` 없이 고정. 발표 전 업그레이드 금지 |

**[NEEDS CLARIFICATION]** 확정하지 못한 것들 (개발 시작 시 실제 호출로 확인):
- 네이버맵 무료 이용량 수치와 결제수단 등록 강제 여부 → NCP 콘솔
- ~~심평원 상세정보 서비스 버전 접미사 (`2.7` vs `2.8`)~~ → **해결: `MadmDtlInfoService2.8/getDtlInfo2.8`** (2026-07-10 실측 200)
- ~~심평원 두 서비스의 실제 응답 필드~~ → **해결.** `fixtures/hospital_list.json`, `hospital_detail.json`
- DUR 벌크 CSV(HIRA `15127983`)의 실제 컬럼 구성 → 다운로드 전 신뢰 금지
- DUR `PROHBT_CONTENT` 한국어 텍스트를 영어권 사용자에게 어떻게 제시할지

---

## 4. 데이터 모델 (3층)

### 4-1. `sessionStorage` — 탭이 닫히면 사라짐
- `chatSession` — 의료 상담 대화. **서버로 가지 않습니다.**
- `sessionAllergies` — 이번 대화에서 말한 기피 성분 (기본값)

### 4-2. `localStorage` — 사용자가 지울 때까지
- `deviceId` — UUID, 최초 진입 시 생성
- `savedFacilities` — 즐겨찾기 스냅샷 (표시용. 상세 열 때 live 재조회)
- `preferences` — `rememberAllergies: false` 기본. opt-in 시에만 서버 동기화

### 4-3. 서버 DB (JPA → ERD·테이블 명세서의 원본)

```
user_profile (1) ──< (N) allergy_ingredient      [opt-in 시에만 채워짐]
      │
      └──< (N) favorite_facility
```

| 테이블 | 주요 컬럼 | 비고 |
|---|---|---|
| `user_profile` | `id` PK, `device_id` UK, `country_code`, `remember_allergies` | 계정 아님. 익명 UUID |
| `allergy_ingredient` | `id` PK, `profile_id` FK, `ingredient_name_en`, `ingredient_name_ko`, `normalized_key` | `MAIN_INGR_ENG`와 매칭 |
| `favorite_facility` | `id` PK, `profile_id` FK, `facility_id`, `facility_type`, `alias`, `memo` | `facility_id`는 namespace 포함 |

**외부 ID는 provider namespace를 붙입니다** (Sol Pro Part 6.4):

```
facility:nmc:12345        (국립중앙의료원 hpid)
facility:hira:<ykiho>     (심평원)
drug:mfds:200000001       (식약처 ITEM_SEQ)
```

이름·주소를 ID로 쓰지 않습니다. 공급자 record ID가 없으면 영구 참조 대상으로 쓰지 않습니다.

### 4-4. Redis
공공 API 응답 캐시. 도메인 테이블이 아니므로 ERD에 넣지 않습니다.

### 4-5. CRUD 시연 매핑 (채점 필수 요소)

| | 대상 | 엔드포인트 |
|---|---|---|
| **C** | 즐겨찾기 추가, (opt-in) 알레르기 추가 | `POST /api/v1/profiles/{deviceId}/favorites` |
| **R** | 프로필·즐겨찾기 조회 | `GET /api/v1/profiles/{deviceId}` |
| **U** | 즐겨찾기 별칭·메모 수정 | `PATCH .../favorites/{id}` |
| **D** | 즐겨찾기 해제, 알레르기 삭제 | `DELETE .../favorites/{id}` |

---

## 5. API 계약

### 5-1. 공통

```http
X-Request-Id: <uuid>
X-Data-Mode: live | hybrid | fixture
```

시각은 ISO 8601 UTC. 거리는 meter. 좌표는 WGS84. **모르는 값은 추측하지 않고 `null`.** 빈 결과(성공)와 공급자 오류를 구분합니다.

### 5-2. 오류 Envelope

```json
{ "error": { "code": "SOURCE_UNAVAILABLE", "message": "...", "retryable": true,
             "request_id": "...", "details": { "source": "facility" } } }
```

`details`에 민감정보·스택트레이스를 넣지 않습니다.

| Code | HTTP | Retryable |
|---|---:|---|
| `INVALID_REQUEST` | 400 | false |
| `INPUT_TOO_LARGE` | 413 | false |
| `UNSUPPORTED_MODEL` | 400 | false |
| `RATE_LIMITED` | 429 | true |
| `LOCATION_REQUIRED` | 400 | false |
| `RESOURCE_NOT_FOUND` | 404 | false |
| `AI_PROVIDER_TIMEOUT` | 504 | true |
| `AI_PROVIDER_ERROR` | 502 | true |
| `AI_SCHEMA_INVALID` | 502 | true |
| `FACILITY_PROVIDER_TIMEOUT` | 504 | true |
| `DRUG_PROVIDER_TIMEOUT` | 504 | true |
| `SOURCE_UNAVAILABLE` | 503 | true |
| `SOURCE_PAYLOAD_INVALID` | 502 | true |
| `INTERNAL_ERROR` | 500 | false |

### 5-3. 엔드포인트

| Method | URL | 비고 |
|---|---|---|
| POST | `/api/v1/chat/completions` | OpenAI 호환. `stream=true`도 **검증된 답변 1청크**로 옵니다. 비표준 확장 필드 `mermaid.exclude_ingredients[]`로 알레르기를 받습니다 |
| GET | `/api/v1/facilities` | `?lat=&lng=&radius_m=&open_now=&type=&limit=&sort=` |
| GET | `/api/v1/facilities/{id}` | `facility:nmc:12345` |
| GET | `/api/v1/drugs` | `?query=&ingredient=&prescription_status=&limit=` |
| GET | `/api/v1/drugs/{id}` | e약은요 + 허가정보 + DUR 병합 |
| GET/POST/PATCH/DELETE | `/api/v1/profiles/{deviceId}/**` | CRUD |
| GET | `/api/v1/health` | 비밀키·스택트레이스 노출 금지 |

> `DELETE /api/v1/drugs/{id}`는 **없습니다** (§2-7).

### 5-4. `MermAidAnswerV1`

Sol Pro의 `docs/03_API_CONTRACT.md` §3 스키마 전문을 그대로 채택합니다. 필수 필드:

```
schema_version, answer_id, language, data_status, urgency, summary,
clarifying_questions, guidance, drugs, ui_actions, source_refs, warnings, disclaimer
```

`drugs[]`에는 `allergy_check{status, matched_ingredients, message}`와 `source_ref_id`가 필수입니다. **DUR 경고는 `drugs[].warnings[]`에 넣고, 근거를 `source_refs[]`에 DUR 레코드로 남깁니다.**

**스트리밍은 토큰 단위 중계를 하지 않습니다** (2026-07-10 변경). `stream=true`는 **검증을 마친 답변 하나를 SSE 청크 하나로** 보냅니다. `openai` SDK는 동일하게 읽습니다.

이유: 후처리 불변조건은 JSON이 완성돼야 돌릴 수 있는데, 그때는 앞 청크가 이미 브라우저에 도착해 있습니다. 즉 **스트리밍된 의약품 추천은 검증 없이 사용자에게 닿습니다**. 점진적 렌더링은 기능이고, 검증되지 않은 약은 결함입니다. 토큰 스트리밍을 되살리려면 버퍼링 후 검증을 먼저 붙여야 합니다.

---

## 6. 요구사항

원본 FR 번호를 유지하고, DUR을 FR-07로 신설합니다.

- **FR-01** (Must): 로그인 없이 챗 개시. `openai` SDK가 `baseURL` 교체만으로 붙는다.
- **FR-02** (Must): 좌표·반경·기관 종류·현재 영업 여부로 필터링. **반경과 영업 여부는 백엔드가 계산** (§2-9). `unknown`을 `closed`로 말하지 않는다 (§2-13).
- **FR-03** (Must): `MermAidAnswerV1` 스키마 강제 + **서버 후처리 불변조건** 통과 (§2-15). 지도는 `ui_actions[]`.
- **FR-04** (Must): 기피 성분과 `MAIN_INGR_ENG`를 매칭해 4-state 판정 (§2-12). **허가정보 API 필요** (§2-8). **계열 교차반응은 판정하지 않으며**, 알레르기가 선언된 턴에는 LLM이 대체 성분을 제안하지 못한다 (SA-08, 수용된 위험 AR-01).
- **FR-05** (Should): 국가별 의료 시스템 차이 안내. **검수된 정적 콘텐츠만.** LLM이 법·제도를 생성하지 않는다.
- **FR-06** (Could, **MVP 제외**): 시니어 데일리 케어 + 보호자 FCM.
- **FR-07** (Should, **신규**): DUR 금기·주의 정보를 의약품 카드에 결합 (§2-10). 병용금기, 연령금기, 임부금기, 노인주의.

**비기능:** 원본 NFR-01~05 유지. NFR-01은 §2-3대로 교정.

---

## 7. 안전 및 정책 제약

OpenAI 이용정책은 *"면허가 필요한 맞춤형 조언(의료 조언 등)을 유자격 전문가의 적절한 관여 없이 제공하는 것"*을 금지합니다. Google의 생성형 AI 금지사용 정책도 자격 있는 인간의 감독 없는 의료 진단·조언을 제한합니다.

우리 서비스는 증상을 듣고 의약품을 제시하며, 페르소나에는 사후피임약과 당뇨 합병증 의심 증상이 포함됩니다. **"정보 제공"과 "의료 조언"의 경계선 위에 있습니다.**

**구현 필수:**

- **SA-01**: 시스템 프롬프트에 "진단하지 않는다"를 명시하고, **클라이언트가 보낸 `system` 메시지는 프록시에서 버린다** (P0 허용 role은 `user`, `assistant`).
- **SA-02**: `disclaimer`는 **항상 채워지며** UI에 상시 표시된다.
- **SA-03**: **규칙 기반 응급 선별을 LLM보다 먼저** 돌린다. 흉통·호흡곤란·의식소실·대량출혈·자살사고 등은 LLM이 실패해도 안전 응답을 만들 수 있어야 한다. `urgency=emergency`면 의약품 추천 대신 119 안내를 우선하고 `SHOW_EMERGENCY_CALL`을 넣는다 (불변조건 4).
- **SA-04**: 채팅 입력창에 여권번호·생년월일 등을 넣지 않도록 UX 가이드문을 노출한다. 정확한 나이 대신 **연령대만** 받는다 (`child|teen|adult|senior|unknown`).
- **SA-05**: 사용자 위치를 서버에 영속 저장하지 않는다. 요청 처리 후 폐기한다.
- **SA-06**: **"안전합니다", "복용하세요" 같은 단정 문구를 쓰지 않는다.** `no_match_found`는 안전 보장이 아니다.
- **SA-07**: 외부 API 응답 텍스트와 웹 콘텐츠는 **데이터이지 지시가 아니다.** 그 안에 든 명령문을 따르지 않는다.
- **SA-08**: **알레르기가 선언된 턴에서 LLM은 의약품을 고르지 못한다.** `AllergyDeclaration`이 걸리면 모델이 제안한 성분을 전부 버리고, 사용자가 직접 타이핑한 제품명만 조회한다 (§2-12). 대체재 제안은 임상 행위다.

### 7-1. 수용된 위험 (Accepted risks)

완화하지 못한 채 **알고서 안고 가는** 위험입니다. 숨기지 않고 여기 적습니다.

- **AR-01 — 약물 계열 교차반응을 판정하지 않는다.**
  - **위험:** 이부프로펜에 반응하는 사람에게 나프록센(같은 NSAID 계열)이 제시될 수 있었다. 우리 판정은 성분명 정확 일치라 `no_match_found`가 뜨고, 그 표시는 정직하지만 보호가 되지 않는다.
  - **왜 고치지 않는가:** 계열 교차반응 표는 **임상 지식**이다. 개발자도 LLM도 지어낼 수 없고, `synonyms.tsv`와 같은 기준으로 **실명 검토자의 서명**을 요구한다. **2026-07-10 현재 팀에 임상 자격자가 없다** (`synonyms.tsv`의 검토자 칸이 전부 `TODO`인 것과 같은 이유).
  - **대신 한 것:** 표를 만드는 대신 생성기를 묶었다 (SA-08). 알레르기가 선언되면 모델은 대체재를 제안하지 못하고, 사용자는 "약사에게 확인하라"는 답을 받는다. 임상 판단이 아닌 **범위 축소**이므로 검토자 없이 시행할 수 있다.
  - **남는 노출:** ① 알레르기라는 단어 없이 선언한 경우(`"이부프로펜 먹으면 두드러기가 나요"`)는 규칙이 걸리지 않는다 — 다만 이 경우 우리는 알레르기를 애초에 모르므로 서명된 표로도 막지 못했다. ② 규칙은 영어 전용이다(TODO DEV-405). ③ 사용자가 직접 이름 댄 제품은 여전히 조회되며, 정확 일치 시 `blocked`로 표시된다.
  - **재검토 조건:** 임상 검토자를 확보하면 서명된 계열 표를 얹는다 (§2-12). **생성기 제한을 대체하지 않고 그 위에 얹는다.**
  - **승인:** 윤서진, 2026-07-10.

- **AR-02 — `synonyms.tsv` 7행이 전부 미서명인 채로 차단을 만들고 있다.**
  - **위험:** §2-12는 "**검증된** 동의어"만 `blocked`를 만들 수 있다고 규정하지만, 검토자 칸이 전부 `TODO`다.
  - **정정 (2026-07-10):** 이 항목의 초안은 "미서명 행이 차단하는 것"을 위험으로 적었다. **틀렸다.** 코드를 읽어보니 반대다 — **행을 빼는 쪽이 위험하다.** `paracetamol → acetaminophen`이 없으면 파라세타몰 알레르기가 `Acetaminophen`에 아예 매칭되지 않고, `dexibuprofen → ibuprofen`이 없으면 `\b` 단어 경계가 `"dexibuprofen"` 안의 `"ibuprofen"`을 보지 못한다. 두 경우 모두 알레르기 환자에게 그 약이 **제시된다.** 이 행들은 보호 장치다.
  - **그러므로 진짜 위험은 틀린 행이 아니라 _없는 행_이다.** 검토자 칸이 `TODO`라는 건 어느 쪽인지 아무도 확인하지 않았다는 뜻이다.
  - **한 것:** `putRow()`가 검토자 칸을 **읽지도 않고 버리던** 것을 고쳤다. 이제 `TODO`·공란은 서명이 아니며(`IngredientNormalizer.isSigned`), 미서명 행은 **매 부팅 WARN에 이름이 찍힌다.** **동작은 불변** — 미서명 행도 계속 차단한다(강등이 위험한 방향이므로). `dexibuprofen` 행의 근거란은 화학적 동일성(이부프로펜의 S-거울상)으로 다시 썼다. **계열 교차반응은 근거가 아니다.**
  - **남은 것 — 서명. 에이전트가 대신할 수 없다.** 그 칸의 유일한 의미가 "사람이 확인했다"이기 때문이다. 한결이 이름을 채우면 그 칸은 그 순간부터 아무 뜻도 없어진다.
  - **상태: 백로그 `DEV-309`.** 수용된 위험이 아니라 **처리 중인 과제**다 — 함께 검토·결정한 뒤 서명하면 닫힌다. 그래서 이 항목은 요구사항 명세서(`.docx`)의 "수용된 위험" 표에 **올리지 않는다.** 올리면 "안고 가기로 했다"는 뜻이 되어 서명 압력이 사라진다. **서명 전까지 행을 추가하지도 지우지도 않는다** (지우는 쪽이 위험한 방향이므로).
  - **준비 완료 (2026-07-10):** 허가정보 실호출 21회로 세 계열의 모든 성분 표기를 열거해 [DEV-305 검토 시트](DEV-305-synonym-review.md)에 정리했다. 검토자는 조사하지 않고 판정만 하면 된다. 스윕에서 드러난 것:
    - **경고 없이 통과하는 표기 4종** — `Dexibuprofen D.C.`(24), `Aspirin Enteric Pellets`(27), `Aspirin Enteric Granules`(2), `Aspirin Lysine For Injection 90%`(1). 알레르기가 있어도 아무 표시가 붙지 않는다.
    - **경고만 뜨고 차단되지 않는 표기 4종** — `Microencapsulated Acetaminophen`(12), `Ibuprofen Piconol`(22), `Ibuprofen Sodium Dihydrate`(1), `Ibuprofen Encapsulated`(1).
    - **표준명이 거꾸로 있는 버그** — 허가정보에 `Acetylsalicylic Acid`는 **0건**, `Aspirin`은 119건이다. `toSearchTerm("Aspirin")`이 전자를 내놓으므로 `GET /api/v1/drugs?ingredient=Aspirin`은 **빈 목록**이고 RAG는 아스피린 제품을 못 가져온다. 표기법 사실이므로 확인은 쉽다. 시트 ①.
    - `Salicylic Acid`(32)는 아세틸살리실산이 **아니다.** 프로브에 우연히 걸린 다른 물질이고, 아스피린 알레르기와의 관계는 AR-01의 계열 문제라 우리가 판정하지 않는다. 행을 추가하지 않는다.

---

## 8. User scenarios

### 야간에 아픈 외국인 여행자 (P1)
- **Given** 로그인 없이 접속, 기피 성분 미등록
- **When** "I have a sore throat and fever, it's 11pm"
- **Then** 서버가 식약처 API를 조회한 뒤 스키마·불변조건을 통과한 JSON을 반환하고, `ui_actions`에 `OPEN_FACILITY_MAP{types:[pharmacy], open_now:true}`가 담겨 지도가 열린다. `disclaimer`가 화면에 있다.

### 알레르기가 있는 보호자 (P1, **2026-07-10 개정 — AR-01**)
- **Given** 세션에 `ibuprofen`을 기피 성분으로 입력
- **When** "My 14-year-old has a fever, what can I buy?"
- **Then** `mermaid.exclude_ingredients`가 비어 있지 않으므로 **모델은 성분을 제안하지 못한다** (SA-08). `drugs`는 빈 배열이고, 답변은 **어떤 의약품 이름도 말하지 않으며**, "알레르기가 있는 분께는 대체약을 제시할 수 없고 약사가 도울 수 있다"고 밝힌 뒤 약국 지도를 띄운다.
- **개정 이유** — 이전 판의 기대값은 "아세트아미노펜 **계열** 대체 약이 제시된다"였습니다. 그 한 줄이 곧 계열 수준 임상 판단을 요구하고 있었고, 실제로 그 판단을 내린 것은 LLM이었습니다 (§2-12). 대체재를 고르는 일은 약사의 것입니다.

### 알레르기가 있는 사용자가 제품을 직접 지목 (P1, **신규**)
- **Given** 세션에 `ibuprofen`을 기피 성분으로 입력
- **When** "Can I take 부루펜?"
- **Then** 사용자가 **직접 타이핑한 제품명**은 살아남아 조회된다. 부루펜정은 `allergy_check.status=blocked` + `matched_ingredients=["Ibuprofen"]`으로 표시되고, **DUR 연령금기가 있으면 `warnings[]`에 결합.** 모델은 대체재를 암시조차 하지 못한다.
- 묻는 것에 답하지 않으면 안전한 것이 아니라 쓸모없는 것입니다. 막는 것은 **모델의 제안**이지 사용자의 질문이 아닙니다.

### 구조화 출력 실패 (P1, TC-03)
- **Given** LLM이 스키마를 무시하고 평문을 반환
- **Then** 크래시 없이 safe fallback으로 우회한다 (NFR-04).

### 모델이 약 이름을 지어냄 (P1, **신규**)
- **Given** LLM이 스키마는 지키지만 우리가 조회하지 않은 제품명을 반환
- **When** 서버가 불변조건 6번을 검증
- **Then** 응답을 거부하고 `AI_SCHEMA_INVALID` → safe fallback. **그 제품명은 화면에 절대 도달하지 않는다.**

### 공공 API 장애 (P1, **신규**)
- **Given** `data_mode=hybrid`, 약국 API가 타임아웃
- **Then** fixture로 fallback하고, 화면에 `data_mode=fixture`가 표시된다. 앱이 먹통이 되지 않는다.

---

## 9. Success criteria

- **SC-001**: `openai` JS SDK가 `baseURL` 교체만으로 붙고, 커스텀 파싱 없이 렌더링된다 (= TC-01)
- **SC-002**: `type=pharmacy&open_now=true&radius_m=500`에 대해 지금 영업 중인 500m 이내 약국만 노출된다 (= TC-02)
- **SC-003**: LLM이 평문을 반환해도 클라이언트가 크래시하지 않는다 (= TC-03)
- **SC-004**: 개발자 도구 네트워크 탭에 LLM API 키가 노출되지 않는다 (= NFR-03)
- **SC-005**: `MAIN_INGR_ENG` 기반 알레르기 필터가 4-state로 판정한다 (= FR-04)
- **SC-006**: ERD와 테이블 명세서를 JPA 엔티티에서 도출하여 제출한다 (= 산출물 6·7번)
- **SC-007**: 공공 API 캐시가 동작하여 반복 조회가 일일 한도를 소모하지 않는다
- **SC-008**: **모델이 지어낸 제품명이 후처리 불변조건에서 거부된다** (= §2-15)
- **SC-009**: **`urgency=emergency`인데 `SHOW_EMERGENCY_CALL`이 없는 응답은 반려된다**
- **SC-010**: `data_mode=fixture`로 네트워크 없이 전체 시연이 재현된다

---

## 10. Sol Pro 문서 병합 결정

| Sol Pro 문서 | 판정 | 이유 |
|---|---|---|
| `00_SOURCE_NORMALIZATION` | MERGE | NORM 표를 §2에 병합. NORM-004/005는 채택, "서버 DB 없음"만 §2-4로 교정 |
| `01_PRODUCT_SPEC` | TAKE WITH EDITS | P0/P1/P2 분류·KPI 채택. 저장 항목만 교정 |
| `02_ARCHITECTURE` | TAKE WITH EDITS | 어댑터 경계·2-패스·data mode 채택. 스택을 Spring/React로 확정 |
| `03_API_CONTRACT` | **TAKE AS-IS** | `MermAidAnswerV1`·에러 코드·후처리 불변조건 그대로. 프로필 CRUD 엔드포인트만 추가 |
| `04_DATA_MODEL` | TAKE WITH EDITS | 정규화 타입·성분/운영시간 처리·버전 마이그레이션 채택. 서버 DB 3층으로 대체 |
| `05_TEAM_PLAN` | **TAKE AS-IS** | → `docs/team-plan.md` |
| `06_BACKLOG` | TAKE WITH EDITS | → `docs/specs/001-foundation/tasks.md`. 서버 DB·DUR 이슈 추가, P0 범위 현실화 |
| `07_TEST_PLAN` | **TAKE AS-IS** | → `docs/test-plan.md`. 채점 산출물 9번 직결 |
| `08_OPEN_QUESTIONS` | MERGE | 우리가 이미 답한 OQ는 Decision으로 닫음 |
| `09_DEMO_PLAN` | **TAKE AS-IS** | → `docs/demo-plan.md`. 우리에게 없던 영역 |

**우리가 Sol Pro보다 앞선 지점:** e약은요의 성분 부재(§2-8), 공공 API의 radius 부재(§2-9), 네이버맵 키 파라미터, Vercel AI SDK의 실제 동작(§2-3). Sol Pro는 이들을 정직하게 UNKNOWN으로 두었고, 우리가 조사로 닫았습니다.

---

## 11. v1 코드에서 고쳐야 할 것

이 스펙 v2와 어긋나는 구현이 이미 있습니다.

| 파일 | 문제 | 조치 |
|---|---|---|
| `facility/domain/Facility.java` | `openNow`가 `boolean` | `Boolean` + `status` + `statusConfidence` + `verifiedAt` |
| `chat/dto/MedicalResponse.java` | 단일 `map` 필드 | `ui_actions[]` 배열로 교체, `MermAidAnswerV1`에 맞춤 |
| `chat/dto/MedicalResponse.java` | `allergyWarning: String \| null` | `AllergyCheck` 4-state |
| `frontend/src/lib/deviceId.ts` | 채팅을 `localStorage`에 | `sessionStorage`로 |
| (없음) | 후처리 불변조건 | `AnswerValidator` 신설 |
| (없음) | DUR 클라이언트 | `DurApiClient` 신설 |
| (없음) | `data_mode` | `DataMode` + fixture 어댑터 |

## Future expansion

- `docs/specs/001-foundation/tasks.md` — 백로그 + 팀원 분배 (WBS 산출물)
- `docs/team-plan.md`, `docs/test-plan.md`, `docs/demo-plan.md` — Sol Pro에서 이관
