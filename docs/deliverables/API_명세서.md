---
title: mermAid API 명세서
status: verified
created: 2026-07-10
owner: 윤서진 (BE-1)
source: 실행 중인 서버에 직접 호출해 확인 (DATA_MODE=fixture)
---

# API 명세서

> **이 문서는 코드 주석이 아니라 실제 응답에서 만들었습니다.**
> 스펙 문서(`docs/specs/001-foundation/spec.md` §5-3)와 어긋나는 부분은 **이 문서가 맞습니다.**
>
> ```bash
> docker compose up -d
> cd backend && DATA_MODE=fixture ./gradlew bootRun    # 다른 셸에서
> ./bin/verify-api-doc.sh                              # 43개 검사, 전부 통과해야 합니다
> ```
>
> 검사가 깨지면 **서버가 바뀌었거나 이 문서가 거짓말을 하는 것**입니다. 둘 중 틀린 쪽을 고치세요.
> 검사 자체를 고치지 마세요. 마지막 두 검사는 백엔드 enum · 프론트 union · 아래 §5 표의 에러 코드
> 개수가 일치하는지 봅니다 — 셋 중 하나만 고치면 API 계약이 자기 자신과 어긋납니다.

- **Base URL**: `http://localhost:8080/api/v1`
- **인증 없음.** 로그인이 없고, `deviceId`는 브라우저가 만든 UUID일 뿐 계정이 아닙니다.
- **모든 응답에 `X-Request-Id` 헤더**가 붙고, 에러 본문에도 같은 값이 들어갑니다.
- 날짜·시각은 전부 UTC ISO-8601 (`2026-07-10T05:00:00Z`).
- JSON 필드명은 **camelCase**입니다. Jackson 기본 설정이며, 프론트 타입(`lib/types.ts`)도 그렇습니다.

---

## 0. 한눈에 보기

| Method | 경로 | 상태 |
|---|---|---|
| POST | `/chat/completions` | ✅ 동작 |
| GET | `/facilities` | ✅ 약국·병원 검색 |
| GET | `/facilities/{id}` | 🚧 **501 미구현** |
| GET | `/drugs` | ✅ 동작 |
| GET | `/drugs/{itemSeq}` | ✅ 동작 |
| GET | `/profiles/{deviceId}` | ✅ 동작 |
| PATCH | `/profiles/{deviceId}` | ✅ 동작 |
| PATCH | `/profiles/{deviceId}/consent` | ✅ 동작 |
| POST | `/profiles/{deviceId}/allergies` | ✅ 동작 |
| DELETE | `/profiles/{deviceId}/allergies/{allergyId}` | ✅ 동작 |
| POST | `/profiles/{deviceId}/favorites` | ✅ 동작 |
| PATCH | `/profiles/{deviceId}/favorites/{favoriteId}` | ✅ 동작 |
| DELETE | `/profiles/{deviceId}/favorites/{favoriteId}` | ✅ 동작 |
| GET | `/actuator/health` | ✅ 동작 (`/api/v1` 아래가 **아님**) |

### 스펙 문서와 달랐던 것

| 스펙 §5-3이 적은 것 | 실제 |
|---|---|
| `GET /api/v1/health` | **없습니다.** Actuator의 `GET /actuator/health`뿐 (`/api/v1/health` → 404) |
| `GET /drugs?…&prescription_status=&limit=` | 그런 파라미터 **없습니다.** 실제는 `query`, `ingredient`, `exclude_ingredients` |
| `GET /drugs/{id}` (`drug:mfds:…`) | 경로 변수는 **`itemSeq`** (`202005623`). `drug:mfds:` 접두사는 응답 `id` 필드에만 |
| `GET /facilities?…&sort=` | 그런 파라미터 **없습니다.** 항상 거리순 정렬 |
| `DELETE /api/v1/drugs/{id}` 없음 | 맞습니다 (§2-7) |
| 에러 코드 13개 | **15개**입니다 (§5 참조) |

