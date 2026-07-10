# mermAid

한국에 있는 영어 사용자가 **로그인 없이** 영어로 증상을 말하면, 공공 데이터로 검증된 의약품 정보와 지금 영업 중인 근처 약국·병원을 알려주는 서비스.

> MINI PROJECT 1 · 1팀 · 자유주제

---

## 빠른 시작

```bash
./bin/setup.sh          # 훅 등록 + .env 생성 + 의존성 설치
# .env 를 채운다 (아래 참조)
docker compose up -d    # MariaDB + Redis

cd backend  && ./gradlew bootRun    # http://localhost:8080
cd frontend && pnpm dev             # http://localhost:5173
```

프론트엔드는 Vite 프록시로 `/api`를 백엔드에 넘깁니다. 브라우저에서 보면 **모든 게 한 오리진**이라 CORS 문제가 없습니다.

### `.env` 채우기

| 키 | 어디서 | 주의 |
|---|---|---|
| `DATA_GO_KR_SERVICE_KEY` | [data.go.kr](https://www.data.go.kr) | **Decoding 키**를 넣으세요. Encoding 키를 넣으면 `SERVICE_KEY_IS_NOT_REGISTERED_ERROR`가 납니다 |
| `VITE_NAVER_MAP_KEY_ID` | NCP 콘솔 > Maps | Web 서비스 URL에 `http://localhost` 등록 필수 |
| `LLM_API_KEY` | OpenAI 호환 엔드포인트 | |

`.env`는 커밋되지 않습니다. **이 저장소는 public이고, 유출된 키는 몇 분 안에 수집됩니다.** pre-commit 훅이 막아주지만, 훅을 믿지 말고 조심하세요.

---

## 구조

```
backend/    Spring Boot 3.5 · Java 21 · JPA · Flyway · MariaDB · Redis
frontend/   React 19 · Vite · TypeScript · Tailwind · astryx · 네이버맵
docs/specs/       스펙과 WBS. 코드보다 먼저 읽으세요
docs/deliverables/ 제출 산출물 (ERD, 테이블 명세서, 요구사항 명세서, 수행계획서)
backend/src/main/resources/fixtures/   실제 공공 API 응답. 이걸로 개발하세요
```

### 네트워크 없이 개발하기

```bash
DATA_MODE=fixture ./gradlew bootRun
```

공공 API를 한 번도 부르지 않고 실제 약국 데이터가 나옵니다.
**약국 API는 하루 1,000회**뿐이니 기본값(`hybrid`)으로도 캐시가 걸립니다.

요청은 이렇게 흐릅니다.

```
브라우저 ──(openai JS SDK, baseURL=/api/v1)──▶ Spring 프록시 ──▶ LLM
   │                                              │
   │                                              └──▶ 공공 API 6종
   ├── sessionStorage: 대화 기록 (탭을 닫으면 사라짐. 서버로 절대 가지 않음)
   └── localStorage:   deviceId, 즐겨찾기 스냅샷
```

---

## 코드를 짜기 전에 읽을 것

**[docs/specs/001-foundation/spec.md](docs/specs/001-foundation/spec.md)** — 특히 두 곳:

- **§2 원본 문서에서 달라진 점.** 요구사항 명세서 v0.1에는 실제로 구현 불가능한 요구가 몇 개 있었습니다. 무엇이 왜 바뀌었는지 근거와 함께 적혀 있습니다.
- **§3 검증된 외부 제약.** 이 표를 안 읽으면 각각 반나절씩 날립니다. 요약하면:

  - 약국 API는 **하루 1,000회**입니다. 다섯 명이 지도를 새로고침하면 점심 전에 소진됩니다. `DATA_MODE=fixture`로 개발하세요.
  - 조사 문서가 틀렸던 여섯 곳은 [`fixtures/README.md`](backend/src/main/resources/fixtures/README.md)에 있습니다. 파서를 쓰기 전에 읽으세요.
  - JSON을 요청하는 파라미터가 API마다 다릅니다. 약국·심평원은 `_type=json`, 식약처는 `type=json`. 언더스코어 하나 차이로 조용히 XML이 옵니다.
  - 네이버맵 키 파라미터는 `ncpKeyId`입니다. 인터넷 예제의 `ncpClientId`는 인증 실패합니다.
  - **어떤 공공 API에도 "지금 영업 중" 필터가 없습니다.** 우리가 계산합니다.

---

## 어디를 채워야 하나

배관은 동작합니다. 살은 아직 없습니다. `TODO(team)` 으로 검색하세요.

| 파일 | 할 일 |
|---|---|
| `PharmacyApiClient#weeklyHours` | 주간 시간표(`getParmacyBassInfoInqire`). 이게 붙어야 영업 상태가 `INFERRED`에서 `OFFICIAL_SCHEDULE`이 됩니다 |
| `FacilityService#hospitals` | 병원은 API 두 개를 엮습니다. **다만 심평원 API가 지금 403** — 활용신청 승인이 필요합니다 |
| `IngredientNormalizer` | 동의어 사전(`resources/ingredients/synonyms.tsv`)의 검토자 칸이 전부 `TODO`입니다. **QA가 채워야 합니다** |
| `HolidayCalendar` | 지금은 늘 `false`를 반환합니다. 설날에 약국이 열렸다고 말하게 됩니다 |
| `App.tsx`, `useNaverMap.ts` | UI-01 의약품 카드, UI-02 지도, UI-03 상세. **로딩 상태를 꼭 그리세요** — 챗 응답은 콜드 캐시에서 100초를 넘깁니다 |
| `ChatProxyService#prepare` | `response_format` 주입. glm-5.2는 지원하지만 `deepseek-v4-*`는 400을 냅니다 — 모델별 플래그가 필요합니다 (DEV-102) |

누가 무엇을 맡는지는 [`docs/specs/001-foundation/tasks.md`](docs/specs/001-foundation/tasks.md)를 보세요.

---

## 협업

- 브랜치: `main`에 직접 푸시하지 않습니다. `feat/…`, `fix/…` 를 만들어 PR을 올립니다.
- 커밋: [Conventional Commits](https://www.conventionalcommits.org) (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`)
- CI가 백엔드 테스트, 프론트 타입체크·빌드, 시크릿 스캔을 돌립니다. 초록불이 아니면 머지하지 않습니다.
- 이슈와 PR을 잘게 쪼개세요. **작업 추적성 자체가 채점 항목입니다** (NFR-05).

```bash
cd backend  && ./gradlew test    # 219 tests
cd frontend && pnpm build        # tsc -b 포함
```

## 제출 산출물

| 산출물 | 위치 |
|---|---|
| ERD | [`docs/deliverables/ERD.md`](docs/deliverables/ERD.md) — **실제 DB에서 생성** |
| 테이블 명세서 | [`docs/deliverables/테이블_명세서.md`](docs/deliverables/테이블_명세서.md) |
| WBS | [`docs/specs/001-foundation/tasks.md`](docs/specs/001-foundation/tasks.md) |
| API 명세서 | [`docs/deliverables/API_명세서.md`](docs/deliverables/API_명세서.md) — **실행 중인 서버에서 검증** (`./bin/verify-api-doc.sh`) |
| 요구사항 명세서 · 수행계획서 | `docs/deliverables/*.docx` |

---

## 이 서비스가 하지 않는 것

진단하지 않습니다. 정보를 제공하고 전문가 상담을 권합니다.

시스템 프롬프트에 이 제약이 박혀 있고, 클라이언트가 보낸 `system` 메시지는 프록시에서 버려집니다. 응답에는 항상 면책 문구가 붙고 화면에 표시됩니다. 스펙 §7을 읽어보세요 — 왜 이렇게 했는지 적혀 있습니다.
