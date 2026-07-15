#!/usr/bin/env bash
# Check that docs/deliverables/API_명세서.md still describes the server we actually built.
#
#   docker compose up -d
#   cd backend && DATA_MODE=fixture ./gradlew bootRun    # in another shell
#   ./bin/verify-api-doc.sh
#
# Every assertion below is a sentence from the document. When one fails, either the
# server changed or the document lied — fix whichever is wrong, never the assertion.
#
# The last two checks are static: they compare the backend's ErrorCode enum against the
# frontend's union type and against the table in the document. A code added to one and
# not the others is an API contract that disagrees with itself.

set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE="http://localhost:8080/api/v1"
DOC="$ROOT/docs/deliverables/API_명세서.md"
DEVICE="apidoc-verify-$$"

pass=0
fail=0

# expect METHOD PATH EXPECTED_STATUS [BODY]
expect() {
  local method=$1 path=$2 want=$3 body=${4:-}
  local got
  if [ -n "$body" ]; then
    got=$(curl -s -o /dev/null -w '%{http_code}' -X "$method" "$BASE$path" \
      -H 'Content-Type: application/json' -d "$body")
  else
    got=$(curl -s -o /dev/null -w '%{http_code}' -X "$method" "$BASE$path")
  fi
  if [ "$got" = "$want" ]; then
    pass=$((pass + 1))
    printf '  ok   %-6s %-46s %s\n' "$method" "$path" "$got"
  else
    fail=$((fail + 1))
    printf '  FAIL %-6s %-46s want %s, got %s\n' "$method" "$path" "$want" "$got"
  fi
}

check() { # check DESCRIPTION CONDITION_EXIT_CODE
  if [ "$2" -eq 0 ]; then
    pass=$((pass + 1)); printf '  ok   %s\n' "$1"
  else
    fail=$((fail + 1)); printf '  FAIL %s\n' "$1"
  fi
}

if ! curl -sf -o /dev/null http://localhost:8080/actuator/health; then
  echo "서버가 http://localhost:8080 에 없습니다. 먼저 띄우세요:"
  echo "  cd backend && DATA_MODE=fixture ./gradlew bootRun"
  exit 2
fi

echo "§2 의료기관"
expect GET "/facilities?lat=37.4979&lng=127.0276&type=pharmacy" 200
expect GET "/facilities?lat=37.4979&lng=127.0276&type=hospital" 200
hospital=$(curl -s "$BASE/facilities?lat=37.5663&lng=126.9779&radius_m=1000&type=hospital")
echo "$hospital" | grep -q '"type":"hospital"'
check "GET /facilities?type=hospital returns hospital facilities" $?
echo "$hospital" | grep -q '"id":"facility:hira:'
check "GET /facilities?type=hospital is not an empty legacy 200 response" $?
expect GET "/facilities/facility:nmc:C1110693"                 200   # 약국 단건 상세
expect GET "/facilities/facility:hira:JDQ4MTg4MSM1MSM"         501   # 병원 단건은 미구현
expect GET "/facilities/facility:nmc:not-an-hpid"              404   # 잘못된 형식 → 상류 호출 0
detail=$(curl -s "$BASE/facilities/facility:nmc:C1110693")
echo "$detail" | grep -q '"type":"pharmacy"'
check "GET /facilities/{id} 약국 단건은 pharmacy 타입" $?
echo "$detail" | grep -q '"distanceMeters":null'
check "GET /facilities/{id} 단건은 distanceMeters=null (기준 좌표 없음)" $?
expect GET "/facilities?lat=999&lng=127.0"                     400
expect GET "/facilities?lng=127.0"                             400   # lat is required
expect GET "/facilities?lat=37.5&lng=127.0&type=clinic"        400   # closed enum

echo
echo "§3 의약품"
expect GET "/drugs?query=%ED%83%80%EC%9D%B4%EB%A0%88%EB%86%80"                200
expect GET "/drugs?ingredient=Ibuprofen&exclude_ingredients=Ibuprofen"        200
expect GET "/drugs?query=%ED%83%80%EC%9D%B4%EB%A0%88%EB%86%80&ingredient=Ibuprofen" 400  # exactly one
expect GET "/drugs"                                                           400  # exactly one
expect GET "/drugs/202005623"                                                 200

echo
echo "§4 프로필 — 동의가 먼저"
expect GET   "/profiles/$DEVICE"            200                                      # created on read
expect PATCH "/profiles/$DEVICE"            200 '{"countryCode":"US"}'
expect POST  "/profiles/$DEVICE/allergies"  400 '{"ingredientNameEn":"Ibuprofen"}'   # no consent yet
expect PATCH "/profiles/$DEVICE/consent"    200 '{"rememberAllergies":true}'
expect POST  "/profiles/$DEVICE/allergies"  201 '{"ingredientNameEn":"Ibuprofen"}'
expect POST  "/profiles/$DEVICE/favorites"  201 '{"facilityId":"facility:nmc:C1110693","facilityType":"pharmacy"}'

