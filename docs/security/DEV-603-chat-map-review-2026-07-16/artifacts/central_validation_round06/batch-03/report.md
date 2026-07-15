# Central validation batch 03

Target: `654f906e00e81648d1482210b6a9171747dddd75`  
Canonical source: `artifacts/04_reconciliation/deduped_candidates.jsonl`  
Canonical SHA-256: `274fd61156b7ae3ebeadb1c905258fbe001a5f81380891b9d2491b44652477b8`  
Assigned physical lines: 77–113 inclusive (37 rows)

## Outcome

All 37 assigned canonical rows were validated exactly once against immutable target objects.

- Reportable: 22
- Needs review: 2
- Not reportable: 13
- Severity receipts: P0 7, P1 12, P2 5, none 13

The central pass pruned shared-root timeout duplicates, a duplicated pharmacy-fan-out statement, an impact-defeated over-row parser claim, two unreachable code paths, and composite HYBRID duplicates. Unknown provider and production-infrastructure facts remain explicit proof gaps.

## P0 findings

- 94 `R01-CAN-094` — Malformed hospital hours collapse to CLOSED instead of UNKNOWN
- 95 `R01-CAN-095` — Malformed pharmacy hours collapse to CLOSED instead of UNKNOWN
- 98 `R01-CAN-098` — Cached pharmacy facts are restamped as freshly retrieved
- 105 `R01-CAN-105` — Composite outer hybrid fallback is stamped live and current
- 109 `R01-CAN-109` — Hybrid permission fallback binds a fixed fixture product to an arbitrary requested ID
- 110 `R01-CAN-110` — Hybrid EasyDrug fallback attaches a fixed product narrative to an arbitrary drug
- 113 `R01-CAN-113` — Outer DUR fallback warnings are not bound to the requested medicine

The P0s are explicit §2 trust-boundary failures: malformed schedules become `CLOSED` rather than `UNKNOWN`; cached or fallback facts receive false server-owned provenance; and query-independent permission, narrative, or DUR fixtures can be bound to a different requested medicine.

## Evidence-gated decisions

- 90 `R01-CAN-090`: The data flow is proven, but reportability depends on unprovided personal-data classification, correlation, Redis access, and persistence facts. Proof gaps: Confirm production Redis persistence/backups, access controls, key telemetry, and the project's classification/retention policy for location searches. Determine whether any request/session identifiers are correlated with cache operations outside the target repository.
- 102 `R01-CAN-102`: The truncating implementation is proven, but material reachability depends on an unverified external cardinality/ordering fact, so central validation cannot promote it yet. Proof gaps: Obtain the official DUR pagination/totalCount contract and a real or stubbed product/kind response with more than 20 applicable rows. Confirm whether provider ordering guarantees the most safety-critical rows are on page one.

## Validation table

