# mermAid

한국에 있는 영어 사용자가 **로그인 없이** 영어로 증상을 말하면, **정부 공공데이터로 검증된** 의약품 정보를 얻고, 지금 열려 있는 근처 약국·병원·응급실을 지도로 찾는 서비스입니다.

*mermAid lets English speakers in Korea describe symptoms in English — no sign-up — and get medicine information verified against Korean government data, plus a live map of pharmacies, hospitals, and emergency rooms that are open right now.*

> MINI PROJECT 1 · 1팀 · 자유주제

<!-- TODO(PM/QA · DEV-610 #26): 히어로 스크린샷 · 기능 GIF · 아키텍처 그림 — 재료는 docs/발표-노트.md -->

## 무엇을 하나

- **증상 → 검증된 약 정보.** "I have a fever"라고 말하면 식약처 기록에서 찾은 약을 카드로 보여줍니다. 카드의 안전 사실 — 제품·성분·용법용량·경고·출처 — 은 전부 서버가 정부 데이터에서 직접 씁니다.
- **지금 열려 있는 곳.** 근처 약국·병원·응급실을 지도에 보여줍니다. 영업 여부는 공공 스케줄로 서버가 계산하고, 확인할 수 없으면 "모른다"고 정직하게 표시합니다 — 아픈 사람을 열려 있는 약국 앞에서 돌려세우지 않기 위해서입니다.
- **안전이 기본값.** 응급 신호는 AI보다 먼저 코드가 잡아 119 안내로 답합니다. 알레르기를 선언하면 AI가 고른 약 제안은 통째로 폐기됩니다. 모든 응답에 "진단이 아닙니다" 면책이 붙고 화면에 항상 보입니다.

## 왜 믿을 수 있나 — "verified"의 의미

이 서비스는 AI가 아는 것을 그대로 전달하지 않습니다. AI의 역할은 **증상에서 어떤 성분을 찾아볼지 정하는 것까지**이고, 사용자가 보는 사실은 전부 공공 API에서 옵니다.

```
브라우저 ──▶ Spring 프록시 ──▶ LLM ("이 증상엔 어떤 성분을 찾아볼까?")
                │
                └──▶ 공공 API 8종 (식약처·심평원·국립중앙의료원)
                     └──▶ 서버가 이 기록으로 약 카드를 직접 씁니다
```

AI의 제안은 **질의이지 사실이 아닙니다** — 정부 API가 돌려주지 않은 약은 화면에 오르지 못합니다. 상담 내용은 브라우저 탭 안(sessionStorage)에만 있고 서버에 저장되지 않습니다. 이 원칙들의 전문과 이유는 [AGENTS.md §2](AGENTS.md#2-invariants)에 있습니다.

## 기술

Spring Boot 3.5 · Java 21 | React 19 · TypeScript · Vite · Tailwind | MariaDB · Redis · 네이버맵 — 공공 API 8종 연동.

직접 실행해 보려면 → **[docs/개발.md](docs/개발.md)** (빠른 시작 · `.env` · 오프라인 fixture 모드)

## 문서 지도

| 궁금한 것 | 여기 |
|---|---|
| 처음 온 팀원 | [docs/시작하기.md](docs/시작하기.md) |
| 개발 환경과 빠른 시작 | [docs/개발.md](docs/개발.md) |
| 규칙과 그 이유 (영어) | [AGENTS.md](AGENTS.md) |
| 설계 결정과 검증된 외부 제약 | [docs/specs/001-foundation/spec.md](docs/specs/001-foundation/spec.md) |
| 보안 검토 기록 | [docs/security/README.md](docs/security/README.md) |

## 제출 산출물

| 산출물 | 위치 |
|---|---|
| ERD | [`docs/deliverables/ERD.md`](docs/deliverables/ERD.md) — **실제 DB에서 생성** |
| 테이블 명세서 | [`docs/deliverables/테이블_명세서.md`](docs/deliverables/테이블_명세서.md) |
| WBS | [`docs/specs/001-foundation/tasks.md`](docs/specs/001-foundation/tasks.md) |
| API 명세서 | [`docs/deliverables/API_명세서.md`](docs/deliverables/API_명세서.md) — **실행 중인 서버에서 검증** (`./bin/verify-api-doc.sh`) |
| 요구사항 명세서 · 수행계획서 | `docs/deliverables/*.docx` |

## 팀

| 이름 | 레인 |
|---|---|
| 윤서진 | 리드 · BE-1 (챗·RAG) · FE-1 |
| 임수혁 | BE-2 (시설 검색) |
| 박주형 | FE-2 (지도·저장소) |
| 최정민 | PM · UX · QA |

<!-- TODO(PM/QA · DEV-610 #26): 팀원별 "누가 무엇을 만들었나" — 발표 스토리와 같은 손으로 -->

## 이 서비스가 하지 않는 것

**진단하지 않습니다.** 정보를 제공하고 전문가 상담을 권합니다. 이 제약은 시스템 프롬프트에 박혀 있고, 클라이언트가 보낸 `system` 메시지는 프록시가 버리며, 응답에는 항상 면책 문구가 붙습니다. 왜 이렇게 했는지는 [spec §7](docs/specs/001-foundation/spec.md)에 적혀 있습니다.
