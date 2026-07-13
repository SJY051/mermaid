---
title: English safety-copy review
status: draft
created: 2026-07-13
owner: 최정민
tags: [pm, qa, ux, safety, english-copy]
---

# English safety-copy review

## Context & problem

mermAid is read by people seeking care in English, often under time pressure.
The service must explain verified medicine and facility information without
diagnosing, prescribing, or turning missing data into reassurance. Existing
components contain some safe copy, but the team has no single reviewed set for
every empty, loading, error, allergy, and location state.

## Goals / non-goals

- **Goals:** give FE-1 and FE-2 one reviewable English copy source; make each
  safety state understandable; preserve the repository's safety invariants.
- **Non-goals:** change medical logic, add medical claims, translate the full
  UI, or make a clinical judgement about an allergy.

## Requirements

- **FR-001:** Every response screen MUST keep a visible disclaimer that the
  service provides general information, not medical advice.
- **FR-002:** Allergy `no_match_found` copy MUST never say or imply "safe".
  It must say that the match was not found in the listed ingredients and direct
  the user to a pharmacist.
- **FR-003:** Unknown ingredient data, fixture data, unavailable verified data,
  and unknown opening hours MUST be stated as unknown or sample data, never as
  a positive result or a closure.
- **FR-004:** Emergency copy MUST direct the user to 119 or emergency care and
  must not wait for an online answer.
- **FR-005:** Facility copy MUST ask users to call ahead when hours may be
  incomplete.

## Proposed copy for review

| State | Proposed English copy | Notes for implementation |
|---|---|---|
| Persistent disclaimer | **This service provides general information, not medical advice. For personalised advice, speak with a licensed pharmacist or doctor. In an emergency, call 119.** | Keep visible before and after a response. |
| Privacy prompt | **Please do not include identifying information, such as a passport number or date of birth.** | Show beside the symptom input. |
| Emergency | **Call 119 now. In Korea, 119 is the emergency number for ambulance, fire, and rescue—similar to 911 in some countries. If you need police assistance, call 112. If you need language support, say “English please” or “Interpreter please.”** | The backend owns the emergency decision; UI only presents it. Keep this visible without waiting for an online reply. |
| Allergy: blocked | **This medicine lists: {ingredients}. It has been excluded from suggestions. Please check with a pharmacist or doctor.** | Do not say the medicine is unsafe for everyone. |
| Allergy: warning | **Possible ingredient match. Please ask a pharmacist before taking this medicine.** | Warning is not a confirmed match. |
| Allergy: no match found | **No match was found in the listed ingredients. This is not a guarantee that the medicine is suitable for you. Please ask a pharmacist.** | Plain text, not a green badge; never use “safe”. |
| Allergy: unknown | **We could not check the ingredient list. Please ask a pharmacist before taking this medicine.** | Treat unavailable data as a warning. |
| Verified-data unavailable | **We could not retrieve verified medicine information right now. Please try again, or ask a pharmacist.** | Do not show medicine suggestions from unavailable data. |
| Empty medicine result | **No matching medicines were found in the available records. A pharmacist may be able to help with other options.** | Do not infer that no medicine exists. |
| Fixture data | **Showing sample data for development. Availability may not reflect current conditions.** | Must be visible whenever `dataStatus` is `fixture`. |
| Location denied | **We could not access your location. Showing results near Seoul City Hall instead.** | Do not claim the fallback is near the user. |
| Hours unknown | **Hours are unknown. Call before you go to confirm that the facility is open.** | Never render this state as “Closed”. |
| Empty facility result | **No facilities matching these filters were found. Try changing the filters or contacting a local health service.** | Do not state that no facilities exist nearby. |
| Long wait / loading | **Looking up verified information. This can take a little while.** | Keep visible while the chat request is pending. |

## User scenarios

### Allergy record is incomplete (P0)

- **Given** a user has declared an allergy and the ingredient list has no match
- **When** the medicine card is displayed
- **Then** it does not use a green badge or the word “safe”, and tells the user
  to ask a pharmacist.

### Facility hours are unavailable (P0)

- **Given** a facility has `isOpenNow: null`
- **When** it appears on the map or list
- **Then** it says that hours are unknown and asks the user to call ahead.

## Success criteria

- **SC-001:** PM/QA marks every proposed line approved or supplies a replacement.
- **SC-002:** FE-1 and FE-2 can map every relevant UI state to one approved line.
- **SC-003:** Frontend tests keep `no_match_found` non-reassuring and hours
  unknown distinct from closed.

## Open questions

- [NEEDS CLARIFICATION: Should emergency copy say “emergency department” in
  addition to “emergency care”, or should it remain shorter for readability?]
- [NEEDS CLARIFICATION: Who approves final English wording before FE adoption?]

## Reference

The Korea Tourism Organization states that 119 reaches fire and ambulance
services in Korea, 112 reaches police, and callers can ask for English or an
interpreter. Verified 2026-07-13: [Disaster Safety Services for International
Visitors in Korea](https://english.visitkorea.or.kr/svc/contents/contentsView.do?menuSn=217&vcontsId=248145).

## Future expansion

After approval, FE-1 and FE-2 can implement the approved lines in their owned
components and add the corresponding component tests. This document stays the
PM/QA reference for later copy reviews.
