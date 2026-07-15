# 공공 API 실제 응답 샘플

**대부분 2026-07-10에 실제 API를 호출해서 받은 응답입니다.** 추측이나 문서 예시가 아닙니다. 예외는 표에 날짜로 표시합니다.

이것으로 파서를 개발하세요. **약국 API는 개발계정 하루 1,000회**뿐이라, 디버깅하며 호출하면 그날 개발이 끝납니다.

| 파일 | 출처 | 무엇을 보여주나 |
|---|---|---|
| `pharmacy.json` | `ErmctInsttInfoInqireService/getParmacyLcinfoInqire` | 좌표 조회. **주간 시간표가 없습니다.** `startTime`/`endTime`, `distance`(km), `latitude`/`longitude` |
| `emergency_room.json` | `ErmctInfoInqireService/getEgytLcinfoInqire` | 응급의료기관 좌표 조회. `distance`는 **km**이며, 이것만으로 응급실 운영 여부나 가용 병상을 알 수 없습니다. |
| `holiday_2026.xml` | `SpcdeInfoService/getRestDeInfo` | 2026-07-15 실제 캡처. **XML**, `isHoliday=Y`·`locdate` 사용. 공급자가 `Y`로 답한 노동절·제헌절도 자체 정책으로 제외하지 않는다. |
| `pharmacy_basis.json` | `…/getParmacyBassInfoInqire` | HPID 단건. **여기에 `dutyTime1s`~`dutyTime6c`가 있습니다.** `wgs84Lat`/`wgs84Lon` |
| `easydrug.json` | `DrbEasyDrugInfoService/getDrbEasyDrugList` | 안내문(`*Qesitm`). **성분 필드가 없습니다.** |
| `permission.json` | `DrugPrdtPrmsnInfoService07/getDrugPrdtPrmsnInq07` | 목록. `ITEM_INGR_NAME`이 **영문** 성분명 |
| `permission_detail.json` | `…/getDrugPrdtPrmsnDtlInq06` | 상세. `MAIN_INGR_ENG`, `MAIN_ITEM_INGR`(`[M######]…`) |
| `dur_usjnt.json` | `DURPrdlstInfoService03/getUsjntTabooInfoList03` | 병용금기. `MIXTURE_*` 블록, `PROHBT_CONTENT` |
| `dur_age.json` | `…/getSpcifyAgrdeTabooInfoList03` | 연령금기. **나이 필드가 없습니다.** `PROHBT_CONTENT` 한국어 자유 텍스트 |
| `dur_elderly.json` | `…/getOdsnAtentInfoList03` | 노인주의. 여러 품목 행과 null `PROHBT_CONTENT`를 포함하는 파서용 캡처 |
| `dur_empty.json` | `…/getPwnmTabooInfoList03` | `totalCount: 0` 응답 형태를 보여주는 기존 파서용 캡처 |
| `dur_202005623_{combination,age,pregnancy,elderly}.json` | 위 네 DUR 오퍼레이션 | 2026-07-16 `itemSeq=202005623` 재확인. 네 응답 모두 HTTP 200, `resultCode: 00`, `totalCount: 0`으로 byte-identical |
| `hospital_list.json` | `hospInfoServicev2/getHospBasisList` | 병원 목록. `ykiho`, `yadmNm`, `XPos`/`YPos`(대문자!), `distance`(**미터**), `clCd`(종별코드: 문자열/숫자 혼합), `clCdNm`(표시명). **진료시간이 없습니다** |
| `hospital_detail.json` | `MadmDtlInfoService2.8/`**`getDtlInfo2.8`** | `ykiho` 단건. **여기에 `trmtMonStart`~`trmtSatEnd`, `lunchWeek`, `noTrmtSun`, `emyNgtYn`이 있습니다** |

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

**3. `distance`의 단위가 발급기관마다 다릅니다. 반대로요.**

