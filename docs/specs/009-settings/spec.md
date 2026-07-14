---
title: Settings — the consent screen, and the two controls that were blocked by a wrong fact
status: draft
created: 2026-07-15
owner: ASQi
tags: [frontend, settings, consent, safety]
---

# 009 — Settings

## Context & problem

Settings has four cards. Two of them say "coming later", and one of those two says it for a reason
that turned out to be false.

```tsx
{/* Theme-neutral 0.1.4 ships no dark tokens — spec §5 resolution 2026-07-13. */}
<Switch label="Appearance" isDisabled disabledMessage="Manual appearance controls are not available yet." />
```

**That comment is wrong** (corrected 2026-07-15, see [007] FR-002). astryx expresses dark through the
CSS `light-dark()` function — 96 tokens — driven by `color-scheme`, which we already set to
`light dark`. A manual override is one property on `:root`. The switch has been disabled for a day by
a fact nobody re-checked, and the wireframe's §6 renders in dark specifically to demo that toggle.

The other "coming later" is the one that matters:

```tsx
<h2>Allergy profile</h2>
<p>Coming later. Allergy profile and consent controls are not available yet.</p>
```

**This is the §2-5 consent boundary, and it is the only place it may live.** Allergy memory is opt-in
and off by default: when off, both the read and the write path drop the list, and the conversation's
allergy list dies with the tab like everything else. Turning it on means a list of a person's
allergies is written to their device and survives the tab — a real change in what this app remembers
about someone, and it must be a decision they made, in a place that says plainly what it does.

Onboarding does **not** get to write this list ([008], non-goal). Neither does the chat. Here, once.

## Goals / non-goals

- **Goals:** a working appearance control; an allergy-memory opt-in whose copy says exactly what it
  does and undoes; a Settings screen that matches the wireframe (§6) rather than apologising.
- **Non-goals:** language switching (한국어 is a later product decision, not a toggle we can ship);
  accounts; any server-side profile the ERD does not already hold.

## Requirements

### FR-001 — Appearance: three states, and the device is one of them

`Light` / `Dark` / `Follow my device` — a segmented control, not a two-state switch, because "off"
must not silently mean "light" for someone whose device is dark.

Implemented by setting `color-scheme` on `:root` (`light`, `dark`, or `light dark` for follow), which
is the property astryx's `light-dark()` tokens already read. **No component changes, no second
palette, no `.dark` class.** Stored in `mermaid.preferences.v1` alongside the other UI preferences.

The wireframe renders §6 in dark on purpose: this is the control it is demonstrating.

### FR-002 — Allergy memory: the opt-in, and it is off

A switch, **off by default**, labelled and described in the words of what actually happens:

> **Remember my allergies**
> Off — your allergy list is used for this conversation only, and is forgotten when you close the tab.
> On — your allergy list is saved on this device so you do not have to tell us again. It is never sent
> to our server, and never shared.

The state today, shown under it, exactly as the wireframe does: `ibuprofen · aspirin (this session only)`.

**Turning it OFF asks once, and then deletes what was stored.** An opt-out that leaves the data behind
is not an opt-out — but a list of someone's allergies is not something to delete on a mis-tap either,
and re-entering it is exactly the friction that gets a person to skip declaring an allergy at all.

So: a confirmation. *"Forget your allergy list? We will delete ibuprofen and aspirin from this device.
You can tell us again at any time."* Confirm → the key is gone from `localStorage` before the toggle
settles. Cancel → nothing changes, including the switch, which must not appear to have moved.

The confirmation is deliberately a **reusable destructive-consent dialog**, not a bespoke one: the
medical-profile work spec 002 deferred (allergies, conditions, current medicines) will need exactly
this shape — name what will be deleted, say where it lives, say it can be given again — and will
replace this switch rather than sit beside it.

**Turning it ON does not retroactively promise anything.** It saves the list as it is now; it does not
claim the app was safer before.

Three things this screen must never do, each of which we have already been burned by in the allergy
path:

1. **Never say "safe".** Not here, not anywhere near an allergy state (§2-2).
2. **Never present the stored list as verified.** An unverified allergen the user typed is checked by
   name only and blocks nothing (§2-6, AR-02) — if the list shown here contains one, it is marked as
   such, in the same words the chat uses. Two lists, two promises; conflating them overstates one.
3. **Never make this the only way to remove an allergen.** Clearing here and editing in the chat must
   agree, always. (The clarification cut-off bug — a manual edit silently superseding a failed
   declaration — was exactly this class, and it was a P0.)

### FR-003 — About, and the sources

Unchanged in substance, correct as it stands: 식약처 · 심평원 · 국립중앙의료원, and "This app informs — it
never diagnoses." Keep the `lang="ko"` wrappers; add them to any agency name that is missing one.

### FR-004 — It looks like the app

Tokens only, contrast scan clean in light **and dark** (007 FR-002) — a Settings screen that fails
contrast in the very theme its own toggle switches to would be a particular kind of embarrassing.
Bounded shell (007 FR-001), `prefers-reduced-motion` honoured (007 FR-004).

## User scenarios

### Someone turns allergy memory on, then off again (P1)
- **Given** the switch is on and `ibuprofen` is stored on the device
- **When** they turn it off
- **Then** the stored list is deleted from `localStorage` there and then, and the screen says so.

### Someone with a dark phone opens Settings (P1)
- **Given** their device is in dark mode and the control is on "Follow my device"
- **Then** the screen is dark, every word on it meets AA contrast, and the control shows which state
  it is actually in — not an unlabelled off switch.

### Someone typed an allergen we cannot bind (P1)
- **Given** the stored list contains a typed allergen with no reviewed dictionary row
- **Then** Settings marks it as name-match-only, in the same words the chat uses — it never appears in
  a list that reads as "these are avoided".

## Success criteria

- **SC-001**: the appearance control has three states and actually changes `color-scheme` on `:root`.
  Asserted by computed style, not by a class name.
- **SC-002**: allergy memory is **off** on a fresh device. A test asserts the default.
- **SC-003**: turning it off asks for confirmation first; **Confirm removes the key** from
  `localStorage` (the test reads storage directly and goes red if the data survives), and **Cancel
  changes nothing** — not the storage, and not the switch's position.
- **SC-004**: the word "safe" appears nowhere on this screen, in any state. Asserted (§2-2).
- **SC-005**: an unverified typed allergen is shown as name-match-only, never as avoided.
- **SC-006**: contrast scan reports 0 failures on Settings in light and in dark.

## Open questions

None blocking.

## Future expansion

Language (한국어) is a product decision with a translation cost, not a switch. Notification settings
exist in the ERD and have no UI yet; they are not urgent and are not here.