---

## 1. 챗 (FR-01)

### `POST /chat/completions`

OpenAI 호환입니다. 프론트는 공식 `openai` JS SDK의 `baseURL`만 바꿔서 씁니다.

**요청**

```jsonc
{
  "messages": [{ "role": "user", "content": "I have a headache." }],
  "stream": false,                       // 기본값. true여도 검증된 답변 1청크로 옵니다
  "mermaid": {                           // 비표준 확장. 상류로 넘기기 전에 제거됩니다
    "exclude_ingredients": ["Ibuprofen"] // 사용자가 피해야 할 성분 (최대 10개, 각 100자)
  }
}
```

- 클라이언트가 보낸 `system` 메시지는 **버려집니다.** 프롬프트 인젝션 방어입니다 (NFR-03).
- 클라이언트가 보낸 `model`, `response_format`도 **무시하고 서버 값으로 덮어씁니다.**
- **알레르기는 서버에 저장하지 않습니다.** 매 요청 확장 필드로 받습니다 (spec §2-5).

**응답** — `chat.completion` 봉투. `choices[0].message.content`가 `MermAidAnswer` **JSON 문자열**입니다.

```jsonc
{
  "schemaVersion": "1.0",
  "answerId": "…",
  "language": "en",
  "dataStatus": "live" | "fixture" | "mixed" | "unavailable",  // 서버가 씁니다
  "urgency": { "level": "emergency"|"urgent"|"routine"|"unknown", "title": "…", "message": "…",
               "reasonCodes": [], "actions": [] },
  "summary": "사용자가 읽는 문장. 절대 비지 않습니다",
  "clarifyingQuestions": ["…"],
  "guidance": [{ "id": "…", "title": "…", "body": "…",
                 "evidence": "official_data"|"general_safety"|"model_summary", "sourceRefIds": ["…"] }],
  "drugs": [{ "productNameKo": "삼남아세트아미노펜정", "productNameEn": null,
              "ingredients": [{ "nameEn": "Acetaminophen", "nameKo": null, "amount": null, "unit": null }],
              "indicationSummary": "…", "directionsSummary": "…", "warnings": ["…"],
              "prescriptionStatus": "otc"|"prescription"|"unknown",
              "allergyCheck": { "status": "blocked"|"warning"|"no_match_found"|"unknown",
                                "matchedIngredients": [], "message": "…" },
              "sourceRefId": "src:mfds:199401030" }],
  "uiActions": [{ "type": "OPEN_FACILITY_MAP",
                  "payload": { "types": ["pharmacy"], "openNow": true, "radiusM": 1000 } }],
  "sourceRefs": [{ "id": "src:mfds:199401030", "provider": "…", "recordId": "…",
                   "retrievedAt": "2026-07-10T05:41:50Z", "dataMode": "live", "title": "…" }],
  "warnings": ["…"],
  "disclaimer": "항상 채워집니다"
}
```

**`uiActions[].type` 허용 목록** (닫힌 집합. 그 밖은 거부됩니다)

`OPEN_FACILITY_MAP` · `APPLY_FACILITY_FILTERS` · `OPEN_DRUG_DETAIL` · `SHOW_EMERGENCY_CALL` · `ASK_CLARIFYING_QUESTION`

**서버가 지키는 것**

1. **응급 선별이 모델보다 먼저 돕니다.** 흉통·호흡곤란·뇌졸중·대량출혈·의식소실·자해 문구가 걸리면 **모델을 부르지 않고** 코드가 답합니다(약 30ms). 실제 모델이 "crushing chest pain and I cannot breathe"에 `urgency: unknown`을 반환한 적이 있습니다.
2. **`drugs[].productNameKo`는 그 턴에 실제로 조회한 제품만 허용됩니다.** 모델이 지어낸 이름은 답변 전체가 거부되고 안전 문구로 대체됩니다 (후처리 불변조건 6).
3. **`sourceRefs`와 `dataStatus`는 서버가 씁니다.** 모델 출력은 버립니다.
4. `stream=true`도 **검증을 마친 뒤** SSE 청크 하나로 나갑니다. 토큰 단위 중계는 하지 않습니다 — 검증되지 않은 의약품 추천이 브라우저에 닿기 때문입니다.

