---
title: Worker A — 007 appearance (tokens, icons, motion)
status: ready
created: 2026-07-15
owner: 윤서진
spec: docs/specs/007-ui-appearance/spec.md
verification: docs/specs/worker-briefs/VERIFICATION.md
base: main (after #70, #77, #78, #79 are merged — do not start before that)
branch: feat/DEV-007c-appearance
---

# Worker A — make it look like the thing we designed

Read [`docs/specs/007-ui-appearance/spec.md`](../007-ui-appearance/spec.md) first. FR-001 is done
(the shell is bounded). You are doing **FR-002, FR-003 and FR-004**, in that order.

**Every row of [VERIFICATION.md](VERIFICATION.md) applies to you and is part of "done".**

---

## The one thing that would waste your night if you assumed it

**astryx already has dark mode, and you must not build a palette.** An earlier note in this repo said
it ships no dark tokens. That was wrong, and the spec now says so: astryx expresses dark through the
CSS `light-dark()` function — 96 tokens — driven by `color-scheme`, which `index.css` already sets to
`light dark`. There is no `.dark` class and no `[data-theme]`, and looking for them is exactly how the
wrong conclusion was reached.

So this is a **replacement**, not a design.

## FR-002 — 18 hard-coded hex values become tokens

Find them: `grep -rn "#[0-9a-fA-F]\{6\}" frontend/src`. Every one is a colour written for the light
frame that the dark theme cannot reach.

The mapping is decided. Use these Tailwind utilities — astryx exposes them and all are dark-aware:

| meaning | where it may be used | utilities |
|---|---|---|
| **danger** | emergency banner, allergy **blocked** | `text-red-vivid` · `bg-red-subtle` · `border-red-ring` |
| **caution** | allergy **warning** / **unknown**, hours unknown, card warnings | `text-yellow-vivid` · `bg-yellow-subtle` · `border-yellow-ring` |
| **open** | facility **open now** — and nowhere else | `text-green-vivid` · `bg-green-subtle` · `border-green-ring` |
| **closed / neutral** | closed pin, muted glyphs | `text-secondary` · `border-strong` · `bg-muted` |

Naver markers take an **HTML string**, not a React component, so the three map glyphs carry
`var(--color-red-vivid)` etc. inline. That resolves in the document; it works.

**The green row is a safety rule with a colour attached.** Green may not appear on, next to, or inside
an allergy surface, in either theme — `no_match_found` is not reassurance (§2-2). A test asserts this;
do not weaken it.

**`bg-canvas` is not a token.** It resolves to transparent. `bg-surface` and `bg-muted` are real.

**Completion is the scan, not your eye:**

```bash
cd frontend && pnpm contrast     # exit 0 — zero WCAG AA failures, light AND dark, every tab
```

It already finds real failures on `main` — astryx's own `text-secondary` (#737373) on its own body
background (#f1f1f1) is **4.2:1**, under AA. Some of those resolve once the bounded shell paints
`bg-surface` behind them; the ones that do not are yours to fix, by choosing a token with enough
contrast, **never** by loosening the scan.

Also: `grep -rn "#[0-9a-fA-F]\{6\}" frontend/src/components` must return **nothing** when you are done
(SC-002). Paste the empty result.

## FR-003 — Icons come from one set

Add **`lucide-react`** (ISC — permissive, compatible, tree-shaken). This is the one dependency you are
allowed to add.

Replace every hand-drawn `<svg>` in `frontend/src/components`. Hand-drawing is what produced the mixed
stroke weights and corner radii the design review flagged.

The three map glyphs are **inlined from Lucide's path data** (Naver needs a string):

| kind | glyph | why not the current one |
|---|---|---|
| pharmacy | half-filled **capsule** | a rotated square does not read as a pharmacy |
| hospital | **cross** | a cross is right — keep it |
| emergency room | **heart-pulse** | a lightning bolt reads as *fast* or *electrical* before it reads as *emergency*. This is the glyph a person looks for at 3am. |

**Do not touch the system around them.** Shape = kind, fill = state, and the state also carries a
non-colour glyph (✓ open · ? unknown · ✕ closed). That pairing is why the map is readable to someone
who cannot distinguish red from green. It is not a style choice and it may not be simplified away.

Attribution for the inlined path data goes in a comment beside it.

## FR-004 — Motion that says "working", and nothing else

- The waiting indicator (the cold path exceeds 100 seconds; this is the only thing telling a person
  the app is not broken) becomes smooth. It stays **indeterminate** — never a progress bar implying a
  percentage we do not know.
- Motion is confined to: the waiting indicator, the bottom sheet, tab/section transitions.
- **Nothing in a safety state animates.** An emergency banner that slides is a banner that arrives late.
- **`prefers-reduced-motion: reduce` → no animation runs.** Someone who asked their device for less
  motion is often someone for whom motion is a symptom. Assert it (SC-006).

## Boundaries

- **Do not change behaviour.** No screen gains a capability. If you find yourself editing a `.ts` file
  that is not a component's styling, stop and say so.
- Do not touch backend, chat logic, allergy logic, or the grounding invariants.
- Do not edit tests except where a class name you changed is asserted — and if a test asserts a *hex*,
  that is the point of this task, so update it to assert the token and say you did.
- No copy changes. Not one word. If a string looks wrong to you, report it; do not fix it.

## Done means

The five commands in [VERIFICATION.md §1](VERIFICATION.md), plus:

```bash
cd frontend && pnpm contrast                                  # exit 0
grep -rn "#[0-9a-fA-F]\{6\}" frontend/src/components          # no output
```

…and a browser, at 320 / 390 / 768 / 1600, in light and dark, with screenshots. Every row of the
verification sheet answered, including the ones you could not complete.
