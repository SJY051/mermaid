# 공공 API 실제 응답 샘플

**2026-07-10에 실제 API를 호출해서 받은 응답입니다.** 추측이나 문서 예시가 아닙니다.

이것으로 파서를 개발하세요. **약국 API는 개발계정 하루 1,000회**뿐이라, 디버깅하며 호출하면 그날 개발이 끝납니다.

| 파일 | 출처 | 무엇을 보여주나 |
|---|---|---|
| `pharmacy.json` | `ErmctInsttInfoInqireService/getParmacyLcinfoInqire` | 좌표 조회. **주간 시간표가 없습니다.** `startTime`/`endTime`, `distance`(km), `latitude`/`longitude` |
| `pharmacy_basis.json` | `…/getParmacyBassInfoInqire` | HPID 단건. **여기에 `dutyTime1s`~`dutyTime6c`가 있습니다.** `wgs84Lat`/`wgs84Lon` |
| `easydrug.json` | `DrbEasyDrugInfoService/getDrbEasyDrugList` | 안내문(`*Qesitm`). **성분 필드가 없습니다.** |
| `permission.json` | `DrugPrdtPrmsnInfoService07/getDrugPrdtPrmsnInq07` | 목록. `ITEM_INGR_NAME`이 **영문** 성분명 |
| `permission_detail.json` | `…/getDrugPrdtPrmsnDtlInq06` | 상세. `MAIN_INGR_ENG`, `MAIN_ITEM_INGR`(`[M######]…`) |
| `dur_usjnt.json` | `DURPrdlstInfoService03/getUsjntTabooInfoList03` | 병용금기. `MIXTURE_*` 블록, `PROHBT_CONTENT` |
| `dur_age.json` | `…/getSpcifyAgrdeTabooInfoList03` | 연령금기. **나이 필드가 없습니다.** `PROHBT_CONTENT` 한국어 자유 텍스트 |

## 실물을 열어보고 알게 된 것 (조사 문서와 달랐던 것)

**1. 응답 봉투가 두 종류입니다.**
- 식약처(`1471000/…`): 최상위가 `{"header":…, "body":…}`
- 약국·심평원(`B5…`): `{"response":{"header":…,"body":…}}`

`response.header`를 가정하는 파서는 식약처에서 통째로 깨집니다.

**2. 같은 응답 안에서 타입이 섞입니다.**
```json
"dutyTime1s": "0900",   // 문자열
"dutyTime1c": 1900      // 정수
```
`startTime`/`endTime`도 그렇습니다. Java DTO를 `String`으로 잡으면 Jackson이 터집니다. `JsonNode.asText()`로 읽으세요.

**3. `distance`는 km입니다.** `0.14` = 140m. Haversine 계산값(미터)과 단위가 다릅니다.

**4. 없는 요일은 필드 자체가 없습니다.** 이 약국은 일요일·공휴일에 쉬어서 `dutyTime7*`, `dutyTime8*`이 아예 없습니다. `WeeklyHours`가 없는 인덱스를 CLOSED로 처리하는 건 그래서 맞습니다.

**5. 성분 검색은 영문이고 대소문자를 가립니다.**
- `item_ingr_name=Acetaminophen` → 1,357건
- `item_ingr_name=acetaminophen` → **0건**
- `item_ingr_name=아세트아미노펜` → **0건**

**6. 목록 조회에 `item_seq` 파라미터가 없습니다.** 넣어도 조용히 무시하고 전체 43,064건을 반환합니다. `ITEM_SEQ`로 단건을 잡으려면 상세 조회(`getDrugPrdtPrmsnDtlInq06` — **06**입니다. `…Inq07`은 404)를 쓰세요.

**7. `ITEM_SEQ`가 마스터 조인 키입니다.** e약은요의 `itemSeq`, 허가정보의 `ITEM_SEQ`, DUR의 `ITEM_SEQ`가 같은 값(`202005623`)입니다. 실물로 확인했습니다.

**8. DUR `INGR_CODE`(`D000762`)는 허가정보의 `[M######]`와 조인되지 않습니다.** 성분 레벨로 엮으려면 DUR의 `MAIN_INGR`(역시 `[M######]`)을 쓰세요.

## 재수집

`docs/specs/001-foundation/spec.md` §3을 먼저 읽고, 필요할 때만 다시 부르세요.
개인정보는 없습니다 — 전부 공개된 약국·의약품 정보입니다.