**응답 시간**: 콜드 100초 이상, 웜 50~90초. LLM 왕복 2회(성분 추출 + 요약)가 대부분입니다. **FE는 로딩 상태를 반드시 그려야 합니다.**

> 상류가 느리거나 죽으면 **500이 아니라** `dataStatus: "unavailable"`인 안전 답변이 200으로 옵니다.

---

## 2. 의료기관 (FR-02)

### `GET /facilities`

| 파라미터 | 타입 | 필수 | 기본값 | 제약 |
|---|---|:---:|---|---|
| `lat` | double | ✅ | — | −90 ~ 90 |
| `lng` | double | ✅ | — | −180 ~ 180 |
| `radius_m` | int | | `1000` | 100 ~ 10000 |
| `open_now` | boolean | | `false` | |
| `type` | enum | | `pharmacy` | `pharmacy` \| `hospital` \| `emergency_room` |
| `limit` | int | | `50` | 1 ~ 50; nearest results only |

- 항상 **거리순**으로 정렬됩니다. 정렬 파라미터는 없습니다.
- 항상 요청한 `limit` 이하의 가장 가까운 결과만 답합니다. `open_now=true`일 때 약국과 병원 모두
  거리순 최대 100개 후보의 시간표를 조회한 뒤, 그중 가까운 열린 `limit`곳을 답합니다. 이 100개는
  상세 시간표 API의 호출량과 첫 로드 시간을 지키는 안전 상한이므로 반경 전체에 대한 완전 검색은 아닙니다.
- `open_now=true`는 **영업 중임을 아는** 곳만 남깁니다. 시간표를 읽지 못한 시설은 추측하지 않고 제외합니다.

**응답** `200` — `Facility[]`

```jsonc
[{
  "id": "facility:nmc:C1110693",     // 제공자 네임스페이스 포함. 병원은 facility:hira:<base64url(ykiho)>
  "type": "pharmacy",
  "nameKo": "청실약국", "nameEn": null,
  "addressKo": "서울특별시 중구 …", "addressEn": null,
  "phone": "02-3789-6953",
  "latitude": 37.5672818668855,      // `lat`/`lng`가 아닙니다. 요청 파라미터와 이름이 다릅니다
  "longitude": 126.978921749794,
  "distanceMeters": 140.0,           // 약국 공공 API는 km로 줍니다. 여기서는 미터입니다
  "operation": {
    "isOpenNow": true,               // null 가능 = "모름". false는 "닫힘"이라는 단정입니다
    "status": "open" | "closed" | "unknown",              // 소문자입니다
    "statusConfidence": "official_realtime" | "official_schedule" | "inferred" | "unknown",
    "verifiedAt": "2026-07-10T05:00:00Z",
    "notice": "Call ahead to confirm."
  },
  "source": { "id": "…", "provider": "…", "recordId": "C1110693",
              "retrievedAt": "…", "dataMode": "fixture" | "live", "title": "…" },
  "emergencyDay": null,               // 병원 HIRA emyDayYn만 true/false; 약국·미상은 null
  "emergencyNight": null              // 병원 HIRA emyNgtYn만 true/false; 약국·미상은 null
}]
```

> **요청은 `lat`/`lng`, 응답은 `latitude`/`longitude`입니다.** 헷갈리기 좋은 비대칭이라 적어둡니다.
> 열거값은 전부 **소문자 wire 값**입니다(`@JsonValue`). 자바 enum 이름(`OPEN`)이 아닙니다.

