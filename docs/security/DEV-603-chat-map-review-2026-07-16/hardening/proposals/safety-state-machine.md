# Security Hardening Proposal: Server-owned emergency and allergy state machine

## Decision

Decide whether emergency/allergy truth continues to emerge from regexes, retriever shortcuts, model fields, answer IDs, frontend session state, and picker refreshes, or whether one deterministic server state machine owns every safety transition and permitted output combination.

## Executive Recommendation

- **Option 1: Expanded deterministic guards.** Add missing categories and negation handling, preserve exclusions, constrain answer/action combinations, and repair state-loss paths in place.
- **Option 2: Owned safety state machine.** Normalize safety events once, transition through reviewed server states, and let the model explain only within states where medication output is permitted.

I recommend Option 2 after clinicians and product/accessibility reviewers approve the transition table and exact server-authored copy. We should use Option 1 as immediate containment, especially for already validated P0 paths.

## Evidence

| Evidence | Finding or audit | What it establishes |
| --- | --- | --- |
| `R01-CAN-020` | [Emergency answers can retain medication cards](../../artifacts/central_validation_round06/batch-01/validation.jsonl) | **Observed:** a sealed provider run returned emergency urgency, 119 action, and a grounded drug card together. |
| `R01-CAN-028` | [Reaction-only allergy declarations bypass fail-closed handling](../../artifacts/central_validation_round06/batch-01/validation.jsonl) | **Observed:** common reaction wording does not activate SA-08. |
| `R01-CAN-029` | [Acknowledged emergency categories bypass deterministic triage](../../artifacts/central_validation_round06/batch-01/validation.jsonl) | **Observed:** severe categories can fall into routine model/allergy flow. |
| `R01-CAN-034` | [Model answerId invokes server-only allergy workflow](../../artifacts/central_validation_round06/batch-01/validation.jsonl) | **Observed:** model data can select an authoritative safety transition. |
| `R01-CAN-046` | [Drug search drops exclusions it cannot normalize](../../artifacts/central_validation_round06/batch-02/validation.jsonl) | **Observed:** an incomplete exclusion set can produce an official-looking no-match result. |
| `R02-CAN-003` | [Null urgency suppresses fail-upward emergency handling](../../artifacts/central_validation_round06/batch-04/validation.jsonl) | **Observed:** an explicit null can prevent an authoritative emergency presentation. |
| `R05-CAN-014` | [Generic emergency copy may conflict with rescue medication](../../artifacts/central_validation_round06/batch-05/validation.jsonl) | **Needs review:** copy is deterministic; clinical harm requires a clinician's judgment. |
| `R06-CAN-001/002` | [Negation/recency and unreviewed qualifier candidates](../../artifacts/central_validation_round06/batch-05/validation.jsonl) | **Needs review:** code paths are observed, while clinical/product validity remains unresolved. |

**Inferred:** the dominant failure is split state ownership. Backend matchers, the model, and the browser can each create or erase facts that change whether medication advice is allowed.

## Current Design And Failure Mode

`EmergencyTriage` correctly short-circuits known matches before the model, but its finite pattern list is itself the category boundary. Allergy declaration, exclusion normalization, retrieval, clarification, `answerId`, urgency/actions, and browser picker state each participate in later safety decisions. The system can therefore fail both ways: miss an emergency/reaction and proceed, or conservatively lock a negated/historical report into an emergency-only path.

The important distinction from a bigger regex list is authority. A model may suggest explanatory prose, but it must never select a safety workflow. A frontend control may edit a declaration, but it must not silently delete prior server-recognized state. A state machine gives us one reviewed place to express those rules and makes forbidden combinations—emergency plus drug cards, unresolved allergy plus model suggestions—unrepresentable.

## Desired Invariants

- Raw current user text is normalized once before any LLM call; equivalent punctuation/spacing cannot skip the same reviewed category.
- Only deterministic server events transition emergency/allergy state.
- `EMERGENCY` output always uses canonical server copy/action and structurally cannot carry drug cards.
- Any declared but unresolved allergy transitions to clarification/blocked behavior; exclusions are lossless until a reviewed binding is made.
- Model fields and answer IDs cannot create, clear, or downgrade safety state.
- Every rule and copy change records clinical reviewer approval; software never fabricates clinical equivalence.

## Constraints And Non-Goals

The machine must remain conservative without claiming that all red-flag language is current or affirmative. It cannot supply missing cross-reactivity knowledge. Human review of synonyms/qualifiers and rescue-medication language remains mandatory. Browser-local state is not trusted as the authoritative transition ledger.

## Before Architecture

[Current distributed safety flow](../diagrams/safety-state-machine-before.mmd)

The before view shows why ordering alone is insufficient: triage runs first for recognized text, but later model and UI fields still participate in safety state and output composition.

## Options

### Option 1: Expanded deterministic guards

We can extend emergency categories and text normalization, add negation/recency cases, broaden reaction declarations, preserve picker selections, require canonical emergency actions, and reject emergency-card combinations. This is the fastest way to make known failures red and then green, and it avoids a new state contract.

The tradeoff is continued coupling. Every guard must agree on what “declared,” “unresolved,” and “emergency” mean, and the browser still holds state that can diverge from the server. Clinical changes remain scattered across patterns, normalizers, validator rules, and copy.

[Option 1 after view](../diagrams/safety-state-machine-local-guards-after.mmd)

