# AGENTS.md

이 저장소에서 일하는 **모두**를 위한 안내입니다. 사람이든, AI 에이전트든 같은 규칙을 따릅니다.

팀 다섯 명 중 개발이 처음인 사람도 있고, 비개발자도 있습니다. 그래서 이 문서는 "무엇을 하라"뿐 아니라 **왜 그런지**를 같이 적었습니다. 이유를 모르는 규칙은 지켜지지 않고, 지켜지더라도 응용이 안 되니까요.

읽다가 틀린 곳을 찾으면 고쳐서 PR을 올려 주세요. 문서가 코드보다 먼저 낡습니다.

---

## 0. 30초 요약

```bash
./bin/setup.sh                       # 훅 + .env + 의존성. 한 번만
docker compose up -d                 # MariaDB + Redis

cd backend  && ./gradlew bootRun     # http://localhost:8080
cd frontend && pnpm dev              # http://localhost:5173
```

작업을 끝내기 전에 **반드시 이 두 개를 직접 돌리고, 통과하는 것을 눈으로 봅니다.**

```bash
cd backend  && ./gradlew test
cd frontend && pnpm test && pnpm build
```

브랜치는 `feat/DEV-203-hospital-adapter` 꼴, 커밋은 `feat(facility): ...` 꼴입니다.
`main`에 직접 푸시하지 않습니다.

---

## 1. 이 저장소가 무엇인가

한국에 있는 영어 사용자가 로그인 없이 영어로 증상을 말하면,

1. **정부 공공데이터로 검증된** 의약품 정보를 영어로 설명하고,
2. 지금 영업 중인 근처 약국·병원을 지도에 보여줍니다.

핵심은 **"검증된"** 입니다. 이 서비스는 AI가 아는 것을 말하지 않습니다. 식약처·심평원·국립중앙의료원이 답해 준 것만 말합니다. AI는 *어떤 성분을 찾아볼지 정하고*, *찾아온 사실을 영어로 풀어쓰는* 두 가지만 합니다.

```
브라우저 ──(openai JS SDK, baseURL=/api/v1)──▶ Spring 프록시 ──▶ LLM
                                                  │
                                                  └──▶ 공공 API 8종
```

이걸 **2-패스 RAG**라고 부릅니다.

- **1a단계** — LLM에게 묻습니다. "이 증상엔 어떤 *성분*을 찾아봐야 하나?" → `["Acetaminophen", "Ibuprofen"]`
- **1b단계** — 그 성분명으로 **서버가** 식약처 API를 부릅니다.
- **2단계** — LLM에게 찾아온 약 목록만 주고 말합니다. "이것들만 가지고 설명해."

> **1a단계의 출력은 사실이 아니라 질문입니다.** 모델이 "타이레놀"이라고 말해도 그건 검색어일 뿐, 우리가 사용자에게 보여줄 약이 아닙니다. 보여줄 약은 **1b단계에서 정부 API가 돌려준 것뿐**입니다. 2단계에서 모델이 목록에 없는 약 이름을 지어내면 서버가 그 답을 통째로 버립니다(후처리 불변조건 6번).

코드보다 먼저 [`docs/specs/001-foundation/spec.md`](docs/specs/001-foundation/spec.md)를 읽으세요. 특히 §2(원본 요구사항에서 바꾼 것)와 §3(검증된 외부 제약).

---

## 2. 절대 어기면 안 되는 것

의료 정보 서비스입니다. 아래 규칙들은 스타일 취향이 아니라, **틀리면 사람이 다칠 수 있는 지점**입니다. 각각 왜 그런지 적어 뒀습니다. 지우거나 우회해야 할 것 같으면, 먼저 이슈를 열고 이야기해 주세요.

### 2-1. 진단하지 않는다

