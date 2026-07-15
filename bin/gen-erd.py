#!/usr/bin/env python3
"""Regenerate docs/deliverables/ERD.md and 테이블_명세서.md from the LIVE database.

Never hand-edit those two files. They are generated from INFORMATION_SCHEMA after
Flyway has migrated a real MariaDB, which is the same schema JPA's `ddl-auto: validate`
checks the entities against. Entities, migrations and diagram therefore cannot drift.

Usage:

    docker compose up -d
    cd backend && ./gradlew bootRun     # applies migrations, validates entities
    # ...once it says "Started BackendApplication", stop it and:
    ./bin/gen-erd.py

Reads MARIADB_ROOT_PASSWORD from .env. Prints nothing sensitive.
"""

import pathlib
import subprocess
import sys
from collections import defaultdict

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT_DIR = ROOT / "docs" / "deliverables"
CONTAINER = "mermaid-mariadb"
DB = "mermaid"

TABLE_PURPOSE = {
    "user_profile": "익명 프로필. 계정이 아니라 브라우저가 만든 UUID (spec §2-5)",
    "allergy_ingredient": "기피 성분. 동의(opt-in)한 경우에만 저장됨 (FR-04)",
    "favorite_facility": "즐겨찾기한 약국·병원. CRUD의 C/R/U/D 시연 대상",
}
SKIP = {"flyway_schema_history"}


def root_password() -> str:
    for line in (ROOT / ".env").read_text().splitlines():
        if line.startswith("MARIADB_ROOT_PASSWORD="):
            return line.split("=", 1)[1].strip()
    sys.exit("MARIADB_ROOT_PASSWORD not found in .env")


PW = root_password()


def query(sql: str) -> list[list[str]]:
    proc = subprocess.run(
        [
            "docker",
            "exec",
            CONTAINER,
            "mariadb",
            "-uroot",
            "-p" + PW,
            "-N",
            "-B",
            "-e",
            sql,
        ],
        capture_output=True,
        text=True,
    )
    if proc.returncode != 0:
        sys.exit(f"query failed (is `docker compose up -d` running?):\n{proc.stderr}")
    rows = [
        line.split("\t") for line in proc.stdout.strip().splitlines() if line.strip()
    ]
    width = max((len(r) for r in rows), default=0)
    return [r + [""] * (width - len(r)) for r in rows]


columns = query(f"""
    SELECT TABLE_NAME, ORDINAL_POSITION, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE,
           COALESCE(COLUMN_DEFAULT,''), COLUMN_KEY, EXTRA, COALESCE(COLUMN_COMMENT,'')
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA='{DB}' ORDER BY TABLE_NAME, ORDINAL_POSITION;
""")
foreign_keys = query(f"""
    SELECT TABLE_NAME, COLUMN_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME, CONSTRAINT_NAME
    FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE
    WHERE TABLE_SCHEMA='{DB}' AND REFERENCED_TABLE_NAME IS NOT NULL;
""")
indexes = query(f"""
    SELECT TABLE_NAME, INDEX_NAME, NON_UNIQUE, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA='{DB}'
    GROUP BY TABLE_NAME, INDEX_NAME, NON_UNIQUE ORDER BY TABLE_NAME, INDEX_NAME;
""")

if not columns:
    sys.exit(
        f"schema `{DB}` is empty — run `./gradlew bootRun` once so Flyway migrates it"
    )

by_table = defaultdict(list)
for row in columns:
    if row[0] not in SKIP:
        by_table[row[0]].append(row)

fks = defaultdict(list)
for row in foreign_keys:
    if row[0] not in SKIP:
        fks[row[0]].append(row)

idxs = defaultdict(list)
for row in indexes:
    if row[0] not in SKIP:
        idxs[row[0]].append(row)


def label(comment: str) -> str:
    # Only the quote character breaks mermaid's label syntax; keep the rest, including "e.g.".
    text = comment.replace('"', "'")
    return text[:45].rstrip() + "…" if len(text) > 46 else text


erd = ["erDiagram"]
for table, rows in fks.items():
    for fk in rows:
        erd.append(f"    {fk[2].upper()} ||--o{{ {table.upper()} : has")
erd.append("")
for table, rows in sorted(by_table.items()):
    erd.append(f"    {table.upper()} {{")
    for _, _, name, ctype, _, _, key, _, comment in rows:
        marker = (
            "PK"
            if key == "PRI"
            else "FK"
            if any(f[1] == name for f in fks[table])
            else "UK"
            if key == "UNI"
            else ""
        )
        erd.append(
            f'        {ctype.split("(")[0]} {name} {marker} "{label(comment)}"'.rstrip()
        )
    erd.append("    }")

