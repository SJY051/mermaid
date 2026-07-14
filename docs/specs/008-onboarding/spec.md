---
title: Onboarding — three screens, before anyone is unwell
status: draft
created: 2026-07-15
owner: ASQi
tags: [frontend, onboarding, safety]
---

# 008 — Onboarding

## Context & problem

There is no onboarding. There is no first-run state of any kind: `grep -ri "onboard\|firstRun\|hasSeen"`
over `frontend/src` returns nothing. Someone opens mermAid and is looking at a chat box.

Three things go wrong as a result, and the third is the expensive one.

1. **Nobody is told what this is.** The app answers with government-verified medicine information and
   never diagnoses. That distinction is the whole product, and a person who does not know it either
   over-trusts the answer or does not trust it at all.
2. **The disclaimer is a strip.** It is always on screen (§2-1), which is right, and it is six words
   at the bottom of a screen, which means the first time it matters is the first time nobody reads it.
3. **The location permission arrives with no reason attached.** `MapScreen` calls `resolveLocation()`
   as soon as the Map tab mounts, so the browser's own permission dialog is the first explanation a
   person gets. Someone who has just landed, is ill, and does not read Korean is being asked by a grey
   system box to share where they are. They press Deny — and now the pharmacy map, the reason they
   opened the app at 11pm, is a fallback pin on Seoul City Hall. **The denial is permanent per origin
   and we cannot ask again.** We get one chance and we currently spend it silently.

## Goals / non-goals

- **Goals:** say what the app is and is not; put the disclaimer somewhere it is actually read once;
  ask for location *after* saying why, so a Deny is an informed Deny and a grant is an informed grant.
- **Non-goals:** **no allergy input** (decided 2026-07-15, ASQi). The allergy picker stays where it
  is — raised by the chat when a person declares an allergy. Onboarding must not become a second place
  that writes the allergy list: §2-5's consent boundary lives in Settings and nowhere else, and every
  extra state around the allergy list has cost us a P0 (see [007]'s state-space note and the
  composer-clear history). Also not here: accounts, language choice, anything that needs a server.

## Requirements

### FR-001 — Three screens, skippable, shown once

| # | says | why it exists |
|---|---|---|
| 1 | **What this is.** Medicine information verified against Korean government data, explained in English. It never diagnoses. | The product in one sentence, in the reader's language. |
| 2 | **The disclaimer, full size.** Not the six-word strip — the sentence, on its own screen, once, while the reader is well. Plus: this conversation is not saved and dies with the tab (§2-5). | A disclaimer that has been read once is worth more than one that is always present and never read. The strip stays regardless (§2-1); this does not replace it. |
| 3 | **Why we would like your location**, then the button that asks. "To show pharmacies and hospitals that are open near you, right now. We do not store where you are." | See FR-002. |

**Skippable at any point**, and skipping is not a lesser path: someone in a hurry because they feel
awful must reach the chat box in one tap. Skip lands on Chat, exactly as today.

**Shown once**, keyed in `localStorage` (`mermaid.onboarding.v1`) — not `sessionStorage`: the point is
that a returning user is not asked twice, and a tab-scoped flag would ask them every time. This is a
UI preference, not a transcript; §2-5 is about consultation content, and this stores a boolean.

### FR-002 — The location permission is asked for by us, not sprung by the browser

The browser's permission dialog is fired **only by a tap on our own button**, on screen 3, under a
sentence that says what it is for. Not on mount — **of any component.**

There are **two** mount-time callers, not one, and review caught the second:

| caller | when it fires today |
|---|---|
| `MapScreen` | the Map tab is opened |
| `NearbyFacilities` | **the chat renders an assistant facility map** — i.e. in the middle of a conversation, with no map tab in sight |

The second is the worse one. A person asks about a headache, the assistant offers to show pharmacies,
and the browser throws a permission box at them mid-answer. Fixing only `MapScreen` would leave that
path open and let us believe we had closed it.

**`resolveLocation()` must not be called from a mount effect anywhere.** Both components take the
location they are given, and both offer their own "Use my location" control when they do not have one.
`grep -rn "resolveLocation" frontend/src` is the check: no call may sit inside a `useEffect` that runs
on mount.

This is not a nicety. A denial is **permanent for the origin** — the browser will not re-prompt, and
no code of ours can undo it. Today we spend that one chance the moment the Map tab mounts, with no
explanation on screen. After this, a Deny means the person read why and said no, which we respect: the
Map tab keeps working exactly as it does now (manual pin, address search, honest `fallback` notice —
DEV-206/206b), and it must never nag.

Someone who skips onboarding is never asked at all until they tap the Map tab's own "Use my location"
control. **Skipping is not consent, and it is not refusal either.**

### FR-003 — Nothing here weakens a §2 invariant

Stated because onboarding is exactly where they would be weakened by accident:

- The disclaimer strip **still shows on every screen**, including during onboarding (§2-1). Screen 2
  is in addition to it, never instead of it.
- Onboarding **writes no allergy data**, reads none, and offers no allergen input (non-goal above).
- Onboarding **stores nothing but the seen-flag.** No name, no age, no symptoms — SA-04 tells people
  not to type identifying details into the chat box; onboarding must not ask for them itself.
- Screen 3 **must not imply that granting location is required.** The app works without it and says so.

### FR-004 — It looks like the app

Onboarding renders inside the bounded shell (007 FR-001), uses tokens only (007 FR-002 — zero
hard-coded hex, contrast scan clean in light and dark), and honours `prefers-reduced-motion` (007
FR-004). It ships after 007, on top of it, not beside it.

## User scenarios

### Someone opens the app for the first time, at 11pm, feeling awful (P1)
- **Given** they have never opened mermAid
- **When** the app loads
- **Then** they see screen 1 — and a Skip that reaches the chat box in one tap.

### Someone reads why we want their location, and says no (P1)
- **Given** screen 3
- **When** they tap the button and then Deny in the browser dialog
- **Then** the app continues, the Map tab still works from a manual pin or an address, and it never
  asks again — because it cannot.

### Someone who has used the app before opens it again (P1)
- **Given** `mermaid.onboarding.v1` is set
- **Then** they land on Chat. No onboarding, no re-prompt.

## Success criteria

- **SC-001**: a first-run visit shows screen 1; a second visit does not. Asserted by a test, not by a
  reviewer clicking twice.
- **SC-002**: `navigator.geolocation.getCurrentPosition` is **not called on mount by any component** —
  only from an explicit user tap. Asserted by spying on it, in **both** `MapScreen` and
  `NearbyFacilities`; each test goes red if its component prompts on mount again.
- **SC-003**: the disclaimer strip is present on every onboarding screen (§2-1).
- **SC-004**: onboarding touches no allergy key in storage. A test asserts the allergy list is
  untouched across the whole flow.
- **SC-005**: Skip reaches Chat in one tap, from any screen.
- **SC-006**: contrast scan reports 0 failures in light and dark for the onboarding screens.

## Open questions

None blocking. (The one that was — whether onboarding collects allergies — was decided: it does not.)

## Future expansion

The medical-profile onboarding that spec 002 §5 deferred to "003" (allergies, conditions, current
medicines, sharing with a clinician) is **still deferred, and is not this spec.** It carries a §2-5
and §2-1 re-examination and needs its own document. This spec is deliberately the orientation, and
nothing more.