# The generated ids are what the update and delete routes are keyed by, so read them back
# rather than guessing. A profile that has just been created has exactly one of each.
profile=$(curl -s "$BASE/profiles/$DEVICE")
allergy_id=$(printf '%s' "$profile" | sed -n 's/.*"allergies":\[{"id":\([0-9]*\).*/\1/p')
favorite_id=$(printf '%s' "$profile" | sed -n 's/.*"favorites":\[{"id":\([0-9]*\).*/\1/p')
check "생성된 allergyId와 favoriteId를 읽어옴 ($allergy_id, $favorite_id)" \
  "$([ -n "$allergy_id" ] && [ -n "$favorite_id" ] && echo 0 || echo 1)"

expect PATCH  "/profiles/$DEVICE/favorites/$favorite_id" 200 '{"alias":"집앞","memo":"24시"}'
expect DELETE "/profiles/$DEVICE/favorites/$favorite_id" 204
expect DELETE "/profiles/$DEVICE/allergies/$allergy_id"  204
expect DELETE "/profiles/$DEVICE/favorites/999999"       404   # someone else's, or gone

# Consent off deletes what was stored — it does not merely hide it (spec §2-5).
curl -s -o /dev/null -X POST "$BASE/profiles/$DEVICE/allergies" \
  -H 'Content-Type: application/json' -d '{"ingredientNameEn":"Aspirin"}'
curl -s -o /dev/null -X PATCH "$BASE/profiles/$DEVICE/consent" \
  -H 'Content-Type: application/json' -d '{"rememberAllergies":false}'
curl -s "$BASE/profiles/$DEVICE" | grep -q '"allergies":\[\]'
check "동의를 끄면 저장된 알레르기가 삭제된다" $?

echo
echo "§0 · §5 경로와 에러 봉투"
expect GET "/health"        404   # the spec claimed this exists; it does not
expect GET "/does-not-exist" 404
curl -sf -o /dev/null http://localhost:8080/actuator/health
check "GET /actuator/health -> 200" $?

curl -s -D- -o /dev/null "$BASE/facilities?lat=999&lng=1" | grep -qi '^x-request-id:'
check "모든 응답에 X-Request-Id 헤더" $?

envelope=$(curl -s "$BASE/facilities?lat=999&lng=1")
for field in code message retryable request_id; do
  echo "$envelope" | grep -q "\"$field\""
  check "에러 봉투에 error.$field" $?
done

echo
echo "§2 · §3 응답의 필드 이름 (문서가 지어낼 수 있는 부분)"
# Status codes said nothing about field names, so the document claimed `lat`/`lng` and uppercase
# enums for a week. Assert the names the document prints.
facility=$(curl -s "$BASE/facilities?lat=37.5663&lng=126.9779&radius_m=1000")
for field in '"latitude"' '"longitude"' '"distanceMeters"' '"statusConfidence"'; do
  echo "$facility" | grep -q "$field"
  check "GET /facilities 응답에 $field" $?
done
echo "$facility" | grep -qE '"lat":|"lng":'
check "응답에 lat/lng 는 없다 (요청 파라미터 이름과 다름)" $([ $? -ne 0 ] && echo 0 || echo 1)
echo "$facility" | grep -qE '"status":"(open|closed|unknown)"'
check "operation.status 는 소문자 wire 값" $?

drug=$(curl -s "$BASE/drugs/202005623")
for field in '"itemSeq"' '"ingredientsEn"' '"prescriptionStatus"' '"allergyCheck"' '"durWarnings"'; do
  echo "$drug" | grep -q "$field"
  check "GET /drugs/{itemSeq} 응답에 $field" $?
done

echo
echo "§5 에러 코드가 세 곳에서 일치하는가"
enum=$(grep -cE '^\s{4}[A-Z_]+\(HttpStatus' "$ROOT/backend/src/main/java/com/mermaid/common/ErrorCode.java")
union=$(grep -cE "^\s*\| '[A-Z_]+'$" "$ROOT/frontend/src/lib/types.ts")
doc=$(grep -cE '^\| `[A-Z_]+` \| [0-9]{3} \|' "$DOC")
printf '  backend enum=%s  frontend union=%s  문서 표=%s\n' "$enum" "$union" "$doc"
[ "$enum" -eq "$union" ] && [ "$enum" -eq "$doc" ]
check "ErrorCode 개수가 backend · frontend · 문서에서 동일" $?

echo
echo "  통과 $pass · 실패 $fail"
[ "$fail" -eq 0 ]