| 기관 | API | `distance` | 좌표 필드 |
|---|---|---|---|
| 국립중앙의료원 `B552657` | 약국·응급실 | **km** (`0.14` = 140m) | `latitude`/`longitude` |
| 심평원 `B551182` | 병원 목록 | **미터** (`865.68…` = 866m) | `XPos`/`YPos` |

둘 다 Haversine과 대조해 확인했습니다(오차 0.6% 이내). 같은 포털, 같은 "가까운 곳 찾기"인데 단위가 반대입니다.
하나를 보고 다른 하나를 짐작하지 마세요. 병원 쪽 `distance`는 **39자리 문자열**로 옵니다
(`"865.68688945254311272999118456133305877"`). `asDouble()`로 읽으세요.

**4. 없는 요일은 필드 자체가 없습니다.** 이 약국은 일요일·공휴일에 쉬어서 `dutyTime7*`, `dutyTime8*`이 아예 없습니다. `WeeklyHours`가 없는 인덱스를 CLOSED로 처리하는 건 그래서 맞습니다.

**5. 성분 검색은 대소문자를 구분하는 _부분 문자열_ 매칭입니다.** (정확 일치가 아닙니다)
- `item_ingr_name=Ibuprofen` → 282건 (진짜 이부프로펜)
- `item_ingr_name=ibuprofen` → **142건 — 전부 Dex*ibuprofen***. 진짜 이부프로펜 제품은 하나도 없습니다
- `item_ingr_name=Acetaminophen` → 1,357건 / `acetaminophen` → **0건**
- `item_ingr_name=아세트아미노펜` → **0건**

**사용자가 입력한 대소문자를 그대로 넘기면 알레르기 질문에 엉뚱한 약이 나옵니다.** `IngredientNormalizer.toSearchTerm()`을 반드시 거치세요.

**6. 목록 조회에 `item_seq` 파라미터가 없습니다.** 넣어도 조용히 무시하고 전체 43,064건을 반환합니다. `ITEM_SEQ`로 단건을 잡으려면 상세 조회(`getDrugPrdtPrmsnDtlInq06` — **06**입니다. `…Inq07`은 404)를 쓰세요.

**7. `ITEM_SEQ`가 non-empty 의약품 레코드의 마스터 조인 키입니다.** e약은요의 `itemSeq`, 허가정보의 `ITEM_SEQ`, DUR의 `ITEM_SEQ`는 행이 있을 때 같은 품목을 식별합니다. DUR zero-row 응답에는 `ITEM_SEQ`가 없으므로 요청한 `(itemSeq, kind)`가 캡처의 정체성을 보존합니다.

**8. DUR `INGR_CODE`(`D000762`)는 허가정보의 `[M######]`와 조인되지 않습니다.** 성분 레벨로 엮으려면 DUR의 `MAIN_INGR`(역시 `[M######]`)을 쓰세요.

**9. 성분 이름은 제형 수식어를 달고 옵니다.** `Acetaminophen Granules`, `Acetaminophen Micronized`, `Anhydrous Caffeine`. 전부 같은 알레르겐입니다. 벗겨내지 않으면 아세트아미노펜 알레르기 환자에게 `warning`만 뜹니다 — 차단되어야 하는데도.
반면 **염 형태**(`Chlorpheniramine Maleate`)는 벗기지 않습니다. 그건 실제 성분명입니다.

**10. 같은 성분이 두 번 옵니다.** `타이레놀8시간이알서방정`의 `ITEM_INGR_NAME`은 `"Acetaminophen/Acetaminophen"`, `ITEM_INGR_CNT=2`. 이중층 정제라서요.

**11. 모든 약이 e약은요에 있는 건 아닙니다.** 수출용 의약품은 안내문이 없습니다. `Optional.empty()`가 정상이며, `@Cacheable`에 `unless = "#result == null"`이 없으면 Redis가 예외를 던집니다.

