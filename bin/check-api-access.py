#!/usr/bin/env python3
"""Ask data.go.kr which of our APIs the service key is actually allowed to call.

Run it whenever an 활용신청 might have been approved, or when a call starts failing
and you cannot tell whose fault it is:

    ./bin/check-api-access.py

Reads DATA_GO_KR_SERVICE_KEY from .env. Prints no key material.

How it can tell the difference
-----------------------------
The gateway answers before the API does, and it answers differently:

    401  it does not recognise the key
    403  it recognises the key but this SERVICE is not approved for it
    404  no such OPERATION — see the warning below
    500  "Unexpected errors" — routed nowhere. Returns 500 for a WRONG key too,
         so it happens before authentication and tells you nothing about approval.
    200  approved, and the API answered

So we send every endpoint twice, once with the real key and once with nonsense.
`real=403, bogus=401` is the signature of "waiting for 활용신청 approval" — the key
is fine, the paperwork is not. `real=500, bogus=500` means we have the path wrong
and no amount of approval will help.

Approval is granted per SERVICE, not per agency: 국립중앙의료원's pharmacy service
answers 200 while its hospital service answered 403 on the same key for weeks.

A 404 is about the operation, not the service
---------------------------------------------
This one cost us. `MadmDtlInfoService2.8/getDtlInfo` answers 404, so we concluded
the 2.8 service did not exist and "corrected" the config to 2.7 — which does exist,
and was never approved, and answered 403, which we then read as more evidence.

The real endpoint is `MadmDtlInfoService2.8/getDtlInfo2.8`. The version suffix is on
the SERVICE and on the OPERATION, and we had only ever tried the unsuffixed operation
under 2.8.

So: a 404 tells you that one operation name is wrong. It never tells you the service is
absent. Before declaring a path dead, vary the operation name — including the version
suffix — and only then believe it.
"""

import pathlib
import sys
import urllib.error
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
BOGUS_KEY = "THIS_KEY_IS_NOT_REGISTERED"
USER_AGENT = "mermAid/0.1 (+https://github.com/SJY051/mermaid)"

# (label, url, params). Params only need to be enough to reach the gateway.
ENDPOINTS = [
    (
        "약국 좌표 조회",
        "B552657/ErmctInsttInfoInqireService/getParmacyLcinfoInqire",
        {"pageNo": 1, "numOfRows": 1, "_type": "json"},
    ),
    (
        "의약품 허가정보",
        "1471000/DrugPrdtPrmsnInfoService07/getDrugPrdtPrmsnInq07",
        {"pageNo": 1, "numOfRows": 1, "type": "json"},
    ),
    (
        "e약은요",
        "1471000/DrbEasyDrugInfoService/getDrbEasyDrugList",
        {"pageNo": 1, "numOfRows": 1, "type": "json"},
    ),
    (
        "DUR 병용금기",
        "1471000/DURPrdlstInfoService03/getUsjntTabooInfoList03",
        {"pageNo": 1, "numOfRows": 1, "type": "json"},
    ),
    (
        "병원정보서비스 (목록·ykiho 발급)",
        "B551182/hospInfoServicev2/getHospBasisList",
        {"pageNo": 1, "numOfRows": 1, "_type": "json"},
    ),
    (
        "의료기관별상세정보 (진료시간)",
        "B551182/MadmDtlInfoService2.8/getDtlInfo2.8",
        {"ykiho": "x", "_type": "json"},
    ),
    (
        "NMC 병·의원 목록",
        "B552657/HsptlAsembySearchService/getHsptlMdcncListInfoInqire",
        {"pageNo": 1, "numOfRows": 1, "_type": "json"},
    ),
    (
        "NMC 응급실 좌표 조회",
        "B552657/ErmctInfoInqireService/getEgytLcinfoInqire",
        {
            "WGS84_LON": "126.9779",
            "WGS84_LAT": "37.5663",
            "pageNo": 1,
            "numOfRows": 1,
            "_type": "json",
        },
    ),
]


def service_key() -> str:
    for line in (ROOT / ".env").read_text().splitlines():
        if line.startswith("DATA_GO_KR_SERVICE_KEY="):
            key = line.split("=", 1)[1].strip()
            if key:
                return key
    sys.exit("DATA_GO_KR_SERVICE_KEY is missing or empty in .env")


def status(path: str, key: str, params: dict) -> int | None:
    query = "serviceKey=" + urllib.parse.quote(key, safe="")
    for name, value in params.items():
        query += f"&{name}=" + urllib.parse.quote(str(value), safe="")
    request = urllib.request.Request(
        f"https://apis.data.go.kr/{path}?{query}", headers={"User-Agent": USER_AGENT}
    )
    try:
        with urllib.request.urlopen(request, timeout=25) as response:
            return response.status
    except urllib.error.HTTPError as e:
        return e.code
    except Exception:  # noqa: BLE001 — a network failure is a verdict of its own
        return None


def verdict(real: int | None, bogus: int | None) -> tuple[str, str]:
    if real == 200:
        return "OK", "사용 가능"
    if real == 403 and bogus == 401:
        return "WAIT", "활용신청 승인 대기 (키는 유효)"
    if real == 404:
        return (
            "PATH",
            "이 오퍼레이션 이름이 없음 — 서비스가 없다는 뜻이 아님 (버전 접미사 확인)",
        )
    if real == 500 and bogus == 500:
        return "PATH", "존재하지 않는 서비스 경로 (인증 이전에 실패)"
    if real is None:
        return "NET", "네트워크 오류"
    return "??", f"real={real} bogus={bogus}"


def main() -> int:
    key = service_key()
    print(f"{'서비스':34} {'우리 키':>7} {'틀린 키':>7}  판정")
    print("-" * 78)
    blocked = 0
    for label, path, params in ENDPOINTS:
        real = status(path, key, params)
        bogus = status(path, BOGUS_KEY, params)
        tag, message = verdict(real, bogus)
        if tag != "OK":
            blocked += 1
        print(f"{label:34} {str(real):>7} {str(bogus):>7}  [{tag}] {message}")

    print()
    if blocked:
        print(
            f"{blocked}개가 아직 막혀 있습니다. WAIT 항목은 data.go.kr 마이페이지 > 활용신청 현황에서 확인하세요."
        )
        print(
            "병원 검색에는 병원정보서비스와 의료기관별상세정보서비스가 **둘 다** 필요합니다:"
        )
        print(
            "  상세정보서비스에는 검색 오퍼레이션이 없고, ykiho는 병원정보서비스만 발급합니다."
        )
    else:
        print("전부 사용 가능합니다.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