(OUT_DIR / "ERD.md").write_text(f"""---
title: mermAid ERD
status: generated
created: 2026-07-10
source: MariaDB 11.4 INFORMATION_SCHEMA, after Flyway V1–V3
---

# ERD

> **이 문서는 실제 데이터베이스에서 생성했습니다.** 손으로 그린 것이 아닙니다.
> `./bin/gen-erd.py`로 재생성하세요. 같은 스키마에 대해 JPA `ddl-auto: validate`가 통과하므로
> **엔티티·마이그레이션·이 그림 셋이 일치**합니다.

## 무엇이 여기에 없는가 (그리고 왜)

| 데이터 | 어디에 | 왜 |
|---|---|---|
| **의료 상담 대화** | 브라우저 `sessionStorage` | 서버에 남기지 않겠다는 결정입니다 (spec §2-4, §2-16). 탭을 닫으면 사라집니다 |
| 즐겨찾기 스냅샷·설정 | 브라우저 `localStorage` | 표시용. 상세를 열 때 서버에서 다시 조회합니다 |
| 공공 API 응답 캐시 | Redis | 도메인 데이터가 아니라 호출 한도 대응입니다 |
| 의료기관·의약품 | (저장 안 함) | 우리가 소유하지 않는 참조 데이터입니다. 매 요청 공공 API에서 조회 |

**알레르기 성분은 사용자가 명시적으로 동의(`remember_allergies`)한 경우에만 이 DB에 들어옵니다.**
기본값은 꺼짐이고, 동의를 끄면 저장된 행이 삭제됩니다.

## 다이어그램

```mermaid
{chr(10).join(erd)}
```

## 관계

| 관계 | 카디널리티 | 삭제 규칙 |
|---|---|---|
| `user_profile` → `allergy_ingredient` | 1 : N | `ON DELETE CASCADE` |
| `user_profile` → `favorite_facility` | 1 : N | `ON DELETE CASCADE` |

프로필이 사라지면 그에 딸린 성분과 즐겨찾기도 함께 사라집니다. 익명 프로필이므로 "탈퇴"는
프로필 행 하나를 지우는 일이고, 그것으로 그 사람의 모든 서버 데이터가 없어져야 합니다.

## 재생성

```bash
docker compose up -d
cd backend && ./gradlew bootRun    # Flyway가 마이그레이션을 적용하고 JPA가 검증합니다
./bin/gen-erd.py                   # 이 문서와 테이블 명세서를 다시 씁니다
```

이미 적용된 마이그레이션을 수정하면 Flyway 체크섬이 어긋납니다 (`docker compose down -v`로 초기화).
""")

spec = []
for table, rows in sorted(by_table.items()):
    spec.append(f"\n## `{table}`\n")
    spec.append(f"> {TABLE_PURPOSE.get(table, '')}\n")
    spec.append("| # | 컬럼 | 타입 | NULL | 기본값 | 키 | 기타 | 설명 |")
    spec.append("|---:|---|---|:---:|---|:---:|---|---|")
    for _, pos, name, ctype, nullable, default, key, extra, comment in rows:
        is_fk = any(f[1] == name for f in fks[table])
        marker = (
            "PK" if key == "PRI" else "UK" if key == "UNI" else "FK" if is_fk else "-"
        )
        # MariaDB reports an absent default as the literal string "NULL".
        shown_default = f"`{default}`" if default and default != "NULL" else "-"
        spec.append(
            f"| {pos} | `{name}` | `{ctype}` | {'Y' if nullable == 'YES' else 'N'} | "
            f"{shown_default} | {marker} | {extra or '-'} | {comment or '-'} |"
        )
    if fks[table]:
        spec.append("\n**외래키**\n")
        spec.append("| 제약조건 | 컬럼 | 참조 |")
        spec.append("|---|---|---|")
        for fk in fks[table]:
            spec.append(
                f"| `{fk[4]}` | `{fk[1]}` | `{fk[2]}.{fk[3]}` ON DELETE CASCADE |"
            )
    if idxs[table]:
        spec.append("\n**인덱스**\n")
        spec.append("| 이름 | 컬럼 | 유일 |")
        spec.append("|---|---|---|")
        for ix in idxs[table]:
            spec.append(f"| `{ix[1]}` | `{ix[3]}` | {'N' if ix[2] == '1' else 'Y'} |")

(OUT_DIR / "테이블_명세서.md").write_text(f"""---
title: mermAid 테이블 명세서
status: generated
created: 2026-07-10
source: MariaDB 11.4 INFORMATION_SCHEMA, after Flyway V1–V3
---

# 테이블 명세서

> **실제 데이터베이스에서 생성했습니다.** `./bin/gen-erd.py`로 재생성하세요.
> `docs/deliverables/ERD.md`와 같은 스키마이며, JPA `ddl-auto: validate`가 통과합니다.

- DBMS: **MariaDB 11.4**, 엔진 `InnoDB`, 문자셋 `utf8mb4`
- 마이그레이션: `backend/src/main/resources/db/migration/`
- 모든 시각 컬럼은 `DATETIME(6)` UTC이며 JPA Auditing이 채웁니다

**의료 상담 대화는 이 스키마에 없습니다.** 브라우저 `sessionStorage`에만 존재합니다 (spec §2-4, §2-16).
{chr(10).join(spec)}

---

## 설계 근거

**`user_profile.remember_allergies`**
알레르기는 기본적으로 세션 입력입니다. 사용자가 명시적으로 켰을 때만 이 DB에 저장되고,
끄면 저장된 행이 즉시 삭제됩니다 (spec §2-5). 이 컬럼이 그 약속을 검증 가능하게 만듭니다.

**`allergy_ingredient.normalized_key` / `confidence`**
"Ibuprofen", "ibuprofen 200mg", "Ibuprofen (Advil)"은 같은 성분입니다. 정규화된 키로 비교하고,
그 정규화가 정확 일치인지 검토된 동의어인지 추측인지를 `confidence`에 남깁니다.
**추측(`PARTIAL`/`UNKNOWN`)은 약을 차단하지 못합니다** — 경고만 냅니다 (spec §2-12).

**`favorite_facility.facility_id`**
`facility:nmc:C1110693` 처럼 공급자 네임스페이스를 포함합니다 (spec §4-3).
약국은 `hpid`, 병원은 `ykiho`이며 서로 다른 기관이 발급하므로 접두사가 필요합니다.
**이름이나 주소를 ID로 쓰지 않습니다.**
""")

print(f"wrote {OUT_DIR / 'ERD.md'}")
print(f"wrote {OUT_DIR / '테이블_명세서.md'}")