> `isOpenNow`가 `null`이면 **모른다는 뜻입니다.** `false`로 렌더링하지 마세요.
> `statusConfidence`가 `inferred`이면 주간 시간표 없이 추정한 값입니다 (DEV-202 대기).
> `type=emergency_room`은 국립중앙의료원 위치 데이터입니다. 목록은 약 100m 격자 중심에서 조회·캐시하지만, 거리는 현재 요청 좌표에서 m로 다시 계산합니다. 운영시간은 제공되지 않아 `isOpenNow`가 항상 `null`이며 `open_now=true`에는 포함되지 않습니다.

### `GET /facilities/{id}` — 🚧 `501 NOT_IMPLEMENTED`

UI-03 상세용. 아직 없습니다. 병원 ID의 마지막 segment는 base64url로 decode한 뒤 HIRA `ykiho`로 사용합니다.

---

## 3. 의약품 (FR-03, FR-04, FR-07)

### `GET /drugs`

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|:---:|---|
| `query` | string(2~100) | △ | 제품명 부분 일치 (한글). `ingredient`와 **둘 중 정확히 하나** |
| `ingredient` | string(2~100) | △ | **영문 성분명.** 상류가 대소문자를 구분합니다 |
| `exclude_ingredients` | string[] | | 사용자가 피할 성분. 쉼표 구분 |

- `query`와 `ingredient`를 **둘 다 주거나 둘 다 안 주면 `400 INVALID_REQUEST`**입니다.
- `ingredient=ibuprofen`(소문자)은 상류에서 **Dex이부프로펜 142건**만 잡습니다. 서버가 Title Case로 정규화하지만, 직접 부를 때 주의하세요.

**응답** `200` — `Drug[]` (검색 결과에는 안내문·DUR 경고가 **없습니다**. 상세를 부르세요)

### `GET /drugs/{itemSeq}`

식약처 제품 코드(예: `202005623`)입니다. `drug:mfds:` 접두사가 아닙니다.

**응답** `200` — 허가정보 + e약은요 + DUR을 `ITEM_SEQ`로 합친 `Drug` 하나

```jsonc
{
  "id": "drug:mfds:202005623",
  "itemSeq": "202005623",
  "nameKo": "어린이타이레놀산160밀리그램(아세트아미노펜)", "nameEn": null,
  "manufacturerKo": "켄뷰코리아판매유한회사",
  "ingredientsEn": ["Acetaminophen Granules"],   // 알레르기 판정은 이 필드로 합니다
  "mainIngredientKo": "아세트아미노펜과립",
  "prescriptionStatus": "otc",
  "narrative": { "efficacy": "…", "useMethod": "…", "caution": "…",
                 "warning": "…", "interaction": "…", "sideEffect": "…", "storage": "…" },
  "durWarnings": [{ "kind": "combination"|"age"|"pregnancy"|"elderly",
                    "pairedItemSeq": "…", "pairedItemName": "…", "ingredientEn": "…",
                    "prohibitContent": "…", "notifiedOn": "…" }],
  "allergyCheck": { "status": "no_match_found", "matchedIngredients": [], "message": "…" },
  "source": { … }
}
```

**알레르기 4-state** (spec §2-12) — `blocked` · `warning` · `no_match_found` · `unknown`

- **`no_match_found`를 "복용 가능"으로 표시하지 마세요.** "우리가 가진 성분 목록에 일치가 없다"는 뜻일 뿐입니다.
- **`unknown`은 비교 자체를 못 했다는 뜻**입니다. `no_match_found`와 다릅니다.
- 정확 일치와 **검토된 동의어**만 `blocked`가 됩니다. 철자 유사성으로 차단하지 않습니다.

> **알려진 한계.** 성분 단위 매칭이라 **약물 계열 교차반응을 보지 못합니다.** 이부프로펜 알레르기 사용자에게 나프록센(같은 NSAID)이 `no_match_found`로 나옵니다. 임상 판단이 필요한 사안이라 코드로 지어내지 않았습니다 (spec §2-12).

---

## 4. 프로필 CRUD (FR-04, FR-05)

