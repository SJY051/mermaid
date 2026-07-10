---
title: mermAid 기반 스펙 (Foundation)
status: draft
created: 2026-07-10
owner: ASQi
tags: [foundation, architecture, public-api, llm, spring-boot, react]
---

# mermAid 기반 스펙 (Foundation)

> 이 문서는 팀이 작성한 **요구사항 명세서 v0.1**과 **프로젝트 수행계획서**를 대체하지 않습니다.
> 두 문서의 의도를 유지하되, **실제로 구현 가능한 형태로 교정**한 것입니다.
> 원본과 달라진 부분은 §2에 근거와 함께 모두 명시했습니다. 원본 FR 번호는 대조를 위해 그대로 씁니다.

## Context & problem

한국은 공공 의료 인프라가 촘촘하지만, **사전 정보가 없으면 접근 자체가 어렵습니다.** 언어 장벽이 있는 외국인, 의료 시스템에 익숙하지 않은 사람, 야간·휴일에 갑자기 아픈 사람이 그렇습니다.

mermAid는 **로그인 없이** 영어로 증상을 말하면 (1) 공공 데이터로 검증된 한국 의약품 정보를 구조화된 형태로 보여주고, (2) 지금 영업 중인 근처 약국·병원을 지도에 띄워주는 서비스입니다.

대상 사용자는 **한국에 체류·여행 중인 영어 사용자**와 **의료 정보 접근이 어려운 내국인**입니다.

## Goals / non-goals

**Goals**
- 로그인 없이 즉시 대화 시작 → 첫 화면에서 증상 입력까지 마찰 0
- LLM 응답을 **JSON Schema로 강제**하여 프론트 컴포넌트에 그대로 바인딩
- 의약품 정보의 **출처를 공공 API로 고정**하여 환각을 구조적으로 차단
- 반경·기관 종류·**현재 영업 여부**로 필터링한 의료기관을 지도에 표시
- 알레르기·기피 성분이 포함된 의약품을 **차단하거나 경고**
- 채점 산출물(ERD, 테이블 명세서, API 명세서, 테스트 케이스)을 코드에서 자연스럽게 도출

**Non-goals**
- 영어 외 다국어 UI (원본 계획서의 제외 범위 유지)
- 진단. 우리는 **정보를 제공**하고 전문가 상담을 권할 뿐입니다 (§7 참조)
- 시니어 데일리 케어 + 보호자 FCM 알림 (원본 FR-06) → **Could로 강등, MVP 제외**
- 서버에 의료 상담 대화 내용을 영속 저장하는 것

---

## 1. 아키텍처

```
┌─────────────────────────────┐
│ React 19 + Vite + TS        │
│  - openai JS SDK            │   baseURL → 같은 오리진 (Vite proxy)
│  - naver.maps (raw API)     │   dummy apiKey (진짜 키는 서버에만)
│  - LocalStorage: 대화 기록   │
└──────────────┬──────────────┘
               │ POST /api/v1/chat/completions  (OpenAI 호환 SSE)
               │ GET  /api/v1/facilities?...
               ▼
┌─────────────────────────────┐
│ Spring Boot 3.5 / Java 21   │
│  ChatProxyController        │  ← 시스템 프롬프트 주입, API 키 은닉
│  FacilityService            │  ← 반경/영업중 계산 (공공 API엔 없음)
│  DrugService                │  ← e약은요 + 허가정보 병합
│  ProfileService             │  ← CRUD 대상 (JPA)
└──┬──────────────────────┬───┘
   │                      │
   ▼                      ▼
LLM Provider        공공데이터포털 4종
(OpenAI/Gemini)     (CORS 없음 → 서버에서만 호출)
```

**왜 Spring Boot인가:** 팀원 4명이 수업에서 배운 스택입니다. 채점 항목에 "커밋/이슈/PR 기록"이 있어 팀원이 기여할 수 있는지가 스택 선택의 1순위 기준이었습니다. 부수적으로, 공공 API가 CORS 헤더를 보내지 않아 서버 프록시가 **어차피 필수**입니다.