| Line | Candidate | Disposition | Severity | Confidence | Candidate |
|---:|---|---|---|---:|---|
| 77 | `R01-CAN-077` | not_reportable | none | 0.99 | Drug detail waits have no deadline across six parallel upstream calls |
| 78 | `R01-CAN-078` | not_reportable | none | 0.99 | Drug government clients have no response timeout |
| 79 | `R01-CAN-079` | not_reportable | none | 0.99 | Drug-permission lookups can block indefinitely on a stalled upstream response |
| 80 | `R01-CAN-080` | not_reportable | none | 0.99 | Easy-drug narrative lookups can block indefinitely on a stalled upstream response |
| 81 | `R01-CAN-081` | not_reportable | none | 0.99 | DUR warning retrieval can leave four parallel calls pending without a deadline |
| 82 | `R01-CAN-082` | reportable | P1 | 0.98 | Outer open-pharmacy requests can exhaust the 1,000-call daily quota |
| 83 | `R01-CAN-083` | not_reportable | none | 0.99 | Nested facility service fans every pharmacy row into a schedule lookup |
| 84 | `R01-CAN-084` | reportable | P1 | 0.98 | Outer hospital searches amplify into 20 list pages and 100 details |
| 85 | `R01-CAN-085` | reportable | P2 | 0.78 | Anonymous geocoding has no cache or call-rate budget |
| 86 | `R01-CAN-086` | reportable | P2 | 0.86 | Outer drug search has an attacker-expandable cache and quota keyspace |
| 87 | `R01-CAN-087` | reportable | P1 | 0.96 | Outer drug detail misses amplify into six government calls |
| 88 | `R01-CAN-088` | reportable | P2 | 0.97 | Exact hospital search coordinates create an attacker-expandable Redis cache |
| 89 | `R01-CAN-089` | reportable | P2 | 0.91 | Rounded pharmacy coordinates still expose billions of Redis cache keys |
| 90 | `R01-CAN-090` | needs_review | P2 | 0.72 | Facility searches persist exact or rounded user locations in Redis keys |
| 91 | `R01-CAN-091` | not_reportable | none | 0.96 | The location parser accepts every provider row despite requesting at most 100 |
| 92 | `R01-CAN-092` | reportable | P1 | 0.91 | Negative HIRA distances bypass radius filtering and ordering |
| 93 | `R01-CAN-093` | reportable | P1 | 0.94 | Malformed pharmacy coordinates and negative distances cross into results |
| 94 | `R01-CAN-094` | reportable | P0 | 0.94 | Malformed hospital hours collapse to CLOSED instead of UNKNOWN |
| 95 | `R01-CAN-095` | reportable | P0 | 0.94 | Malformed pharmacy hours collapse to CLOSED instead of UNKNOWN |
| 96 | `R01-CAN-096` | reportable | P1 | 0.99 | Korean public holidays are evaluated with ordinary weekday schedules |
| 97 | `R01-CAN-097` | reportable | P1 | 0.93 | Rounded pharmacy cache reuses origin-dependent distances |
| 98 | `R01-CAN-098` | reportable | P0 | 0.98 | Cached pharmacy facts are restamped as freshly retrieved |
| 99 | `R01-CAN-099` | reportable | P1 | 0.97 | Pharmacy fixture distances are rebound to an arbitrary caller origin |
| 100 | `R01-CAN-100` | reportable | P1 | 0.97 | Hospital fixture distances are rebound to an arbitrary caller origin |
| 101 | `R01-CAN-101` | not_reportable | none | 0.99 | A coarse shared cache reuses first-caller distances for other users in the same grid cell |
| 102 | `R01-CAN-102` | needs_review | P1 | 0.58 | Detailed drug warnings omit DUR rows after the first page |
| 103 | `R01-CAN-103` | not_reportable | none | 0.99 | Pairwise contraindication lookup can miss partners after the first DUR page |
| 104 | `R01-CAN-104` | not_reportable | none | 0.99 | Holiday early-morning checks can mark an open overnight pharmacy closed |
| 105 | `R01-CAN-105` | reportable | P0 | 0.99 | Composite outer hybrid fallback is stamped live and current |
| 106 | `R01-CAN-106` | not_reportable | none | 0.98 | Hybrid consumer-guidance fallback is presented as live government text |
| 107 | `R01-CAN-107` | not_reportable | none | 0.98 | Name search labels query-independent HYBRID fixture results as live MFDS data |
| 108 | `R01-CAN-108` | not_reportable | none | 0.98 | Ingredient search labels query-independent HYBRID ibuprofen fixtures as live results |
| 109 | `R01-CAN-109` | reportable | P0 | 0.98 | Hybrid permission fallback binds a fixed fixture product to an arbitrary requested ID |
| 110 | `R01-CAN-110` | reportable | P0 | 0.98 | Hybrid EasyDrug fallback attaches a fixed product narrative to an arbitrary drug |
| 111 | `R01-CAN-111` | reportable | P1 | 0.92 | Hybrid name search returns and caches an unrelated fixed product |
| 112 | `R01-CAN-112` | reportable | P1 | 0.95 | Hybrid ingredient search returns and caches ibuprofen for unrelated ingredients |
| 113 | `R01-CAN-113` | reportable | P0 | 0.97 | Outer DUR fallback warnings are not bound to the requested medicine |

## Method

Each receipt traces source, closest control, sink, impact, preconditions, and counterevidence against target `654f906e00e81648d1482210b6a9171747dddd75`. The source itself proves the 100-pharmacy-detail, 20-hospital-page, and 100-hospital-detail bounds; no disposable test output is required for those claims. Shared timeout variants were pruned from the common untimed `WebClient`/blocking-call root. The HYBRID provenance and identity cluster is established statically by query-independent fixture fallback, discarded origin metadata, and `DrugService` stamping every non-fixture-only result live. Provider contracts, production ingress controls, and production Redis policy were not guessed.

No canonical candidate file, candidate ledger, or repository file was modified.