증상을 듣고 병명을 말하지 않습니다. 정보를 주고 전문가 상담을 권합니다. 면책 문구(disclaimer)는 **모든 응답에** 붙고, 화면에 **항상 보입니다**. 클라이언트가 보낸 `system` 메시지는 프록시가 버립니다 — 사용자가 "너는 의사야"라고 프롬프트를 덮어쓰지 못하게.

### 2-2. `no_match_found`는 "안전하다"가 아니다

알레르기 확인은 네 가지 상태입니다: `blocked` / `warning` / `no_match_found` / `unknown`.

`no_match_found`의 뜻은 **"우리가 가진 성분 목록에서 일치하는 것을 못 찾았다"** 입니다. "이 약은 당신에게 안전하다"가 **아닙니다.**

이부프로펜에 알레르기가 있는 사람에게 나프록센을 보여주면 `no_match_found`가 나옵니다. 둘 다 NSAID 계열이라 교차반응이 흔한데도요. 우리에겐 계열 교차반응 표가 없고, **임상 검토자 없이 그 표를 만들 수는 없습니다**(AR-01). 그래서:

- 초록색 배지를 달지 않습니다. 초록 배지는 걱정하는 사람에게 **허락으로 읽힙니다.**
- "safe"라는 단어를 쓰지 않습니다.
- 대신 알레르기가 선언되면 **모델이 약을 고르지 못하게 막습니다**(SA-08). 표를 만드는 대신 생성기를 묶었습니다.

이건 테스트로 지켜집니다 → `frontend/src/components/AllergyBadge.test.tsx`

### 2-3. `isOpenNow: null`은 "닫혔다"가 아니다

`null`은 **"우리가 모른다"** 입니다. 화면엔 `Hours unknown`으로 그립니다. `Closed`로 그리면, 실제로는 열려 있는 약국을 밤중에 아픈 사람이 지나치게 됩니다.

어떤 공공 API도 "지금 영업 중" 필터를 주지 않습니다. 우리가 시간표를 받아서 계산합니다. 시간표가 없으면 모르는 겁니다.

### 2-4. 응급은 모델보다 먼저 판단한다

`EmergencyTriage`가 사용자 문장을 **모델을 부르기 전에** 정규식으로 선별합니다. 걸리면 모델을 아예 안 부르고 코드가 답합니다(31ms).

왜냐하면 실제로 이런 일이 있었기 때문입니다:

> 입력: `"crushing chest pain and I cannot breathe properly"`
> 모델의 답: `urgency: "unknown"`

"모델이 emergency라고 말하면 119 버튼을 띄운다"는 규칙은, 모델이 emergency라고 말하지 않으면 아무것도 못 잡습니다.

### 2-5. 대화는 서버에 저장하지 않는다

의료 상담 내용은 `sessionStorage`에 있고 **탭을 닫으면 사라집니다.** `localStorage`에는 절대 쓰지 않습니다 — `localStorage`는 탭보다 오래 살고, 그 기기를 쓰는 누구나 읽을 수 있습니다.

서버 DB에는 ERD를 그릴 수 있는 것만 갑니다: 즐겨찾기, 알림 설정, (동의한 경우) 알레르기 프로필.

알레르기 기억은 **opt-in이고 기본값은 꺼짐**입니다. 꺼져 있으면 읽을 때도 쓸 때도 알레르기 목록을 버립니다.

이것도 테스트로 지켜집니다 → `frontend/src/lib/storage.test.ts`

### 2-6. "사람이 확인했다"는 칸은 사람만 채운다

`backend/src/main/resources/ingredients/synonyms.tsv`에 `reviewer` 칸이 있습니다. 그 칸의 내용은 **"사람이 이 줄을 확인했다"는 사실 그 자체**입니다.

AI가 채우면, 그 순간부터 그 칸은 영원히 아무 의미가 없습니다. 읽는 모든 사람에게요. 검토자 이름을 넣는 건 PM/QA의 일입니다([검토 시트](docs/specs/001-foundation/DEV-305-synonym-review.md)에 판정만 하면 되게 준비해 뒀습니다). 서명이 없는 줄은 로더가 `blocked` 처리합니다(AR-02).