**왜 Spring AI를 안 쓰는가:** 우리 백엔드는 OpenAI 형식 요청을 받아 상류로 릴레이합니다. Spring AI를 끼우면 `OpenAI DTO → Spring AI 타입 → 다시 OpenAI DTO`로 두 번 변환됩니다. `WebClient` 직접 릴레이가 더 단순하고 팀원에게 익숙합니다. (또한 Spring AI 2.0은 Spring Boot 4를 요구합니다.)

---

## 2. 원본 문서에서 달라진 점 (근거 포함)

각 항목은 조사로 확인된 사실에 근거합니다. 팀 문서를 고칠 때 이 표를 그대로 쓰면 됩니다.

### 2-1. 툴 콜링으로 지도를 띄우는 설계 → **응답 스키마의 `map` 필드로 대체**

**원본:** "AI가 툴 콜링(Tool Calling)을 통해 지도 연동이 필요하다고 판단하면…" (§2, UI-02)

**사실:** 모델이 툴을 호출하기로 하면 그 어시스턴트 메시지에는 `tool_calls`만 담기고 `content`는 비어 있습니다. JSON Schema는 최종 content 메시지에만 적용됩니다. **툴 콜과 구조화 출력은 같은 메시지에 공존할 수 없습니다.** 원본이 그린 "지도를 띄우면서 동시에 구조화된 약 정보 반환"은 한 번의 응답으로 불가능하고, 툴 결과를 다시 넣어 한 번 더 왕복해야 합니다.

**교정:** 툴 콜링을 쓰지 않습니다. 지도 표시 여부와 필터 조건을 **응답 JSON 스키마의 `map` 필드**로 만듭니다. AI가 지도가 필요하다고 판단하면 그 필드를 채우고, 프론트는 필드의 존재로 지도를 렌더링합니다. 왕복 1회로 끝나며 UI-02의 의도("대화창과 유기적으로 스위칭")는 그대로 달성됩니다.

### 2-2. "네이티브 서치 그라운딩 강제 활성화" → **공공 API 기반 검색 증강(RAG)**

**원본:** "할루시네이션 방지를 위해 네이티브 서치 그라운딩을 강제 활성화" (§7)

**사실 두 가지.**
1. **웹 검색 그라운딩은 강제할 수 없습니다.** 모델이 검색할지 스스로 정합니다. "매 요청 반드시 검색" 모드는 어느 프로바이더에도 없습니다. (구조화 출력은 constrained decoding으로 진짜 강제됩니다 — 둘은 성질이 다릅니다.)
2. Gemini 2.5 이하는 **그라운딩과 `responseSchema`를 같은 요청에 쓰면 `400 INVALID_ARGUMENT`로 거부**합니다. Gemini 3 계열에서 풀렸으나 Preview 상태입니다.

**교정:** 웹 그라운딩을 쓰지 않습니다. 대신 **2-패스**로 갑니다.

1. **Pass 1 (검색):** 사용자 발화에서 증상·약 키워드를 추출 → 식약처 API 조회
2. **Pass 2 (구조화):** 조회 결과를 컨텍스트로 주입 → JSON Schema를 강제하여 최종 응답 생성

출처가 정부 API이므로 검증 가능하고, 원본의 목적(환각 방지)을 웹 검색보다 확실히 달성합니다. 명세서의 "강제 그라운딩"은 **"공공 API 기반 검색 증강"**으로 고쳐 씁니다.

### 2-3. "Vercel AI SDK를 그대로 재사용" → **`openai` 공식 JS SDK + `baseURL`**

**원본:** NFR-01 "프론트엔드 기존 라이브러리(Vercel AI SDK 등)를 그대로 재사용할 수 있도록 백엔드 챗 엔드포인트는 업계 표준 스펙을 완전히 준수해야 한다."

