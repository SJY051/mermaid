---
title: Codex Cloud 환경 설정
status: verified
created: 2026-07-11
owner: 윤서진
tags: [ci, review, ops]
---

# Codex Cloud 환경 설정

Codex 리뷰어에게 **우리 코드를 실제로 돌려볼 컨테이너**를 주는 설정입니다. 읽기만 하던 리뷰어가 테스트를 실행할 수 있게 됩니다.

설정은 저장소가 아니라 [Codex 환경 설정 화면](https://chatgpt.com/codex/cloud/settings/environments)에 있습니다. 그래서 **여기에 적어둡니다** — 화면에만 있으면 다음 사람이 왜 이렇게 됐는지 알 수 없으니까요. 값을 바꾸면 이 파일도 같이 고치세요.

바꿀 권한은 저장소 소유자(윤서진)에게 있습니다.

---

## 붙여넣을 값

### 환경 변수 (Environment variables)

| 이름 | 값 | 왜 |
|---|---|---|
| `CODEX_ENV_JAVA_VERSION` | `21` | 백엔드가 Java 21입니다. 기본 이미지는 `25, 24, 23, 22, 21, 17, 11`을 지원합니다 |
| `CODEX_ENV_NODE_VERSION` | `22` | CI와 같은 버전. 이미지는 `18, 20, 22`를 지원합니다 |

### 시크릿 (Secrets)

**하나도 넣지 마세요.** 두 가지 이유가 각각 독립적으로 충분합니다.

1. **필요 없습니다.** 백엔드 테스트는 `test` 프로파일에서 인메모리 H2를 쓰고 Redis도 공공 API도 부르지 않습니다. 프론트 테스트는 jsdom이고 `VITE_` 키를 스텁합니다. `.env` 없는 깨끗한 클론에서 275 + 47개가 전부 통과하는 것을 확인했습니다.
2. **어차피 사라집니다.** Codex 시크릿은 **setup 단계에만** 존재하고 에이전트 단계 시작 전에 제거됩니다. 에이전트가 쓰게 하려면 일반 환경 변수로 넣어야 하는데, 그건 **public 저장소에서 유출 경로**입니다(§2-7, §2-8).

### 에이전트 인터넷 접근 (Agent internet access)

**Off** (기본값). 켜지 마세요.

에이전트 단계는 인터넷이 없어도 테스트를 돌릴 수 있습니다 — setup에서 의존성을 전부 받아두기 때문입니다. `--offline --rerun-tasks`로 강제 재실행해도 275개가 통과하는 것을 확인했습니다.

켜면 얻는 것은 없고 잃는 것은 셋입니다: 신뢰할 수 없는 웹 콘텐츠로부터의 **프롬프트 인젝션**, **코드·시크릿 유출**, 그리고 취약하거나 라이선스가 얽힌 의존성의 유입. 우리 저장소는 public이고, 리뷰 대상은 **아직 리뷰되지 않은 코드**입니다.

### Setup script

setup 단계에는 인터넷이 있습니다. 여기서 받아두지 않은 것은 에이전트가 쓸 수 없습니다.

```bash
set -euo pipefail

# packageManager 필드(pnpm@11.9.0)를 corepack이 해석합니다. 이미 pnpm이 있으면 조용히 넘어갑니다.
corepack enable >/dev/null 2>&1 || true

# node_modules + pnpm store. `prepare`가 pre-commit 시크릿 가드도 걸어줍니다.
(cd frontend && pnpm install --frozen-lockfile)

# Gradle wrapper 배포본과 모든 의존성을 내려받고, 그 자리에서 검증까지 합니다.
# 여기서 실패하면 환경이 잘못된 것이지 PR이 잘못된 게 아닙니다.
(cd backend && ./gradlew test --no-daemon)
```

### Maintenance script

캐시된 컨테이너를 새 커밋으로 이어 쓸 때 돕니다. setup과 같은 일을 하면 됩니다.

```bash
set -euo pipefail
(cd frontend && pnpm install --frozen-lockfile)
(cd backend && ./gradlew test --no-daemon)
```

---

## 이 설정에서 리뷰어가 할 수 있는 것

[`AGENTS.md` §4](../AGENTS.md#4-definition-of-done)의 "완료"를 **말이 아니라 실행으로** 확인할 수 있습니다.

```bash
cd backend  && ./gradlew test     # 275
cd frontend && pnpm test          # 47
cd frontend && pnpm build
```

할 수 없는 것도 분명합니다. **브라우저를 띄울 수 없고**(§4가 요구하는 그 확인), **공공 API를 부를 수 없으며**(키가 없고 인터넷도 없음), **`docker compose`를 쓸 수 없습니다**(컨테이너 안에 Docker가 있다는 근거를 찾지 못했습니다). 그 셋은 여전히 사람 몫입니다.

---

## 확인한 것 (2026-07-11)

컨테이너 조건 — `.env` 없음, DB·Redis 없음, 갓 클론 — 을 로컬에서 재현해 실행했습니다.

- `.env` 없이 `./gradlew test --no-daemon` → **BUILD SUCCESSFUL**, 275개
- `pnpm install --frozen-lockfile` → 성공, `prepare`가 훅까지 설치
- `VITE_` 키 없이 `pnpm test` → **47개 통과**, `pnpm build` → 성공
- `./gradlew test --no-daemon --offline --rerun-tasks` → **BUILD SUCCESSFUL** (에이전트 단계의 진짜 조건)

## 아직 모르는 것

- **maintenance 스크립트에 인터넷이 있는지** 문서에서 확인하지 못했습니다. 없다면, 의존성을 새로 추가하는 PR에서 캐시된 컨테이너의 `pnpm install`이 실패할 수 있습니다. 그런 증상을 보면 setup/maintenance 스크립트를 한 글자라도 고쳐 캐시를 무효화하세요 — 문서상 스크립트·환경변수·시크릿을 바꾸면 캐시가 자동으로 버려집니다.
- **컨테이너 캐시는 최대 12시간** 유지됩니다.
- 기본 이미지가 Temurin JDK인지 다른 배포판인지 문서에 없습니다. 우리는 벤더에 의존하지 않으므로 문제되지 않습니다.

## 참고

- [Cloud environments](https://learn.chatgpt.com/docs/environments/cloud-environment)
- [Agent internet access](https://learn.chatgpt.com/docs/cloud/internet-access.md)
- [codex-universal 이미지](https://github.com/openai/codex-universal)
- 리뷰 기준 자체는 [`AGENTS.md`의 `## Review guidelines`](../AGENTS.md#review-guidelines)에 있습니다.