### 2-7. 시크릿에 `VITE_` 접두사를 붙이지 않는다

Vite는 `VITE_`로 시작하는 모든 환경변수를 **브라우저에 내려가는 자바스크립트 안에 문자열로 박아 넣습니다.** 런타임에 읽는 게 아니라 빌드할 때 치환합니다. `dist/assets/index-*.js`를 열면 누구나 볼 수 있습니다.

2026-07-10, 네이버 **Client Secret**이 `VITE_NAVER_MAP_KEY_ID`에 들어가 번들에 컴파일됐습니다. 커밋되지 않았어도 산출물에 박힌 시크릿은 폐기 대상입니다. 폐기하고 재발급했습니다.

지금은 `vite.config.ts`가 `SECRET|PASSWORD|PRIVATE_KEY|TOKEN|CREDENTIAL` 같은 이름의 `VITE_` 변수를 보면 **빌드를 거부합니다.** 경고는 스크롤에 묻히지만, 실패하는 빌드는 묻히지 않습니다.

| 이름 | 어디 | 공개 여부 |
|---|---|---|
| `VITE_NAVER_MAP_CLIENT_ID` | 브라우저 | 공개 (그래도 됨) |
| `NAVER_MAP_CLIENT_SECRET` | 서버만 | **비밀** |
| `DATA_GO_KR_SERVICE_KEY` | 서버만 | **비밀** |
| `LLM_API_KEY` | 서버만 | **비밀** |

### 2-8. `.env`를 커밋하지 않는다

이 저장소는 **public**입니다. 유출된 키는 몇 분 안에 수집됩니다. pre-commit 훅과 CI의 gitleaks가 막아 주지만, **훅을 믿지 말고 조심하세요.** 훅은 마지막 방어선이지 첫 번째가 아닙니다.

### 2-9. 데이터의 출처를 지어내지 않는다

모든 사실 카드에는 `sourceRef`가 붙습니다. 어느 기관이, 언제 준 데이터인지. 이건 **서버가 채웁니다.** 모델은 `sourceRefId`로 참조만 합니다.

fixture 데이터를 live인 것처럼 보여주지 않습니다. `dataStatus`가 `fixture`면 화면이 그렇게 말합니다.

---

## 3. 실행하기

### 처음 한 번

```bash
./bin/setup.sh
```

훅을 등록하고, `.env.example`을 `.env`로 복사하고, 의존성을 설치합니다. 그다음 `.env`를 채웁니다.