**사실:** Vercel AI SDK의 `useChat`은 **OpenAI SSE를 읽지 못합니다.** 기본 `DefaultChatTransport`가 각 SSE 줄을 Vercel 자체 `UIMessageChunk` 스키마로 검증하기 때문입니다. 백엔드를 OpenAI 표준에 맞출수록 오히려 `useChat`과 멀어집니다. (대안 `streamProtocol: 'text'`는 JSON을 날것 텍스트로 렌더링합니다.)

**교정:** 프론트는 **`openai` 공식 JS SDK**를 씁니다. `baseURL`을 우리 서버로, `dangerouslyAllowBrowser: true`, `apiKey`는 더미 문자열(`'not-needed'` — 빈 문자열은 생성자가 거부). 진짜 키는 Spring에만 있으므로 NFR-03을 지키고, TC-01의 "커스텀 파싱 레이어 없이"를 문자 그대로 만족합니다.

> **함정:** 이 SDK는 `Authorization`과 `x-stainless-*` 헤더를 붙이며, 전부 non-simple 헤더라 브라우저가 **CORS preflight**를 던집니다. Spring CORS에 헤더를 일일이 허용하는 대신 **Vite dev proxy로 same-origin**을 만들어 preflight 자체를 없앱니다. (`defaultHeaders`로 `x-stainless-*`를 제거하려는 시도는 동작하지 않습니다.)

NFR-01은 **"백엔드는 OpenAI Chat Completions 형식을 준수하고, 프론트는 `openai` SDK의 `baseURL`만 교체해 붙는다"**로 고쳐 씁니다.

### 2-4. CRUD·ERD ↔ "DB 없음" 충돌 → **하이브리드 저장**

**원본 충돌:** §7은 "영속성 백엔드 DB 구성을 지양, LocalStorage에 보관"이라 하는데, 수행계획서 §5 산출물에는 **ERD(6번)와 테이블 명세서(7번)가 필수**입니다. LocalStorage만으로는 제출할 내용이 없습니다.

**교정:** §4의 데이터 모델을 참조. **민감한 의료 상담 대화는 LocalStorage에만** 두어 원본의 보안 논리를 지키고, **즐겨찾기·알레르기 프로필·공공 API 캐시**만 서버 DB에 둡니다.

### 2-5. "로그인 없음" ↔ "사용자 프로필" 충돌 → **익명 디바이스 ID**

**원본 충돌:** FR-01은 로그인 없이 시작, FR-04는 "사용자 프로필에 등록된 기피 성분"을 읽습니다.

**교정:** 브라우저가 최초 진입 시 UUID(`deviceId`)를 생성해 LocalStorage에 보관하고, 프로필 API 호출 시 헤더로 보냅니다. **계정 없이 서버 프로필이 성립**합니다. 개인 식별 정보는 저장하지 않습니다.

### 2-6. FR-06(시니어 케어 + 보호자 FCM) → **Could로 강등, MVP 제외**

계정 시스템 + 서버 스케줄러 + 푸시 인프라가 필요하며, 체감 난이도가 MVP 전체를 합친 것보다 큽니다. 또한 "보호자 계정"은 FR-01의 로그인 없음과 정면으로 충돌합니다. (원본 FR 표에서 이 행은 **ID 칸이 비어 있습니다** — FR-06을 부여합니다.)

### 2-7. `DELETE /api/v1/drugs/{id}` → **삭제**

응답이 "단건 상세 스펙 JSON"으로 적혀 있어 GET을 복사한 오기입니다. 원본 §6 CRUD 표에서도 의약품은 **Read 전용**입니다. CRUD의 Delete는 **즐겨찾기·알레르기 성분**에서 시연합니다 (§5).

### 2-8. FR-04는 **API를 하나 더 요구**합니다

