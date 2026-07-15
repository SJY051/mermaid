---
title: UI appearance — make it look like the thing we designed
status: draft
created: 2026-07-14
owner: ASQi
tags: [frontend, design, wireframe]
---

# 007 — UI appearance

## Context & problem

Every screen the wireframe describes now exists and works. None of them looks like the wireframe.

The gap is not decoration. Three of the four items below are things a person notices in the first
five seconds — the app stretched across a desktop with no phone frame, a Settings tab with no
settings in it, glyphs that do not read as what they are. The demo is in a day, and the first
impression of a medical app is part of whether it gets trusted.

What this spec is **not**: onboarding (its own spec — it is a flow, not a coat of paint) and the
Settings rebuild (also its own — the allergy-memory opt-in is a §2-5 feature with a consent
contract, not a toggle). Both are named here only to say they are deliberately elsewhere.

## Goals / non-goals

- **Goals:** the app reads as the designed mobile product on any screen; state and kind are legible
  at a glance and remain legible to someone who cannot distinguish colours; nothing hard-codes a
  colour that the dark theme then cannot reach.
- **Non-goals:** onboarding; the Settings rebuild; new features of any kind. No screen gains a
  capability in this spec — they only look like themselves.

## Requirements

### FR-001 — The app is a handheld, at every size it is looked at

The shell is centred and bounded, and the space around it is inert. Measured today: at 1600px the
chat composer is 1540px wide and the map fills the viewport — the wireframe is a 390px frame, and
every proportion in it (line length, tap target, card rhythm) assumes a hand.

The range we design for is **a narrow phone through to a 9-inch tablet**, and a desktop simply gets
the top of that range:

| | width | what it is |
|---|---|---|
| narrow phone | 320px | the floor. Nothing may overflow or clip here. |
| phone | 390px | the wireframe's own frame. The proportions are drawn for this. |
| tablet / desktop | **768px, centred** | the ceiling. A desktop shows this, framed by inert space. |

Not a hard 390: on a projector at the demo that reads as cramped, and the app is genuinely usable on
a tablet — a pharmacist could be handed one. Not a fluid full-width either: a 1600px line of body
text is unreadable, and every tap target the wireframe placed would be in the wrong place.

The bound lives on the **shell**, not on each screen, so no screen can opt out of it by accident.
Between 320 and 768 the layout is fluid; above 768 it stops growing and centres.

### FR-002 — Colour comes from tokens, and dark mode is already one of them

18 hard-coded hex values live in components today (`#c62828`, `#fdf1f1`, `#e0a800`, …). They were
written for the light frame and the dark theme cannot reach them: a dark-mode user gets a light
callout with light text, or a colour the theme never chose.

**Correction, 2026-07-15 — an earlier draft of this spec said astryx ships no dark tokens. It was
wrong, and the error was mine: I grepped for `prefers-color-scheme`, `.dark` and `[data-theme]`,
found none, and concluded there was no dark theme.** astryx uses none of those. It uses the CSS
`light-dark()` function — `--color-text-primary: light-dark(#171717, #fafafa)` — 96 times, driven by
`color-scheme`, which `index.css` already sets. **So we define no palette.** Every colour this app
needs already exists, dark included:

| meaning | Tailwind utility (all dark-aware) |
|---|---|
| danger — emergency, allergy **blocked** | `text-red-vivid` / `bg-red-subtle` / `border-red-ring` |
| caution — allergy **warning**/**unknown**, hours unknown | `text-yellow-vivid` / `bg-yellow-subtle` / `border-yellow-ring` |
| open — facility **open now** | `text-green-vivid` / `bg-green-subtle` / `border-green-ring` |
| closed / neutral | `text-secondary` / `border-strong` / `bg-muted` |

The work is therefore a replacement, not a design. Naver markers take an HTML string rather than a
class, so they carry `var(--color-red-vivid)` inline — the same token, resolved in the document.

Every colour that carries meaning becomes a token, and the meanings are the ones §2 already fixed:

| meaning | where it may be used | where it may **never** |
|---|---|---|
| danger / red | emergency, allergy **blocked** | anywhere else — red is not decoration |
| caution / amber | allergy **warning**, allergy **unknown**, hours unknown | a state we are confident about |
| open / green | facility **open now** | **anywhere near an allergy state** (§2-2) |

The last row is a safety rule with a colour attached, and this spec must not soften it: a
`no_match_found` allergy state gets no green, no badge, and no reassuring word, in either theme.

**Completion is machine-checked, not eyeballed.** A contrast scan over every rendered text node, in
light and dark, with **zero** failures — the same scan that found 21 failures on the wireframe,
including a grey that only failed in dark (2026-07-12). Eyeballing greys is a coin flip.

### FR-003 — Icons say what they are

Three map glyphs are hand-drawn inline SVG in `FacilityMap`, and the review of the design handoff
found them wanting:

- **Pharmacy** is a rotated square. It does not read as a pharmacy. → a half-filled capsule.
- **Emergency room** is a lightning bolt, which reads as *fast* or *electrical* before it reads as
  *emergency*. → a heart-pulse. This is the glyph a person looks for at 3am.
- **Hospital** is a cross, and a cross is right. Keep it.

The system around them does **not** change: **shape = kind, fill = state**, and the state also
carries a non-colour glyph (✓ open · ? unknown · ✕ closed). That pairing is why the map is readable
to someone who cannot distinguish red from green, and it is not a style choice.

For the rest of the interface (phone, search, close, chevron) we adopt **Lucide** (ISC — permissive,
compatible with our licence, tree-shaken). Hand-drawing them is what produced the mixed stroke
weights and corner radii the handoff review flagged. Naver markers take an HTML string rather than a
React component, so the three map glyphs are inlined from Lucide's path data, with attribution.

### FR-004 — Motion that says "working", and nothing else

The cold path exceeds 100 seconds. The waiting indicator is the only thing telling a person the app
is not broken, and the handoff review found its stripe animation stuttering. It becomes smooth, and
it stays honest: an indeterminate indicator, never a progress bar that implies a percentage we do
not know.

Motion is confined to: the waiting indicator, the bottom sheet, and tab/section transitions. Nothing
in a safety state animates — an emergency banner that slides is a banner that arrives late.

**`prefers-reduced-motion` is respected.** Someone who has asked their device for less motion is
often someone for whom motion is a symptom.

### FR-005 — The icons the wireframe places, in the places it places them

FR-003 said "icons come from one set". That closed the icons the app already had — it did not add the
ones the wireframe shows in places the app never had any. Worker A (#81) correctly replaced what
existed; a browser then showed the gap the replacement could not close.

The wireframe, read screen by screen, places an icon in three slots the app leaves empty:

| slot | wireframe | app |
|---|---|---|
| **bottom tab bar** | an icon above each of Chat / Map / Saved / Settings | text labels only |
| **emergency 119 action** | a phone glyph on the call link (`📞 Call 119`) | text only |
| **top-right header** | a small icon slot on every screen | only Chat's menu is present |

All added glyphs come from Lucide (FR-003's set). The tab icons are `MessageCircle` / `MapPin` /
`Bookmark` / `Settings`; the 119 link takes `Phone`. Every one is **decorative and `aria-hidden`** —
the label or the link text remains the accessible name, and an emergency action's prominence, colour,
href, and reachability may not change (§2-4).

The top-right slot on Map / Saved / Settings is **deliberately not filled**: its action is undefined,
and inventing one is a feature and a possible §2 risk, not appearance. The distinction this FR draws
is the one the worker got right and the brief got wrong — **add the icon the wireframe shows; never
add an icon it does not.**

Worker brief: [`docs/specs/worker-briefs/A1-icons-wireframe-parity.md`](worker-briefs/A1-icons-wireframe-parity.md).

## User scenarios

### Someone opens the app on a laptop at the demo (P1)
- **Given** a 1600px window
- **When** the app loads
- **Then** it renders as a centred phone-width product, not a stretched web page.

### Someone with red-green colour blindness looks at the map at night (P1)
- **Given** the map with open, closed and unknown facilities, in dark mode
- **When** they look at it
- **Then** kind is legible from the shape, state from the glyph, and no meaning rests on hue alone.

### Someone with an allergy reads a drug card in dark mode (P1)
- **Given** `no_match_found`
- **Then** there is no green, no badge, and no reassuring word — in dark mode exactly as in light.

## Success criteria

- **SC-001**: at **320px** nothing overflows or clips; at **390px** the wireframe's proportions hold;
  at **768px and above** the shell stops growing and centres. Asserted at all three widths, not one.
- **SC-002**: zero hard-coded hex values remain in `frontend/src/components`.
- **SC-003**: the contrast scan over every text node reports **0 failures in light and 0 in dark**.
- **SC-004**: the green token appears in no allergy surface — asserted by a test, not by a reviewer.
- **SC-005**: every icon in the interface comes from one set; the three map glyphs are capsule,
  cross and heart-pulse, each carrying its state glyph.
- **SC-006**: with `prefers-reduced-motion: reduce`, no animation runs.

## Verified before writing this (2026-07-14)

Both of the questions this spec would otherwise have opened were answered by opening the package,
not by assuming:

- ~~**astryx ships no dark tokens.**~~ **Wrong — corrected 2026-07-15.** The absence of
  `prefers-color-scheme`, `.dark` and `[data-theme]` was real; the conclusion drawn from it was not.
  astryx expresses dark through the CSS `light-dark()` function (96 tokens in
  `@astryxdesign/theme-neutral/dist/theme.css`), which needs none of those selectors. Verified by
  reading the file and by computed style in a browser. **The dark palette is not ours to define; it
  is already there.** Kept visible rather than deleted: a wrong "verified fact" that reached a spec
  is worth leaving on the page, because the next person will be tempted to re-derive it the same way
  — by grepping for the three selectors a design system is allowed not to use.
- **astryx ships no usable icon set.** `icons.mjs` is a 96-byte stub and `icons` is not in the
  package's `exports` at all. So the choice is Lucide or keep hand-drawing, and hand-drawing is what
  produced the mixed stroke weights. **Lucide.**

## Open questions

None blocking. (The width bound was the last one — ASQi called it: narrow phone through 9-inch
tablet, 768px ceiling, centred.)

## Future expansion

Onboarding (008) and the Settings rebuild (009) are the two screens this spec deliberately leaves
alone. Both are flows with their own contracts — the allergy-memory opt-in in particular is a §2-5
consent boundary, not a switch.
