---
title: Worker C — 009 Settings
status: ready
created: 2026-07-15
owner: 윤서진
spec: docs/specs/009-settings/spec.md
verification: docs/specs/worker-briefs/VERIFICATION.md
base: main, after worker A's appearance work has merged
branch: feat/DEV-009-settings
---

# Worker C — the consent screen, and the toggle a wrong fact had disabled

Read [`docs/specs/009-settings/spec.md`](../009-settings/spec.md). Implement it.

**Every row of [VERIFICATION.md](VERIFICATION.md) applies to you and is part of "done".**

---

## Two of the four cards say "coming later". One of them was lying.

```tsx
{/* Theme-neutral 0.1.4 ships no dark tokens — spec §5 resolution 2026-07-13. */}
<Switch label="Appearance" isDisabled disabledMessage="Manual appearance controls are not available yet." />
```

**That comment is false.** astryx ships 96 dark tokens, through the CSS `light-dark()` function, driven
by `color-scheme` — which `index.css` already sets. The switch has been disabled by a fact nobody
re-checked, and the wireframe renders §6 in dark specifically to demonstrate the toggle it disabled.

Delete the comment. Build the control.

## FR-001 — Appearance: three states

`Light` / `Dark` / `Follow my device` — a **segmented control, not a two-state switch.** "Off" must not
silently mean "light" for someone whose device is dark.

Implementation is one property: set `color-scheme` on `:root` to `light`, `dark`, or `light dark`.
That is the property astryx's tokens already read. **No component changes. No second palette. No
`.dark` class.** Persist it in `mermaid.preferences.v1` beside the other UI preferences.

SC-001 asserts the **computed style**, not a class name.

## FR-002 — Allergy memory: the §2-5 consent boundary

This is the part that matters, and it is the only place in the app allowed to own this decision.

A switch, **off by default**. On means a list of a person's allergies is written to their device and
survives the tab — a real change in what this app remembers about someone. It must be a decision they
made, on a screen that says plainly what it does:

> **Remember my allergies**
> Off — your allergy list is used for this conversation only, and is forgotten when you close the tab.
> On — your allergy list is saved on this device so you do not have to tell us again. It is never sent
> to our server, and never shared.

Show the current list under it, as the wireframe does: `ibuprofen · aspirin (this session only)`.

**Turning it off asks once, then deletes.**

> *Forget your allergy list? We will delete ibuprofen and aspirin from this device. You can tell us
> again at any time.*

Confirm → the key is **gone from `localStorage`** before the toggle settles (SC-003 reads storage
directly and goes red if the data survives). Cancel → **nothing** changes, including the switch, which
must not appear to have moved.

Build the confirmation as a **reusable destructive-consent dialog**, not a bespoke one: name what will
be deleted, say where it lives, say it can be given again. The medical-profile work spec 002 deferred
will need exactly this shape and will replace this switch rather than sit beside it.

## Three things this screen must never do

Each one, we have already been burned by, in this exact code path:

1. **Never say "safe".** Not here, not anywhere near an allergy state (§2-2). SC-004 asserts the word
   appears nowhere on this screen, in any state.
2. **Never present the stored list as verified.** An allergen the user *typed* is checked by name only
   and blocks nothing (§2-6, AR-02). If the list shown here contains one, mark it as name-match-only,
   **in the same words the chat uses**. Two lists, two promises; conflating them overstates one.
3. **Never make this the only way to remove an allergen.** Clearing here and editing in the chat must
   agree, always. (A manual edit silently superseding a failed declaration was a P0 this week. Read
   `chatSession.confirmAllergies` and its comment before you touch the list from here.)

## Boundaries

- `SettingsScreen.tsx`, the new dialog, `storage.ts` (preferences only), and their tests. Nothing else.
- **Do not touch `chatSession.tsx`'s allergy logic.** If Settings needs to clear the list, use the
  existing API; if there isn't one, say so and stop — do not add a second write path.
- Do not add a dependency. Icons from `lucide-react` (worker A added it).
- Language switching is **not** in scope. It stays as it is: `English` · `한국어 — coming later`.
- No copy you invented. The sentences above are the copy. Anything else you had to write goes in your
  report (VERIFICATION §3).

## Done means

The five commands in [VERIFICATION.md §1](VERIFICATION.md), plus SC-001…SC-006, each asserted by a
test — and a browser: switch to Dark and watch the app change, switch to Follow and watch it follow,
turn allergy memory on, reload, confirm it survived, turn it off, cancel (nothing moves), turn it off,
confirm (the key is gone). At 320 / 390 / 768 / 1600, light and dark, `pnpm contrast` at 0 failures.