**사실:** e약은요(`DrbEasyDrugInfoService`)의 응답은 전부 `*Qesitm` 서술형 안내문이며 **성분 필드가 없습니다.** 성분 기반 필터링이 불가능합니다.

**교정:** **식약처 의약품 제품 허가정보**(`DrugPrdtPrmsnInfoService07`)를 추가합니다. 요청 파라미터 `item_ingr_name`(성분명)으로 조회하고, 응답의 `MAIN_ITEM_INGR`(주성분, `|` 구분), **`MAIN_INGR_ENG`(영문 성분명)**을 씁니다. 영문 성분명이 있어 "I'm allergic to ibuprofen"을 직접 매칭할 수 있습니다.

역할 분담: **e약은요 = 사람이 읽을 안내문**, **허가정보 = 기계가 거를 성분**.

### 2-9. FR-02의 `radius` / `open_now`는 **우리가 계산**해야 합니다

**사실:**
- 약국 좌표 조회 `getParmacyLcinfoInqire`의 파라미터는 `serviceKey, WGS84_LON, WGS84_LAT, pageNo, numOfRows`뿐. **radius 파라미터가 없습니다.** 거리순 정렬만 해줍니다.
- **어떤 공공 API에도 "현재 영업 중" 필터가 없습니다.**
- 다행히 약국 좌표 조회는 한 번에 `dutyTime1s`~`dutyTime8c`(1=월 … 7=일, 8=공휴일, HHMM 문자열)를 함께 반환합니다. 추가 호출 없이 영업 여부를 계산할 수 있습니다.
- 병원은 **두 서비스를 엮어야** 합니다: `hospInfoServicev2/getHospBasisList`(여기엔 `xPos`/`yPos`/`radius`(m)가 있음) → `ykiho` 획득 → `MadmDtlInfoService2.8/getDtlInfo`(병원별 1회, `trmtMonStart`~`trmtSunEnd`, `lunchWeek`, `noTrmtSun`, `emyNgtYn` 등). **N+1이므로 캐싱 필수.**

**교정:** `radius`와 `open_now`는 **백엔드가 Haversine 거리 계산 + KST 시각/요일/공휴일 비교로 구현**합니다. FR-02와 TC-02는 그대로 유효하되, "공공 API가 필터링해준다"는 전제만 버립니다.

---

## 3. 검증된 외부 제약 (구현 전 반드시 읽을 것)

| 항목 | 사실 | 대응 |
|---|---|---|
| 약국 API 일일 한도 | 개발계정 **1,000회/일** | 캐시 계층 + mock 프로파일 필수. 팀원 5명이 개발하면 하루도 못 버팀 |
| 기타 공공 API 한도 | 심평원 10,000 / e약은요 10,000 / 허가정보 100,000 | |
| serviceKey 인코딩 | Encoding 키를 `UriComponentsBuilder`에 넣으면 `%`→`%25` 이중 인코딩 → `SERVICE_KEY_IS_NOT_REGISTERED_ERROR` | Decoding 키 사용 또는 `.build(true).toUri()` |
| JSON 파라미터명 | 약국·심평원은 `_type=json`, 식약처는 **`type=json`** | API별로 다름. 언더스코어 주의 |
| CORS | 공공 API는 CORS 헤더 없음 | 서버에서만 호출 (설계상 이미 그러함) |
| 네이버맵 키 | 파라미터가 `ncpClientId` → **`ncpKeyId`**로 변경됨. 호스트는 `oapi.map.naver.com` | 인터넷 예제 대부분이 구버전. 그대로 쓰면 인증 실패 |
| 네이버맵 영어 | 스크립트 URL에 `&language=en` → 지도 라벨 전체 영어화 | 외국인 대상 서비스에 결정적 |
| 네이버맵 무료량 | "대표 계정" **1개**에만 부여 (개인은 전화번호당 1계정) | 기존 NCP 계정 이력이 있으면 신규 계정은 첫 호출부터 과금될 수 있음 |
| 상류 SSE 종료 | OpenAI/Gemini 스트림은 `data: [DONE]`으로 끝남 — **JSON이 아님** | 파싱 전에 필터링 |