`deviceId`는 브라우저가 만든 UUID입니다. **계정이 아니고, 비밀번호도 없습니다.** 그것을 아는 사람은 그 프로필을 읽고 고칠 수 있습니다 — 식별 정보를 아무것도 저장하지 않기 때문에 허용됩니다. **의료 상담 기록은 서버에 오지 않습니다.**

`GET /profiles/{deviceId}`는 **없으면 만듭니다** (404가 아닙니다).

| Method | 경로 | 요청 본문 | 성공 |
|---|---|---|---|
| GET | `/profiles/{deviceId}` | — | `200` |
| PATCH | `/profiles/{deviceId}` | `{"countryCode":"US"}` (정확히 2자) | `200` |
| PATCH | `/profiles/{deviceId}/consent` | `{"rememberAllergies":true}` | `200` |
| POST | `/profiles/{deviceId}/allergies` | `{"ingredientNameEn":"Ibuprofen","ingredientNameKo":null}` | `201` |
| DELETE | `/profiles/{deviceId}/allergies/{allergyId}` | — | `204` |
| POST | `/profiles/{deviceId}/favorites` | `{"facilityId":"facility:nmc:C1110693","facilityType":"pharmacy","alias":"집앞","memo":null}` | `201` |
| PATCH | `/profiles/{deviceId}/favorites/{favoriteId}` | `{"alias":"…","memo":"…"}` | `200` |
| DELETE | `/profiles/{deviceId}/favorites/{favoriteId}` | — | `204` |

- 존재하지 않는 `allergyId`/`favoriteId`를 지우면 `404 RESOURCE_NOT_FOUND`입니다. 남의 것도 마찬가지입니다.
- `DELETE`는 본문 없이 `204`입니다.

**동의(consent)가 먼저입니다.** `rememberAllergies`가 `false`인 상태에서 알레르기를 추가하면 `400`입니다.
**동의를 끄면 저장된 성분이 삭제됩니다.** 숨기는 게 아니라 지웁니다 (spec §2-5). 검증됨.

```
POST  /allergies                            → 400   (동의 없음)
PATCH /consent {"rememberAllergies":true}   → 200
POST  /allergies                            → 201
PATCH /consent {"rememberAllergies":false}  → 200   그리고 allergies == []
```

`allergies[].confidence`는 `exact` · `synonym` · `partial` · `unknown`. **`exact`와 `synonym`만 약을 차단할 수 있습니다.**

---

## 5. 에러 계약

모든 에러가 같은 봉투로 옵니다.

```jsonc
{ "error": {
    "code": "INVALID_REQUEST",
    "message": "Parameter 'lat' is out of range or malformed.",  // 사람이 읽는 문장. 분기하지 마세요
    "retryable": false,                                          // "다시 시도" 버튼이 정직한가
    "request_id": "f36fb65a-…",                                  // X-Request-Id 헤더와 같은 값
    "details": { }                                               // 있을 때만
} }
```

> **`code`로 분기하고 `message`로 분기하지 마세요.** 메시지는 사람을 위해 바뀝니다.
> 서버 내부 사정(스택트레이스, 상류 에러 문자열)은 절대 나가지 않습니다.

| 코드 | HTTP | retryable | 지금 발생하나 |
|---|---|:---:|---|
| `INVALID_REQUEST` | 400 | ✗ | ✅ |
| `RESOURCE_NOT_FOUND` | 404 | ✗ | ✅ |
| `SOURCE_UNAVAILABLE` | 503 | ✓ | ✅ |
| `NOT_IMPLEMENTED` | 501 | ✗ | ✅ |
| `INTERNAL_ERROR` | 500 | ✗ | ✅ |
| `INPUT_TOO_LARGE` | 413 | ✗ | ⬜ 아직 |
| `UNSUPPORTED_MODEL` | 400 | ✗ | ⬜ 아직 |
| `RATE_LIMITED` | 429 | ✓ | ⬜ 아직 |
| `LOCATION_REQUIRED` | 400 | ✗ | ⬜ 아직 |
| `AI_PROVIDER_TIMEOUT` | 504 | ✓ | ⬜ 아직 |
| `AI_PROVIDER_ERROR` | 502 | ✓ | ⬜ 아직 |
| `AI_SCHEMA_INVALID` | 502 | ✓ | ⬜ 아직 |
| `FACILITY_PROVIDER_TIMEOUT` | 504 | ✓ | ⬜ 아직 |
| `DRUG_PROVIDER_TIMEOUT` | 504 | ✓ | ⬜ 아직 |
| `SOURCE_PAYLOAD_INVALID` | 502 | ✓ | ⬜ 아직 |

