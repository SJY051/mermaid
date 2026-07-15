---
title: Server-authored medical output boundary
status: approved — staged amendment complete in this stack
created: 2026-07-13
modified: 2026-07-16
owner: 윤서진
tags: [safety, validator, DEV-603, OUT-02, OUT-04, "#55"]
---

# Server-authored medical output boundary

## 2026-07-16 staged amendment

SJY051 approved replacing the hybrid model-prose gate proposed below with a server-authored output
boundary. The change is staged deliberately so review does not hide a large deletion:

1. **Previous stack — non-empty official context.** `ServerAuthoredAnswerBuilder` maps complete retrieved
   records into canonical cards. It does not call whole-answer Pass 2. Product identity, ingredients,
   official Korean dosage, warnings, prescription status, allergy verdict, and provenance are
   server-owned; English indication and caution enrichment remain absent.
2. **This W3-B stack — true empty and Pass 1 unavailable.** A usable extraction with no terms, or a
   completed official lookup with no records, returns `server-empty-official-data`. An unusable Pass 1
   returns the distinct `server-search-unavailable` answer and performs no public lookup. Both are
   fixed server DTOs with no medicine, guidance, question, action, source, or model prose. JSON and
   SSE carry the same answer, and whole-answer Pass 2 is not called.
3. **Later cleanup.** Dormant whole-answer grounding and validation code is removed in a separate,
   reviewable cleanup after every reachable call has been blocked and verified.

The reason for changing the earlier approach must remain visible in the PR and release report:
providers repeatedly returned malformed or cross-wired structured answers after the server had
already paid the latency and model cost. A small prose rule set could still miss an unknown medicine,
diagnosis, cure, or reassurance. Server-authored cards close that safety boundary while preserving
the official data already retrieved instead of discarding it because a whole-answer schema failed.

Emergency triage, allergy clarification, and SA-08 allergy suppression remain server-authored direct
answers before the empty/unavailable split. This stack consumes the Pass 1 status introduced by the
bounded retry stack; it does not change that retry policy or the client-role boundary. Dormant legacy
source remains below exhaustive returns so its physical deletion can be reviewed separately.

## Superseded 2026-07-13 proposal (retained for audit history)

The hybrid semantic-policy proposal below records the earlier decision and test targets. Where it
conflicts with the staged amendment above, the amendment is the current approved contract.

## Context & problem