**[NEEDS CLARIFICATION]** 아래 세 가지는 확정하지 못했습니다. 개발 시작 시 실제 호출로 확인해야 합니다.
- 네이버맵 무료 이용량의 정확한 수치(월 1천만 로딩 추정)와 결제수단 등록 강제 여부 → NCP 콘솔에서 직접 확인
- 심평원 상세정보 서비스의 현재 버전 접미사 (`2.7` vs `2.8`) → data.go.kr Swagger 확인 후 DTO 생성
- OpenAI Responses API에서 `web_search` + strict `json_schema`가 공식 보장되는지 (우리는 2-패스로 우회하므로 **차단 요인 아님**)

---

## 4. 데이터 모델 (하이브리드)

### 4-1. LocalStorage (브라우저에만 존재)
- `deviceId` — UUID, 최초 진입 시 생성
- `chatHistory` — 의료 상담 대화 스냅샷. **서버로 가지 않습니다.**

### 4-2. 서버 DB (JPA 엔티티 → ERD·테이블 명세서의 원본)

```
user_profile (1) ──< (N) allergy_ingredient
      │
      └──< (N) favorite_facility

facility_cache   (독립 테이블, 공공 API 호출 한도 대응)
```

| 테이블 | 주요 컬럼 | 비고 |
|---|---|---|
| `user_profile` | `id` PK, `device_id` UK, `country_code`, `created_at` | 계정 아님. 익명 UUID |
| `allergy_ingredient` | `id` PK, `profile_id` FK, `ingredient_name_en`, `ingredient_name_ko` | `MAIN_INGR_ENG`와 매칭 |
| `favorite_facility` | `id` PK, `profile_id` FK, `facility_id`, `facility_type`, `memo` | `facility_id`는 `hpid`(약국) 또는 `ykiho`(병원) |
| `facility_cache` | `id` PK, `cache_key` UK, `payload` (JSON), `expires_at` | 일일 1,000회 한도 대응 |

### 4-3. CRUD 시연 매핑 (채점 필수 요소)

| | 대상 | 엔드포인트 |
|---|---|---|
| **C** | 알레르기 성분 추가, 즐겨찾기 추가 | `POST /api/v1/profiles/{deviceId}/allergies` |
| **R** | 프로필·즐겨찾기 조회, 의료기관·의약품 조회 | `GET /api/v1/profiles/{deviceId}` |
| **U** | 즐겨찾기 메모 수정, 프로필 국가 변경 | `PATCH /api/v1/profiles/{deviceId}/favorites/{id}` |
| **D** | 알레르기 성분 삭제, 즐겨찾기 해제 | `DELETE /api/v1/profiles/{deviceId}/allergies/{id}` |

---

## 5. API 명세 (원본 §9 교정판)

| Method | URL | 요청 | 응답 |
|---|---|---|---|
| POST | `/api/v1/chat/completions` | OpenAI Chat Completions 규격 | SSE 스트림 또는 JSON (스키마 보장) |
| GET | `/api/v1/facilities` | `?lat=&lng=&radius=&open_now=&type=` | 필터링된 의료기관 배열 |
| GET | `/api/v1/facilities/{id}` | `hpid` 또는 `ykiho` | 단건 상세 |
| GET | `/api/v1/drugs` | `?symptom=&exclude_ingredients=` | 성분 필터링된 의약품 배열 |
| GET | `/api/v1/drugs/{itemSeq}` | 경로 변수 | 단건 상세 (e약은요 + 허가정보 병합) |
| GET/POST/PATCH/DELETE | `/api/v1/profiles/{deviceId}/**` | §4-3 참조 | CRUD |

> `DELETE /api/v1/drugs/{id}`는 **제거되었습니다** (§2-7).

