# R05-CAN-004 provenance correction hold

## Verdict

Do not append the prepared centralized-validation or attack-path receipts for `R05-CAN-004` unchanged. The canonical and validation records preserve one inaccurate affected-location range. This is a provenance defect in a `needs_review` row, not a change to the finding's current disposition.

## Frozen inaccurate claim

- Canonical/validation path: `backend/src/main/java/com/mermaid/drug/DrugService.java`
- Preserved lines: `236-253`
- Preserved claim: retrieval deduplicates by `itemSeq`, so distinct same-name records are not inherently removed.

At immutable target `654f906e00e81648d1482210b6a9171747dddd75`, lines `236-253` contain a no-guidance debug branch, detail assembly, the return, and the start of the `detail` Javadoc. They do not contain the deduplication predicate.

## Correct immutable-target evidence

The `itemSeq` identity control is at `DrugService.java:214-219`:

```text
214  List<Drug> merged =
215      candidates.stream()
216          .filter(d -> !isExportOnly(d.nameKo()))
217          .filter(distinctBy(Drug::itemSeq))
218          .limit(MAX_DETAIL_PROBES)
219          .toList();
```

The other three affected locations remain unchanged:

- `DrugContextRetriever.java:269-312`
- `DrugContextRetriever.java:377-384`
- `AnswerValidator.java:123-139`

## Required handoff

Round 7 should make a provenance-only canonical correction under a new authority seal, propagate the corrected range to centralized validation and attack-path staging, and re-run both deterministic builders. The Round 0-6 discovery work must not be regenerated, and the existing 188-row canonical stream must not be edited casually because its SHA-256 is the Round 6 handoff identity.