**DUR fixture는 반드시 품목과 종류를 함께 식별합니다.** 2026-07-16에 품목기준코드
`202005623`을 병용금기·연령금기·임부금기·노인주의 네 오퍼레이션에서 다시 조회했고,
네 응답은 byte-identical한 정상 HTTP 200 / `resultCode: 00` / `totalCount: 0`이었습니다.
빈 응답 본문에는 `ITEM_SEQ`가 없으므로 파일 이름과 런타임 binding의 `(itemSeq, kind)`가
그 캡처의 요청 정체성을 보존합니다. 기존 `dur_usjnt.json`, `dur_age.json`,
`dur_elderly.json`, `dur_empty.json`은 실응답 파서 증거로 남기되 다른 품목의 결과로
재사용하지 않습니다. 등록되지 않은 `(itemSeq, kind)`는 source unavailable로 닫고,
행이 있는 캡처는 모든 `ITEM_SEQ`가 요청 품목과 일치해야만 사용합니다.

**12. 병원 목록의 `radius`는 선택이 아니라 필수입니다.** 빼면 좌표를 줘도 **전국 79,727건**이 옵니다. 거르는 게 아니라 아예 무시합니다.

**13. 병원 좌표 필드는 대문자 `XPos`/`YPos`입니다.** 약국은 소문자 `latitude`/`longitude`이고, `getParmacyBassInfoInqire`는 또 `wgs84Lat`/`wgs84Lon`입니다. 세 API가 세 가지 이름을 씁니다.
그리고 `XPos`가 **경도**, `YPos`가 **위도**입니다(x=lon, y=lat). 요청 파라미터도 `xPos`=경도, `yPos`=위도입니다. 뒤집으면 조용히 엉뚱한 곳을 검색합니다.

**14. 병원 `postNo`는 문자열입니다** (`"06132"`). 정수로 파싱하면 앞의 0이 사라집니다. `drTotCnt`(의사 수)는 정수, `telno`는 문자열, `XPos`/`YPos`는 실수, `distance`는 문자열 — **한 행 안에서 네 가지 타입**이 섞입니다.

**15. 병원 목록에는 진료시간이 없습니다.** `trmtMonStart`~`trmtSatEnd`, `lunchWeek`, `noTrmtSun`, `emyNgtYn`은 `MadmDtlInfoService2.8/`**`getDtlInfo2.8`**에 있습니다. **`ykiho` 단건이라 N+1입니다** — 병원의 진료시간은 1년에 한 번 바뀌니 하드하게 캐싱하세요.

**16. 버전 접미사가 서비스와 오퍼레이션 둘 다에 붙습니다.** `MadmDtlInfoService2.8/getDtlInfo2.8`.
`…2.8/getDtlInfo`는 **404 `API not found`**입니다 — 그런데 이건 **오퍼레이션 이름이 틀렸다는 뜻이지, 서비스가 없다는 뜻이 아닙니다.**
우리는 이 404를 보고 "2.8은 없다"고 단정해 설정을 `2.7`로 바꿨습니다. `2.7`은 실재하지만 승인되지 않은 구버전이라 403을 냈고, 그 403을 다시 "승인 대기"의 증거로 읽었습니다. **틀린 전제가 그럴듯한 증거를 만들어냅니다.**

**17. 병원 상세도 일요일 필드가 없습니다.** `trmtSunStart`가 아예 없고 `noTrmtSun: "휴진"`만 옵니다. 4번(약국의 `dutyTime7*` 부재)과 같은 패턴입니다. `lunchWeek`은 `"12:30 ~ 13:30"` 자유 텍스트라 파싱해야 합니다. `hospital_detail.json`은 강북삼성병원 한 건의 캡처라, 다른 `ykiho`의 시간표로 재사용하면 안 됩니다 — 일치하지 않으면 hours unknown입니다.

## 재수집

`docs/specs/001-foundation/spec.md` §3을 먼저 읽고, 필요할 때만 다시 부르세요.
개인정보는 없습니다 — 전부 공개된 약국·의약품 정보입니다.