### 5-1. 챗 응답 JSON Schema (FR-03)

```jsonc
{
  "reply": "string",                    // 사용자에게 보여줄 영어 답변
  "urgency": "self_care | see_pharmacist | see_doctor | emergency",
  "medications": [{
    "koreanName": "string",             // 약국 매대에서 보여줄 한국어 제품명
    "englishIngredient": "string",      // MAIN_INGR_ENG
    "purpose": "string",
    "dosage": "string",
    "cautions": ["string"],
    "prescriptionRequired": true,
    "allergyWarning": "string | null"   // FR-04: 기피 성분 매칭 시 채움
  }],
  "map": {                              // null이면 지도를 띄우지 않음 (§2-1)
    "type": "pharmacy | hospital",
    "radiusMeters": 500,
    "openNow": true,
    "reason": "string"
  },
  "disclaimer": "string"                // 항상 존재 (§7)
}
```

---

## 6. 요구사항 (원본 FR 번호 유지)

- **FR-01** (Must): 로그인 없이 챗 개시. `openai` SDK가 `baseURL` 교체만으로 붙는다.
- **FR-02** (Must): 좌표·반경·기관 종류·현재 영업 여부로 필터링된 의료기관 목록 반환. **반경과 영업 여부는 백엔드가 계산한다** (§2-9).
- **FR-03** (Must): 응답을 JSON Schema로 강제. 지도 표시는 `map` 필드로 전달한다 (§2-1).
- **FR-04** (Must): 프로필의 기피 성분과 `MAIN_INGR_ENG`를 매칭해 차단하거나 `allergyWarning`을 채운다. **허가정보 API 필요** (§2-8).
- **FR-05** (Should): 국가별 의료 시스템 차이 안내(처방 권한, 사후피임약 절차 등)와 반입 금지 성분의 국내 대체 의약품 매핑. **UI는 영어 단일**이며, 다국어 UI는 계획서의 제외 범위다.
- **FR-06** (Could, **MVP 제외**): 시니어 데일리 케어 + 보호자 FCM 알림 (§2-6).

**비기능:** 원본 NFR-01~05 유지. 단 NFR-01은 §2-3대로 문구 교정.

---

## 7. 안전 및 정책 제약 (신규)

원본 문서에 없지만 **반드시 넣어야 합니다.**

OpenAI 이용정책은 *"면허가 필요한 맞춤형 조언(의료 조언 등)을 유자격 전문가의 적절한 관여 없이 제공하는 것"*을 금지합니다. Google의 생성형 AI 금지사용 정책도 자격 있는 인간의 감독 없는 의료 진단·조언을 제한합니다.

우리 서비스는 증상을 듣고 특정 의약품을 추천하며, 페르소나 시나리오에는 사후피임약과 당뇨 합병증 의심 증상이 포함됩니다. **"정보 제공"과 "의료 조언"의 경계선 위에 있습니다.**

**대응 (구현 필수):**
- **SA-01**: 시스템 프롬프트에 "진단하지 않는다. 정보를 제공하고 전문가 상담을 권한다"를 명시하고, 프롬프트 인젝션으로 이 제약이 무력화되지 않도록 방어한다 (NFR-03).
- **SA-02**: 응답 스키마의 `disclaimer` 필드는 **항상 채워지며**, UI에 상시 표시된다.
- **SA-03**: `urgency`가 `emergency`이면 의약품 추천 대신 119/응급실 안내를 우선한다.
- **SA-04**: 채팅 입력창에 여권번호·생년월일 등 개인정보를 넣지 않도록 UX 가이드문을 노출한다 (원본 §7 유지).

---

## 8. User scenarios