> **⬜ 열 개는 정의만 돼 있고 아직 아무 코드도 던지지 않습니다.** 프론트가 그 분기를 써도 절대 실행되지 않습니다.
> 없애지 않고 남겨둔 건 BE-2의 의료기관 작업(`FACILITY_PROVIDER_TIMEOUT`)처럼 곧 쓰일 것들이 섞여 있기 때문입니다.
> 코드를 추가·삭제하는 건 **API 계약 변경**입니다. 백엔드 enum, 프론트 union(`lib/types.ts`), 이 문서를 같은 PR에서 고치세요.

**`INVALID_REQUEST`의 메시지는 일부러 뭉뚱그려집니다.** `ApiException`이 들고 있는 문장은 **내부용**이고, 밖으로는 안전한 일반 문장이 나갑니다. `query`와 `ingredient`를 둘 다 준 경우와 둘 다 안 준 경우를 클라이언트가 구분할 수 없습니다 — 필요하면 `details`를 채우는 쪽으로 가야 합니다.

---

## 6. 개발 모드

```bash
DATA_MODE=fixture ./gradlew bootRun   # 공공 API를 한 번도 부르지 않습니다
```

- `live` — 공공 API만 호출. 실패하면 에러
- `hybrid` (기본) — 호출하고, 실패하면 픽스처로 폴백하며 그렇게 **표시**합니다
- `fixture` — 네트워크 없음. 재현 가능한 시연, CI, 그리고 약국 API의 하루 1,000회 한도 회피

> **픽스처 모드는 쿼리 파라미터를 무시합니다.** `GET /drugs/999999999`가 200에 타이레놀을 돌려줍니다.
> 라이브에서는 404입니다. 픽스처로 "찾을 수 없음"을 테스트할 수 없습니다.

`source.dataMode`가 `fixture`인 데이터는 **절대 라이브인 척하지 않습니다.**

---

## 7. 공공 API 접근 상태

**2026-07-10 기준 8종 전부 사용 가능합니다.** `./bin/check-api-access.py`가 언제든 다시 확인해줍니다.

| 공공 API | 상태 |
|---|---|
| 약국 좌표 조회 | ✅ 200 |
| 의약품 허가정보 · e약은요 · DUR | ✅ 200 |
| 심평원 병원정보서비스 (목록·`ykiho` 발급) | ✅ 200 |
| 심평원 의료기관별상세정보서비스 (진료시간) | ✅ 200 |
| 국립중앙의료원 병·의원 목록 | ✅ 200 |
| 국립중앙의료원 응급실 좌표 조회 | ✅ 200 |

`GET /facilities?type=hospital`은 DEV-203에서 구현됐습니다. 이름·주소·전화·좌표·거리와
진료시간을 제공하며, HIRA의 `emyDayYn`/`emyNgtYn`은 advisory 응급 가능 여부로 함께 보냅니다.
시간표가 없는 기관은 `operation.isOpenNow`가 `null`(모름)이어야 합니다 — `false`(닫힘)가 아니라
(spec §2-13).

> **파서를 쓰기 전에 `fixtures/README.md` 12~17번을 읽으세요.**
> 특히 `distance`가 심평원은 **미터**, 국립중앙의료원은 **km**입니다. 반대입니다.
