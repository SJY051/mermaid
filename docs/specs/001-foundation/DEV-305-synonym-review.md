---
title: DEV-305 — 성분 동의어 사전 검토 시트
status: awaiting-review
created: 2026-07-10
owner: PM/QA (서명 필요)
prepared-by: 한결 (조사만. 서명하지 않음)
tags: [safety, allergy, review, DEV-305, AR-02]
---

# DEV-305 — 성분 동의어 사전 검토 시트

`backend/src/main/resources/ingredients/synonyms.tsv`의 검토자 칸이 전부 `TODO`입니다.
이 문서는 **검토를 조사가 아니라 확인으로 만들기 위한 것**입니다. 아래 표는 전부
식약처 허가정보(`DrugPrdtPrmsnInfoService07/getDrugPrdtPrmsnInq07`)를 **2026-07-10에 실제로 호출해**
얻은 값입니다. 추측이나 LLM 지식은 한 줄도 들어 있지 않습니다.

> **서명은 사람이 합니다.** 이 문서를 준비한 에이전트는 검토자 칸을 채우지 않습니다.
> 그 칸의 유일한 의미가 "사람이 확인했다"이므로, 에이전트가 채우면 그 칸은 그 순간부터 아무 뜻도 없어집니다.
> 스펙 §7-1 AR-02.

## 왜 이 검토가 필요한가

`synonyms.tsv`의 행은 **알레르기 차단을 만들어 냅니다.** 그리고 **없는 행은 아무 소리 없이 통과시킵니다.**
행을 빼는 쪽이 위험한 방향입니다 — `paracetamol` 행이 없으면 파라세타몰 알레르기가 `Acetaminophen`에
아예 매칭되지 않습니다. 그래서 이 시트가 찾는 것은 "틀린 행"이 아니라 **"없는 행"** 입니다.

## 판정 등급 (코드가 실제로 하는 일)

`IngredientNormalizer.compare()` 기준입니다.

| 등급 | 뜻 | 사용자에게 |
|---|---|---|
| `BLOCK(exact)` | 정규화 후 완전 일치 | 그 제품이 후보에서 빠진다 |
| `BLOCK(synonym)` | 사전의 행을 타고 일치 | 그 제품이 후보에서 빠진다 |
| `WARN-only` | 단어 단위 부분 포함 | **제품이 제시된다.** 경고만 붙는다 |
| `INVISIBLE` | 어느 쪽도 아님 | **제품이 제시된다. 경고도 없다** |

`INVISIBLE`이 이 문서의 목적입니다.

## 재현 방법

```bash
set -a; . .env; set +a          # DATA_GO_KR_SERVICE_KEY (디코딩 키)
curl -sG "https://apis.data.go.kr/1471000/DrugPrdtPrmsnInfoService07/getDrugPrdtPrmsnInq07" \
  --data-urlencode "serviceKey=$DATA_GO_KR_SERVICE_KEY" \
  --data-urlencode "item_ingr_name=Aspirin" \
  --data-urlencode "type=json" --data-urlencode "numOfRows=1"
```

`item_ingr_name`은 **대소문자를 구분하는 부분일치**입니다. 그래서 `buprofen`으로 찾으면
`Ibuprofen`과 `Dexibuprofen`이 함께 잡히고, `Ibuprofen`으로 찾으면 후자를 놓칩니다.
전체 스윕은 21회 호출, 페이지 잘림 없음(`truncated=false`).

---

## ① 즉시 확인 가능한 결함 — 아스피린의 표준명이 거꾸로 있습니다

**허가정보는 `Aspirin`을 씁니다. `Acetylsalicylic Acid`라는 성분명은 존재하지 않습니다.**

| 검색어 | `totalCount` |
|---|---|
| `Aspirin` | **119** |
| `Acetylsalicylic Acid` | **0** |
| `Acetylsalicylic` | 0 |
| `ASA` | 0 |

그런데 현재 사전은 반대로 매핑합니다.

```
asa      acetylsalicylic acid
aspirin  acetylsalicylic acid
```

**결과 — 실제 버그.** `IngredientNormalizer.toSearchTerm("Aspirin")`은 `"Acetylsalicylic Acid"`를
내놓고(저장소 자체 테스트 `IngredientNormalizerTest#resolvesSynonym`이 그렇게 단언합니다),
그 문자열은 허가정보에서 **0건**을 반환합니다. 즉:

- `GET /api/v1/drugs?ingredient=Aspirin` → **빈 목록**
- RAG 검색이 아스피린 제품을 **영원히 못 가져옵니다**

**제안 (검토자 판단):** 표준명을 허가정보가 쓰는 이름으로 뒤집습니다.

```
acetylsalicylic acid   aspirin
asa                    aspirin
```

이건 임상 판단이 아니라 **표기법 사실**입니다. 차단 동작은 그대로이고(양쪽 다 `aspirin`으로 모임),
검색이 고쳐지며, 아래 ②의 `Aspirin Enteric *`가 `INVISIBLE`에서 최소 `WARN-only`로 올라갑니다.

- [ ] 승인 — 표준명을 `aspirin`으로 뒤집는다  ·  검토자: ____________  일자: ______