### 야간에 아픈 외국인 여행자 (P1)
- **Given** 사용자가 로그인 없이 앱에 접속했고, 프로필에 등록한 기피 성분이 없다
- **When** 영어로 "I have a sore throat and fever, it's 11pm"이라고 입력한다
- **Then** 백엔드는 식약처 API로 관련 의약품을 조회한 뒤 스키마가 강제된 JSON을 반환하고, `map` 필드가 `{type: "pharmacy", openNow: true}`로 채워져 프론트가 지도를 띄운다. `disclaimer`가 화면에 표시된다.

### 알레르기가 있는 보호자 (P1)
- **Given** 사용자가 프로필에 `ibuprofen`을 기피 성분으로 등록했다
- **When** "My child has a fever, what can I buy?"라고 입력한다
- **Then** 이부프로펜 계열 의약품은 목록에서 제외되거나 `allergyWarning`이 채워지고, 아세트아미노펜 계열 대체 약이 제시된다

### 구조화 출력 실패 (P1, TC-03)
- **Given** LLM이 일시적으로 스키마를 무시하고 평문을 반환한다
- **When** 백엔드가 응답을 검증한다
- **Then** 크래시 없이 기본 안전 포맷(`reply`만 채워지고 `medications: []`, `map: null`)으로 우회 출력된다 (NFR-04)

---

## 9. Success criteria

- **SC-001**: `openai` JS SDK가 `baseURL` 교체만으로 `/api/v1/chat/completions`에 붙고, 커스텀 파싱 없이 스트림이 화면에 렌더링된다 (= TC-01)
- **SC-002**: `type=pharmacy&open_now=true&radius=500` 조합에 대해, 지도에 실제로 지금 영업 중인 500m 이내 약국 마커만 노출된다 (= TC-02)
- **SC-003**: LLM이 평문을 반환해도 클라이언트가 크래시하지 않는다 (= TC-03)
- **SC-004**: 프론트엔드 개발자 도구 네트워크 탭에 LLM API 키가 노출되지 않는다 (= NFR-03)
- **SC-005**: `MAIN_INGR_ENG` 기반 알레르기 필터가 등록된 성분을 포함한 의약품을 차단한다 (= FR-04)
- **SC-006**: ERD와 테이블 명세서를 JPA 엔티티에서 도출하여 제출한다 (= 산출물 6·7번)
- **SC-007**: 공공 API 캐시가 동작하여, 동일 좌표 반복 조회가 일일 한도를 소모하지 않는다

---

## 10. Open questions

- [NEEDS CLARIFICATION: LLM 프로바이더를 OpenAI로 갈지 Gemini로 갈지 — 비용과 팀 계정 사정. Gemini 2.5 이하면 §2-2의 2-패스가 **필수**이고, OpenAI면 선택이다. 우리는 어차피 2-패스로 설계했으므로 어느 쪽도 가능]
- [NEEDS CLARIFICATION: DB를 무엇으로 할지 — 개발 편의는 H2(인메모리), 시연 안정성은 PostgreSQL. 팀원 로컬 환경 통일 난이도를 보고 결정]
- [NEEDS CLARIFICATION: 배포를 할 것인지, 로컬 시연으로 끝낼 것인지. 배포한다면 네이버맵 도메인 allowlist에 배포 도메인 등록 필요]
- [NEEDS CLARIFICATION: 팀원 5명의 역할 분담 — 원본 계획서 §3이 템플릿 그대로 비어 있음. 공공 API 3종을 한 명씩 맡는 구조를 제안]
- [NEEDS CLARIFICATION: 심평원 상세정보 서비스 버전 접미사 (`2.7`/`2.8`) — DTO 생성 전 확인]

## Future expansion

이 스펙이 커지면 `plan.md`(HOW: 레이어별 구현 순서, 캐시 전략, 2-패스 프롬프트 설계)와 `tasks.md`(DO: GitHub Issue로 쪼갠 작업 목록)를 이 파일 옆에 만듭니다. 지금은 만들지 않습니다.

WBS(산출물 3번)는 `tasks.md`에서 도출하는 것이 가장 자연스럽습니다.