| Change | Before | After | Security consequence | Cost |
| --- | --- | --- | --- | --- |
| Emergency recognition | Finite fragile patterns | Reviewed categories plus normalization | Closes known misses | False-positive review and regression corpus |
| Allergy recognition | Keyword/declaration split | Reaction forms and lossless exclusions | Restores SA-08 for known language | Clinical synonym governance |
| Output combinations | Validator permits some conflicts | Explicit state/action/card checks | Blocks emergency+medicine | More distributed conditions |
| Browser state | Picker can erase declarations | Preserve prior selections | Avoids silent downgrade | Frontend migration/tests |

Runtime cost is small and bounded. Reliability can improve for known phrases but may regress through conservative false positives; rollback is local. The strongest risk is future drift between the same concepts represented in different classes.

### Option 2: Owned safety state machine

The structural option defines reviewed events and states such as `NO_SIGNAL`, `EMERGENCY`, `ALLERGY_UNRESOLVED`, `ALLERGY_BLOCKED`, and `MEDICINE_ALLOWED`. A normalizer produces candidate events from raw text and structured exclusions; the state machine resolves them under reviewed rules. The model receives only the state-appropriate context and can never emit a transition command. A server composer creates the only permitted safety output for that state.

This lets us reason about both fail-open and over-strong fail-closed behavior in one table. Negation and recency do not become ad hoc bypasses; they are inputs whose handling is reviewed. The browser renders typed state and may request a new declaration event, but it cannot stamp state from an `answerId` or silently remove a prior declaration.

The machine becomes a safety-critical choke point, so unavailable or ambiguous inputs must have a documented fallback rather than throwing into the model path. It adds little memory and CPU, but it increases governance burden: clinicians own category/equivalence decisions, and product/accessibility owners approve what each state says and exposes. Rollback can keep the old recognizers behind the event adapter while the new machine runs in comparison mode, but we must never compare by exposing two different user-facing safety answers.

[Option 2 after view](../diagrams/safety-state-machine-owned-machine-after.mmd)

| Change | Before | After | Security consequence | Cost |
| --- | --- | --- | --- | --- |
| State authority | Regex/retriever/model/UI split | One server state machine | Invalid transitions become unrepresentable | New state/event contract |
| Model role | Can select urgency/workflow-like fields | Explanation only in permitted states | Removes model authority over safety | Prompt/DTO changes |
| Copy/actions | Mixed model and server ownership | State-authored canonical output | Prevents contradictory emergency guidance | Clinical/product ownership |
| Frontend | Infers state from answer fields | Renders typed server state | Prevents client downgrade | FE–BE coordinated migration |

## Comparison

| Dimension | Option 1: expanded guards | Option 2: owned machine |
| --- | --- | --- |
| Security | Closes reproduced gaps; scattered authority remains | Centralizes transitions and output combinations |
| Performance | Neutral | Neutral to small bounded normalization cost |
| Memory | Neutral | Small event/state objects |
| Reliability | Risk of matcher false positives remains dispersed | Explicit ambiguity/fallback states; choke-point failure risk |
| Operability | Existing logs/tests | State transition reason codes and reviewable rule version |
| Migration | Focused backend/frontend fixes | Versioned event/state/response contract |
| Rollback | Revert individual guards | Retain adapter and old deterministic path until acceptance |

## Recommendation

I would choose Option 2 because the findings include missed inputs, forbidden outputs, model-owned transitions, and client state loss. A list of local fixes cannot prove those categories stay synchronized. Option 1 should nevertheless land first as tactical safety containment once a separate fix brief is authorized.

## Evidence Coverage And Residual Risk

| Evidence | Option 1 | Option 2 | Tactical fix still required? |
| --- | --- | --- | --- |
| `R01-CAN-020` — emergency plus drug | Addresses | Addresses by state output type | Yes |
| `R01-CAN-028/029` — reaction/category misses | Addresses known phrases | Addresses through reviewed event mapping | Yes |
| `R01-CAN-034` — model answerId transition | Addresses with rejection | Addresses by removing model transition authority | Yes |
| `R01-CAN-046` — lost exclusion | Addresses normalization branch | Addresses with lossless event/state representation | Yes |
| `R02-CAN-003` — null urgency | Addresses fallback check | Addresses because model urgency is not state authority | Yes |
| `R05-CAN-014`, `R06-CAN-001/002` — clinical/product candidates | Unknown until review | Creates a reviewable decision point, not an answer | Human proof remains mandatory |

Residual risk includes incomplete clinical rules, unknown cross-reactivity, adversarial ambiguity, inaccessible UI, and stale browser state outside the typed transition protocol.

## Migration And Rollout

Existing deterministic short-circuiting and allergy blocks remain in force until the new machine is independently accepted. The design should support non-user-visible transition comparison and rule-version auditing, but the exact migration sequence is deferred.

## Validation Plan

- Build a clinician-approved corpus spanning affirmative, negated, historical, ambiguous, misspelled, punctuated, and multilingual red flags/reactions.
- Prove every state has an exhaustive output contract and that `EMERGENCY`/`ALLERGY_UNRESOLVED` cannot serialize drug cards.
- Mutation-test removal of each category, negation rule, exclusion, and canonical action.
- Browser-test state preservation across picker refresh, retry, new conversation, and session restore.
- Measure classification latency and false-positive/false-negative review outcomes against the current deterministic path.

## Implementation Work Packages

Intentionally omitted pending option selection and clinical/product review.

## Open Questions

- Which categories and negation/recency rules are clinically approved for deterministic action?
- What server-authored rescue-medication wording is safe and comprehensible?
- Which ambiguous cases clarify immediately, and which recommend professional care without medication output?
- Who signs and versions the state table and allergen qualifier metadata?