| 키 | 어디서 받나 | 함정 |
|---|---|---|
| `DATA_GO_KR_SERVICE_KEY` | [data.go.kr](https://www.data.go.kr) | **Decoding 키**를 넣으세요. Encoding 키를 넣으면 `SERVICE_KEY_IS_NOT_REGISTERED_ERROR`가 납니다 |
| `VITE_NAVER_MAP_CLIENT_ID` | NCP 콘솔 > Maps > Application | **Client ID**입니다. Secret이 아닙니다. Web 서비스 URL에 `http://localhost:5173`을 등록해야 합니다 |
| `LLM_API_KEY` | OpenAI 호환 엔드포인트 | |

### 매번

```bash
docker compose up -d                 # MariaDB + Redis
cd backend  && ./gradlew bootRun
cd frontend && pnpm dev
```

프론트엔드는 Vite 프록시로 `/api`를 백엔드에 넘깁니다. 브라우저에서 보면 **모든 게 한 오리진**이라 CORS 문제가 없습니다.

### 네트워크 없이 개발하기

```bash
cd backend && DATA_MODE=fixture ./gradlew bootRun
```

공공 API를 한 번도 부르지 않고 실제 응답으로 개발합니다.

**약국 API는 하루 1,000회**입니다. 다섯 명이 지도를 몇 번 새로고침하면 점심 전에 소진됩니다. 평소엔 `fixture`로 개발하세요.

> `fixture` 모드는 **쿼리 파라미터를 무시합니다.** 좌표를 바꿔도 같은 약국이 나옵니다. 필터링 로직을 테스트하려면 `hybrid`나 단위 테스트를 쓰세요.

공공 API 키가 살아 있는지 확인:

```bash
./bin/check-api-access.py          # 8종 전부 [OK] 여야 정상
```

---

## 4. "완료"란 무엇인가

**"제 컴퓨터에선 됐어요"는 완료가 아닙니다.** 완료는 아래 명령이 **지금 이 순간** 통과하는 것입니다. 기억 속의 통과가 아니라요.

```bash
cd backend  && ./gradlew test        # 275 tests
cd frontend && pnpm test             # 48 tests
cd frontend && pnpm build            # tsc -b 를 포함합니다
```

세 개 다 돌리고, **종료 코드가 0인 것**을 보세요. 리포트 파일을 보지 마세요.

> 왜 이렇게까지 적냐면: 테스트 러너가 아예 시작하지 못했는데도 **직전 실행이 남긴 리포트 파일**을 읽고 "222개 통과"라고 두 번 보고한 적이 있습니다. 러너가 실행되지 않으면 이전 결과가 그 자리에 그대로 있습니다. 통과의 증거는 **러너의 종료 코드**이지 러너가 남긴 파일이 아닙니다.

### 브라우저를 띄워야 하는 것

UI를 건드렸다면 **브라우저에서 직접 눌러 보세요.** `curl`은 클라이언트 절반을 통째로 건너뜁니다.

2026-07-10, 백엔드 테스트 275개가 전부 초록인 채로 **챗은 브라우저에서 단 한 번도 동작한 적이 없었습니다.** `baseURL`이 상대 경로라 SDK가 요청 URL을 만들다가 던졌고, 그 예외를 `catch`가 먹어서 화면엔 "something went wrong"만 떴습니다. 네트워크 탭은 비어 있었고요. 타입체크도, 빌드도, 테스트도 전부 통과했습니다.

---

## 5. 브랜치

`main`에 직접 푸시하지 않습니다. 언제나 브랜치를 만들고 PR을 올립니다.

```
<타입>/<작업ID>-<영어-요약>
```

```bash
git switch -c feat/DEV-203-hospital-adapter
git switch -c fix/DEV-206-manual-location-entry
git switch -c docs/DEV-305-synonym-review-sheet
```

**작업 ID(`DEV-203`)를 꼭 넣으세요.** [`tasks.md`](docs/specs/001-foundation/tasks.md)의 WBS와 브랜치와 커밋과 PR이 한 줄로 이어집니다. **작업 추적성 자체가 채점 항목입니다**(NFR-05).

타입은 커밋 타입과 같습니다 (§6).

### 레인별로 어디를 건드리나

다섯 명이 겹치지 않게 나눴습니다. 누가 어느 레인인지는 [`tasks.md` §3](docs/specs/001-foundation/tasks.md)에 있습니다.

| 레인 | 주로 건드리는 곳 | 브랜치 예 |
|---|---|---|
| **BE-1** | `backend/…/chat/`, `backend/…/drug/`, `common/`, `config/` | `feat/DEV-102-answer-schema` |
| **BE-2** | `backend/…/facility/` | `feat/DEV-203-hospital-adapter` |
| **FE-1** | `frontend/src/App.tsx`, `components/` (챗·약 카드) | `feat/DEV-308-drug-card` |
| **FE-2** | `frontend/src/components/FacilityMap.tsx`, `hooks/`, `lib/storage.ts` | `feat/DEV-207-detail-drawer` |
| **PM/QA** | `docs/`, `synonyms.tsv` 검토 칸 | `docs/DEV-305-synonym-review` |

남의 레인 파일을 고쳐야 하면, **고치기 전에 그 사람에게 말하세요.** 특히 `lib/types.ts`와 `backend/…/chat/dto/`는 프론트와 백엔드의 계약입니다. 한쪽만 바꾸면 다른 쪽이 없는 필드를 그립니다.

---

## 6. 커밋 메시지

[Conventional Commits](https://www.conventionalcommits.org)를 씁니다. **영어로** 씁니다.

```
<타입>(<범위>): <무엇을 왜 바꿨는지, 소문자로 시작, 마침표 없음>

<필요하면 본문. 왜 이렇게 했는지, 무엇을 시도했다 버렸는지.>
```

### 타입

| 타입 | 언제 |
|---|---|
| `feat` | 사용자가 볼 수 있는 기능이 생겼다 |
| `fix` | 버그를 고쳤다 |
| `docs` | 문서만 바꿨다 |
| `test` | 테스트만 더하거나 고쳤다 |
| `refactor` | 동작은 그대로, 구조만 바꿨다 |
| `perf` | 동작은 그대로, 빨라졌다 |
| `chore` | 빌드·설정·의존성 |

### 범위

우리 저장소의 경계에 맞춥니다: `chat`, `drug`, `facility`, `web`, `config`, `ci`, `docs`

(초기 커밋들은 범위가 들쭉날쭉합니다 — `backend`, `api`, `map` 같은 게 섞여 있어요. 앞으로는 위 목록으로 통일합니다. 과거를 고치진 않습니다.)

### 좋은 예 — 우리 저장소에서 실제로

```
perf(drug): fetch the ministry's APIs concurrently, not one at a time
fix(config): correct the 심평원 hospital endpoints, and tell 403 from 404
test(web): give the frontend its first tests, starting with the bugs that shipped
```

### 아쉬운 예

```
fix: 버그 수정            ← 무슨 버그? 한국어 커밋 메시지
update code               ← 타입 없음, 아무 정보 없음
feat: WIP                 ← 커밋하지 말고 브랜치에 두세요
```

**제목은 "무엇"보다 "왜"에 가깝게.** 무엇을 바꿨는지는 diff가 말해 줍니다. 왜 바꿨는지는 당신만 압니다.

---

## 7. PR과 리뷰

- PR 하나에 하나의 일. 리뷰어가 한 번에 읽을 수 있는 크기로.
- [PR 템플릿](.github/pull_request_template.md)의 체크리스트를 **실제로 확인하고** 체크하세요. 확인 안 했는데 체크하는 건 §2-6과 같은 종류의 거짓말입니다.
- CI(백엔드 테스트 / 프론트 테스트·빌드 / 시크릿 스캔)가 초록불이 아니면 머지하지 않습니다.
- 리뷰에서 "왜 이렇게 했어요?"는 공격이 아닙니다. 답이 코드에 없으면 주석이나 커밋 본문에 있어야 한다는 뜻입니다.

리뷰할 때 특히 볼 것:

- §2의 안전 규칙 중 하나를 우회하고 있지는 않은가
- 새 로직에 테스트가 붙었는가
- 스펙과 어긋나면 `docs/specs/`도 같이 고쳤는가

---

## 8. 코드 스타일

**주변 코드처럼 쓰세요.** 이 규칙 하나가 아래 전부보다 중요합니다.

### 이름

| | 백엔드 (Java) | 프론트 (TypeScript) |
|---|---|---|
| 클래스·타입 | `PascalCase` — `DrugService` | `PascalCase` — `MermAidAnswer` |
| 메서드·함수 | `camelCase` — `retrieve()` | `camelCase` — `fetchFacilities()` |
| 상수 | `UPPER_SNAKE` — `MAX_CONTEXT_DRUGS` | `UPPER_SNAKE` — `SEOUL_CITY_HALL` |
| 컴포넌트 | — | `PascalCase` 파일 — `FacilityMap.tsx` |
| 훅 | — | `use` 접두사 — `useNaverMap.ts` |

API 응답의 JSON 필드는 `camelCase`입니다. 단, **공공 API가 주는 이름은 절대 바꾸지 않습니다** — `XPos`, `dutyTime1s`, `MAIN_INGR_ENG` 그대로 파싱하고, 우리 도메인 타입으로 옮길 때 이름을 정리합니다.

쿼리 파라미터는 `snake_case`입니다: `radius_m`, `open_now`. 응답 바디는 `camelCase`입니다: `distanceMeters`, `isOpenNow`.

> 요청은 `lat`/`lng`를 받고 응답은 `latitude`/`longitude`를 돌려줍니다. 오타가 아닙니다. 한쪽을 "고치면" 다른 쪽이 깨집니다. `bin/verify-api-doc.sh`가 이걸 검사합니다.

### 언어

- 코드, 주석, 커밋 메시지, PR 제목 → **영어**
- 이슈 본문, PR 설명, 문서, 서로에게 하는 말 → **한국어** 편한 대로

한국어 도메인 용어(`심평원`, `식약처`, `수출용`)는 주석에 한글로 써도 됩니다. 그게 더 정확하니까요.

### 검증은 경계에서만

사용자 입력, 외부 API 응답 — 여기서만 검증합니다. 내부 함수끼리는 서로를 믿습니다. 일어날 수 없는 경우를 위한 방어 코드는 쓰지 않습니다. 읽는 사람이 "이게 언제 null이지?" 하고 5분을 씁니다.

---

## 9. 주석

**주석은 코드가 보여줄 수 없는 것만 적습니다.**

무엇을 하는지는 코드가 말합니다. 주석이 말해야 하는 건 **왜 그래야만 하는지**, 그리고 **다르게 하면 무엇이 깨지는지**입니다.

```java
// ❌ 다음 줄이 무엇을 하는지 말한다 — 코드를 읽으면 안다
// itemSeq로 상세 정보를 가져온다
var detail = permissionClient.detail(itemSeq);
```

```java
// ✅ 코드가 보여줄 수 없는 제약을 말한다
/** Four, and raising it will not help. Measured 2026-07-10: four DUR calls take
 *  5.77s in sequence and 2.70s together — a 2.1× speed-up, not 4×. */
private static final int UPSTREAM_CONCURRENCY = 4;
```

```ts
// ✅ 다르게 하면 무엇이 깨지는지 말한다
// Naver markers are not React children. Nothing removes them for us, and a second
// render would silently stack a new pin on every old one.
markersRef.current.forEach((m) => m.setMap(null))
```

특히 이런 것들을 적어 주세요. 다음 사람이 반나절을 아낍니다.

- **측정한 숫자와 측정한 날짜.** "빠르다"가 아니라 "5.77s → 2.70s, 2026-07-10 측정"
- **시도했다 버린 방법과 왜.** `react-naver-maps`를 안 쓴 이유가 훅 주석에 있습니다
- **공공 API의 함정.** `distance`가 기관마다 미터/킬로미터로 다르다는 것 같은
- **안전 규칙과 그 근거.** `no_match_found`에 왜 초록 배지를 달면 안 되는지

적지 말아야 할 것:

- 커밋 메시지에 있어야 할 말 (`// 2026-07-10 수정`, `// 한결이 고침`)
- 리뷰어에게 하는 말 (`// 이 부분이 핵심입니다`)
- 지워진 코드 (`git log`가 기억합니다)

---

## 10. 테스트

```bash
cd backend  && ./gradlew test        # 275
cd frontend && pnpm test             # 48
cd frontend && pnpm test:watch       # 고치면서 볼 때
```

새 로직에는 테스트를 붙입니다. 특히 §2의 안전 규칙을 건드리는 코드라면요.

### 테스트를 만들 때

**통과하는 테스트가 곧 무언가를 잡는 테스트는 아닙니다.** 새 테스트를 썼으면, 검증하려는 코드를 **일부러 망가뜨려 보세요.** 테스트가 빨간불이 안 되면 그 테스트는 아무것도 지키고 있지 않습니다.

이 저장소의 프론트 테스트 5개 파일은 전부 이렇게 확인했습니다. 예를 들어 `storage.test.ts`는 대화를 `localStorage`로 옮기면 즉시 4개가 실패합니다.

### 테스트를 고치지 마세요

테스트가 실패하면 **코드가 틀린 것**입니다. 테스트를 통과시키려고 테스트를 약화시키거나, 지우거나, 건너뛰지 마세요.

테스트가 정말 틀렸다고 생각되면 — 그럴 수 있습니다 — 고치지 말고 **말해 주세요.** 실제로 이런 테스트가 있었습니다:

> `"hospitals are not implemented yet and return nothing rather than lying"`

이름은 정직해 보이지만, 그 테스트가 지키던 동작(`200 []`)은 **"근처에 병원이 없습니다"라는 거짓말**이었습니다. 진실은 "우리가 아직 찾아볼 수 없습니다"였고요. 지금은 `501`을 돌려줍니다.

---

## 11. 자주 밟는 함정

전부 실제로 밟았던 것들입니다. [`fixtures/README.md`](backend/src/main/resources/fixtures/README.md)에 17가지가 더 있습니다.

**공공 API**

- JSON을 요청하는 파라미터가 기관마다 다릅니다. 약국·심평원은 `_type=json`, 식약처는 `type=json`. **언더스코어 하나** 차이로 조용히 XML이 옵니다.
- `distance`의 **단위가 기관마다 반대**입니다. 국립중앙의료원(약국)은 **km**, 심평원(병원)은 **미터**. 한쪽 파서를 복사하면 반경 필터가 1000배 틀립니다.
- **`404`는 서비스가 없다는 뜻이 아니라 오퍼레이션 이름이 틀렸다는 뜻입니다.** `MadmDtlInfoService2.8/getDtlInfo`는 404이고, 진짜 이름은 `getDtlInfo2.8`입니다. 버전 접미사가 서비스에도, 오퍼레이션에도 붙습니다. 이걸 몰라서 멀쩡한 설정을 "고쳤다가" 되돌렸습니다.
- `401` = 키를 모름 / `403` = 키는 알지만 서비스 미승인 / `404` = 오퍼레이션 이름 틀림 / `500 Unexpected errors` = 서비스 경로 자체가 없음.

**네이버 지도**

- 키 파라미터는 `ncpKeyId`입니다. 인터넷 예제의 `ncpClientId`는 인증에 실패합니다.
- **틀린 키로도 `maps.js`는 200을 주고, `onload`가 뜨고, `naver.maps`가 정의되고, 지도가 만들어집니다.** 그다음에야 `navermap_authFailure()`가 불립니다. 그래서 콜백을 스크립트 붙이기 **전에** 등록합니다. 무시하면 사용자는 회색 빈 상자를 "로딩 중"으로 읽습니다.

**LLM**

- `WebClient`에 User-Agent를 명시하지 않으면 Cloudflare가 전부 403으로 막습니다.
- `response_format`(구조화 출력) 지원이 모델마다 다릅니다. 지원 목록은 설정에 allowlist로 있고, 400이 오면 스키마 없이 한 번 재시도합니다.

**Spring**

- Redis 기본 직렬화는 JDK입니다. Java `record`는 `Serializable`이 아니라 터집니다. `cache.type=simple`인 테스트로는 절대 못 잡습니다.
- `IllegalArgumentException`을 `INVALID_REQUEST`로 매핑하지 마세요. Spring과 Jackson이 자기 버그로 던집니다 → 서버 오류가 사용자 잘못으로 보고됩니다.

---

## 12. 막혔을 때

1. [`spec.md`](docs/specs/001-foundation/spec.md) §3 — 검증된 외부 제약
2. [`fixtures/README.md`](backend/src/main/resources/fixtures/README.md) — 실제 응답에서 발견한 함정 17가지
3. `TODO(team)` 으로 검색 — 어디를 채워야 하는지 표시해 뒀습니다
4. 그래도 막히면 **30분 안에 물어보세요.** 혼자 반나절 태우는 것보다 훨씬 낫습니다.

버그를 발견했다면 [이슈 템플릿](.github/ISSUE_TEMPLATE/bug.yml)으로 올려 주세요. 재현 절차·기대·실제·증거. 응답의 `request_id`(또는 `X-Request-Id` 헤더)를 같이 적어 주면 로그를 찾을 수 있습니다.

---

## 13. 비개발자를 위한 안내

코드를 안 짜도 이 프로젝트에서 **완료를 보증하는 산출물**을 냅니다. 그게 PM/UX/QA 레인입니다.

- **동의어 사전 검토** — 지금 가장 급합니다. [검토 시트](docs/specs/001-foundation/DEV-305-synonym-review.md)에 판정만 하면 되게 준비해 뒀습니다. §2-6을 읽어 주세요: 이 칸은 사람만 채울 수 있습니다.
- **영어 문구** — 모든 상태(빈 화면·오류·로딩·차단)의 문구가 필요합니다. 아픈 사람이 읽습니다.
- **fixture 사람 검증** — `backend/src/main/resources/fixtures/`의 실제 응답이 화면에 제대로 나오는지.
- **버그 리포트** — 재현 절차가 있는 리포트 하나가 "안 돼요" 열 개보다 낫습니다.

GitHub에서 파일을 고치는 건 브라우저에서 바로 할 수 있습니다. 연필 아이콘 → 고치기 → "Create a new branch for this commit" 선택 → PR. 터미널을 안 열어도 됩니다.

**모르는 걸 물어보는 게 이 팀에서 가장 값싼 행동입니다.**

---

## 14. AI 에이전트에게

사람에게 적용되는 위의 모든 규칙이 그대로 적용됩니다. 더해서:

- **읽지 않은 코드의 동작을 주장하지 마세요.** 파일을 열고 확인한 다음에 답하세요.
- **"완료"는 검증된 것만.** 완료 기준을 재실행 가능한 명령으로 먼저 말하고, 끝났을 때 **다시 돌려서** 통과를 보세요. 기억이 아니라 지금 이 순간의 통과여야 합니다.
- **잘린 출력에서 부재를 결론짓지 마세요.** `head`로 본 목록에 없다고 "없다"고 쓰면 안 됩니다.
- **`404`는 "그 이름이 틀렸다"이지 "그것이 없다"가 아닙니다.** 틀린 전제는 스스로 증거를 만들어 냅니다 — 결론 내리기 전에 "내가 틀렸다면 무엇이 달라 보일까?"를 묻고, 그걸 한 번 더 관찰하세요.
- **브라우저가 있는 것은 브라우저에서 눌러 보세요.** `curl`은 클라이언트 절반을 건너뜁니다.
- **§2-6의 검토자 칸을 채우지 마세요.** 사람 대신 서명하지 마세요. 대신 검토가 쉬워지게 근거를 모아 두세요.
- **스코프를 벗어난 파일을 건드리지 마세요.** 테스트·문서·fixture·git 히스토리는 명시적으로 지시받은 경우에만 고칩니다.
- **`git add -A`를 쓰지 마세요.** 관계없는 남의 작업이 커밋에 딸려 들어옵니다. 실제로 그런 적이 있습니다.

이 저장소에서 저지른 실수와 그 교훈은 커밋 메시지와 주석에 그대로 남겨 뒀습니다. 지우지 말아 주세요. 다음 사람이 같은 반나절을 태우지 않게 하려고 남긴 것입니다.