The output validator now checks *structure*: every drug card matches a retrieved record's
source and ingredient identities (OUT-03, done in [#60]), sources are grounded, and no
URL/markup reaches a rendered field (INV7). What it does **not** check is the *meaning*
of the model's prose.

A schema-valid answer can still, in `summary` / `guidance` / `warning` /
`clarifyingQuestions` / `urgency` text:
- **name a medicine the server never retrieved** (OUT-02),
- **diagnose** ("you have influenza"), violating AGENTS.md 2-1,
- **claim a cure** or say a drug is **"safe"**, and
- **repeat the system prompt or `DRUG_CONTEXT`** (LEAK-02).

A compiled production-class harness during the DEV-603 audit accepted both an unfetched
medicine and a definite diagnosis with **zero violations** — because the only prose scan
is for links and markup. Separately, an emergency answer can carry medication cards: the
emergency invariant checks only that a `SHOW_EMERGENCY_CALL` action exists, not that
`drugs` is empty (OUT-04).

The team decision (#55) is a **separate semantic policy gate**, fail-closed, with the
validator contract left to self-judgment (same owner as DEV-601). The DEV-603 report is
explicit that a fragile drug-name/diagnosis **regex is not the answer** — it names two
paths: (a) redesign pass 2 so user-visible medical fields are deterministically derived
from server records, or (b) a separately reviewed semantic policy gate with fail-closed
tests. This spec builds on the "server owns the facts" boundary that #59 (allergy) and
#60 (grounding) established, extending it from *fields* to *prose*.

Who it is for: an English-speaking, not-signed-in user reading medical prose in a second
language, who must never be told they have a condition, that a drug is safe, or about a
medicine the server did not actually verify.

## Goals / non-goals

- **Goals**
  - A medicine named anywhere in rendered prose must correspond to a **retrieved** record
    (reuse the `groundedDrugs` map from #60) — deterministic, not a regex guess.
  - Prose must not assert a **diagnosis**, a **cure**, or that a drug is **"safe"** — the
    clearest safety-claim violations — detected by a **human-reviewed** policy, not an
    ad-hoc regex a future edit can silently weaken.
  - An **emergency** answer (`urgency=emergency`) is canonicalized to the existing
    code-authored, **drug-free** 119 response (OUT-04); the model does not author it.
  - The gate is **fail-closed**: any violation replaces the answer with the server-authored
    refusal, exactly like the existing post-validation path.

- **Non-goals**
  - A full pass-2 rewrite into deterministic field templating (report option a) — recorded
    as future expansion; this spec pursues the reviewed policy gate (option b) unless the
    open question below decides otherwise.
  - An LLM-judge second call as the primary mechanism (cost, latency, and it is itself an
    injection surface). May be considered only as a defense-in-depth layer, not the gate.
  - Cross-reactivity / clinical reasoning (AR-01) — unchanged.
  - Re-doing OUT-03 grounding (done in #60).

## Requirements

### Deterministic drug-name grounding in prose
- **FR-001**: A medicine name appearing in any rendered prose field (`summary`, `guidance`,
  `warning`, `clarifyingQuestions`, `urgency.title`, `urgency.message`) that does **not**
  correspond to a product in `groundedDrugs` MUST be a violation. This reuses the
  server-owned retrieved set (#60); it is a membership check, not a spelling regex.

### Reviewed safety-claim policy
- **FR-002**: Prose MUST NOT assert a diagnosis, a cure, or that a medicine is "safe".
  **Hybrid mechanism (decided 2026-07-13):** the strong deterministic FR-001 drug-name
  check plus a **small set of enumerated rules in code** for the clearest claims (diagnosis
  verbs, "safe", cure claims). The rules live in code and are **PR-reviewed** (program
  logic, not per-row clinical data, so code review is the review — §2-6's signed-data
  treatment is for the synonym table's per-ingredient verdicts). Each rule ships with a
  fail-closed test. Deterministic field templating (option ii) is the recorded future
  target, not this slice.
- **FR-003**: The policy MUST be **fail-closed and testable**: each rule ships with a
  red-before/green-after test, and a violation is a hard refusal, not a downgrade.

### Emergency canonicalization (OUT-04)
- **FR-004**: When `urgency=emergency`, the answer MUST carry **empty `drugs`**. A model
  emergency answer with drug cards MUST be replaced by the code-authored, drug-free
  emergency response (`EmergencyTriage.emergencyAnswer`) — the same response the pre-model
  triage already emits. The model never authors an emergency answer's medicine content.

### Prompt/context non-disclosure (LEAK-02, P2) — deferred
- **FR-005 (deferred 2026-07-13)**: Prompt/context-leakage detection is **out of this
  slice**. It is P2 and its mechanism (verbatim-span match) differs; it becomes a follow-up
  after the OUT-02/04 core lands.

### Fail-closed integration
- **FR-006**: The gate runs after grounding/validation in the existing pipeline and, on any
  violation, returns the server-authored refusal (the current `fallback.safeAnswer` path).
  No model prose survives a violation. Both transports (JSON, SSE) share this gate.

## User scenarios

### Unfetched medicine in prose (P1)
- **Given** the server retrieved only acetaminophen products
- **When** the model's `summary` recommends "take ibuprofen"
- **Then** FR-001 fires (ibuprofen is not in `groundedDrugs`) and the answer is refused —
  even though the `drugs[]` array itself named nothing.

### Diagnosis or "safe" claim (P1)
- **Given** any answer
- **When** prose says "you have the flu" or "this is completely safe"
- **Then** the reviewed policy (FR-002) flags it and the answer is refused, never shown.

### Emergency with drug cards (P1)
- **Given** the model classified the turn `urgency=emergency` and included drug cards
- **When** the answer is validated
- **Then** FR-004 replaces it with the code-authored drug-free 119 response.

### Legitimate grounded answer (P1, negative)
- **Given** the model summarizes only the retrieved acetaminophen record without diagnosing
- **When** the gate runs
- **Then** no violation fires and the answer is shown unchanged.

## Success criteria

- **SC-001**: An answer whose prose names an unretrieved medicine is refused. Red before
  FR-001, green after; mutation: drop the prose membership check → a regression turns red.
- **SC-002**: A diagnosis / cure / "safe" claim in prose is refused via the reviewed
  policy. Mutation: unsign/remove a policy rule → its test turns red.
- **SC-003**: An `emergency` answer with non-empty `drugs` is replaced by the code-authored
  drug-free response. Red before FR-004, green after.
- **SC-004**: A grounded, non-diagnostic answer passes unchanged (no false positive).
- **SC-005**: `./gradlew test` passes; the compiled-harness cases from the DEV-603 report
  (unfetched medicine + definite diagnosis) now produce violations.

## Decisions (resolved 2026-07-13)

- **FR-002 mechanism** → **hybrid**: deterministic FR-001 drug-name grounding + a small set
  of enumerated, PR-reviewed rules in code for the clearest claims (diagnosis/"safe"/cure).
  Deterministic field templating (option ii) is the recorded future target.
- **Policy home** → **enumerated rules in code, PR-reviewed** (program logic, not per-row
  clinical data). Each rule ships a fail-closed test.
- **FR-005 (LEAK-02)** → **deferred** to a follow-up slice.
- **FR-001 drug detection** → match prose against the product-name space the server actually
  holds (`groundedDrugs` names, and retrieved-but-rejected names) — deterministic and
  false-positive-safe; no open-ended medicine lexicon.

## Open questions

- None blocking scaffold. The exact enumerated claim-rule list (which diagnosis verbs,
  which "safe"/cure phrasings) is refined during scaffold with a fail-closed test per rule.

## Future expansion
If option (ii) deterministic templating is chosen, this grows into a pass-2 redesign
warranting `plan.md` + `tasks.md`. The reviewed-policy path (option i) can ship as one
slice. This gate is the last DEV-603 output-boundary piece; after it, the audit's P0/P1
output findings are closed.