---

## ② `INVISIBLE` — 알레르기가 있어도 경고 없이 제시되는 성분 표기

이 표기들은 **현재 아무 경고도 만들지 않습니다.** 각 행에 대해 "차단해야 하는가"를 판정해 주세요.

| 성분 표기 (허가정보 원문) | 등장 | 계열 | 지금 동작 | 검토자 판정 |
|---|---|---|---|---|
| `Dexibuprofen D.C.` | 24 | ibuprofen | INVISIBLE | ☐ 차단 ☐ 경고 ☐ 무관 |
| `Aspirin Enteric Pellets` | 27 | aspirin | INVISIBLE | ☐ 차단 ☐ 경고 ☐ 무관 |
| `Aspirin Enteric Granules` | 2 | aspirin | INVISIBLE | ☐ 차단 ☐ 경고 ☐ 무관 |
| `Aspirin Lysine For Injection 90%` | 1 | aspirin | INVISIBLE | ☐ 차단 ☐ 경고 ☐ 무관 |

**참고 (사실만):**
- `Dexibuprofen D.C.`는 `Dexibuprofen`(=이부프로펜의 S-거울상, 이미 차단됨)과 별도 문자열입니다.
  `D.C.`는 정제 제조 방식(direct compression) 표기로 보입니다. `.`이 정규화에서 제거되지 않아 별개 키가 됩니다.
- `Enteric Pellets` / `Enteric Granules`는 장용(腸溶) 제형 표기입니다. `Granules`는 이미
  `FORM_QUALIFIERS`에 있으나 `Enteric`·`Pellets`는 없어서 키가 어긋납니다.

---

## ③ `WARN-only` — 경고는 뜨지만 차단되지 않는 성분 표기

같은 성분인데 표기가 길어서 부분일치로 떨어진 것들입니다. **차단이 맞는지 판정해 주세요.**

| 성분 표기 | 등장 | 계열 | 지금 동작 | 검토자 판정 |
|---|---|---|---|---|
| `Microencapsulated Acetaminophen` | 12 | acetaminophen | WARN-only | ☐ 차단 ☐ 경고 유지 |
| `Ibuprofen Piconol` | 22 | ibuprofen | WARN-only | ☐ 차단 ☐ 경고 유지 |
| `Ibuprofen Sodium Dihydrate` | 1 | ibuprofen | WARN-only | ☐ 차단 ☐ 경고 유지 |
| `Ibuprofen Encapsulated` | 1 | ibuprofen | WARN-only | ☐ 차단 ☐ 경고 유지 |

**참고 (사실만):** `Ibuprofen Piconol`은 이부프로펜의 에스터 유도체이고 국내 허가는 외용제입니다.
같은 성분으로 볼지, 다른 물질로 볼지가 이 표에서 유일하게 **화학·임상 판단이 필요한 행**입니다.
나머지 셋은 제형·수화물 표기로 보입니다.

---

## ④ 추가하면 안 되는 행

| 성분 표기 | 등장 | 왜 |
|---|---|---|
| `Salicylic Acid` | 32 | **아세틸살리실산이 아닙니다.** 다른 물질(각질용해제)이고, 프로브 `alicylic`에 우연히 걸린 것입니다. 아스피린 알레르기와의 관계는 **계열 교차반응 문제**이며 우리는 그 판정을 하지 않습니다 (§7-1 **AR-01**). 행을 추가하지 마십시오. |

---

## ⑤ 기존 7행의 근거 확인

| 별칭 | 표준명 | 허가정보 실측 | 상태 |
|---|---|---|---|
| `paracetamol` | `acetaminophen` | `Paracetamol` **0건** · `Acetaminophen` 1,357건 | ✅ 행이 없으면 파라세타몰 알레르기가 매칭 실패 |
| `apap` | `acetaminophen` | 라벨 약어. 허가정보엔 없음 | ☐ 확인 |
| `ibuprofen lysine` | `ibuprofen` | `Ibuprofen Lysine` 1건 실재 | ✅ |
| `ibuprofen arginine` | `ibuprofen` | `Ibuprofen Arginine` 3건 실재 | ✅ |
| `dexibuprofen` | `ibuprofen` | `Dexibuprofen` 123건 실재 | ✅ 근거는 **화학적 동일성**(S-거울상). 계열 교차반응 아님 |
| `asa` | `acetylsalicylic acid` | 양쪽 다 **0건** | ⚠ ①에 따라 표준명 교체 |
| `aspirin` | `acetylsalicylic acid` | `Aspirin` 119건 · 표준명 0건 | ⚠ ①에 따라 표준명 교체 |

---

## 서명

검토자는 위 ①~⑤를 판정한 뒤, `synonyms.tsv`의 3번째 칸에 **실명**을 적습니다.
`TODO`와 공란은 서명이 아니며, 코드가 부팅할 때마다 미서명 행을 WARN으로 출력합니다
(`IngredientNormalizer.warnIfUnreviewed`).

| 검토자 (실명) | 자격 | 일자 | 서명한 행 |
|---|---|---|---|
| | | | |

**서명 전까지 행을 추가하지 않습니다.**
