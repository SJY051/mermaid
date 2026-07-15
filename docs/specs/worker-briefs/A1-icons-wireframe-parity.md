---
title: Worker A-1 — the icons the wireframe has and the app does not
status: ready
created: 2026-07-15
owner: 윤서진
spec: docs/specs/007-ui-appearance/spec.md (FR-003, extended)
verification: docs/specs/worker-briefs/VERIFICATION.md
base: main, after #81 (worker A) has merged — you build on its lucide-react and its tokens
branch: feat/DEV-007d-icons
---

# Worker A-1 — put the icons where the wireframe puts them

Worker A (#81) did the icon work its brief asked for: it **replaced** every hand-drawn `<svg>` with
Lucide. But "replace what exists" is not "add what the wireframe shows", and the wireframe shows icons
in places the app never had any. This brief is that gap, and only that gap.

**Read [`docs/specs/002-mobile-ui/wireframe-v2.html`](../002-mobile-ui/wireframe-v2.html) — open it in
a browser, do not skim the source.** It is the contract. Every icon you add must be one it shows, in
the place it shows it. Every VERIFICATION.md row applies.

---

## The census (I did this against the wireframe; you verify it, then implement it)

The wireframe was read screen by screen. These are the icon slots it has and the app is missing:

| slot | wireframe | app now | what to add |
|---|---|---|---|
| **Bottom tab bar** — each of Chat / Map / Saved / Settings | an icon **above** each label (`<div class="ic">`, all four tabs) | text only, no icon | one Lucide glyph per tab, above the label |
| **Top-right header** — every screen | a small icon slot (`<div class="icon">`) | Chat has `Ellipsis` (menu); Map/Saved/Settings have nothing | see "the top-right slot" below |
| **Emergency 119 action** | `📞 Call 119` — a phone glyph on the call action | text "Call 119", no glyph | a `Phone` glyph on the 119 call link (`MobileShell.tsx:102`, the emergency banner) |

Nothing else. The drug card, the allergy badge, the map legend, and the map markers already carry the
icons the wireframe asks for (worker A did those). **Do not add an icon the wireframe does not show** —
that is the mistake in the other direction, and VERIFICATION §3 forbids it.

## The four tab icons

`TabBar.tsx` today has `{ id, label }` and renders the label alone. Add a Lucide icon per tab, above
the label, matching the wireframe's layout. The glyphs, from Lucide, chosen to read at a glance:

| tab | Lucide icon | why |
|---|---|---|
| Chat | `MessageCircle` | it is a conversation |
| Map | `MapPin` | it is a map of places near you |
| Saved | `Bookmark` | the saved-places list |
| Settings | `Settings` | (the gear — Lucide's own `Settings`) |

- The icon is **decorative**: the label is the accessible name. Mark each icon `aria-hidden="true"` so
  a screen reader reads "Chat", not "message circle, Chat".
- The **active** tab's icon and label share the active colour the tab bar already uses — do not invent
  a new active treatment, reuse what `TabBar` does for the label today.
- Size and spacing come from the wireframe. Match it; do not guess a bigger icon because it looks nice.

## The top-right slot

The wireframe puts a small icon top-right on every screen. In the app that slot is the **conversation
menu**, and only Chat has it (`Ellipsis`, already Lucide — leave it). Map, Saved, and Settings show
nothing there today.

**Do not add a menu to Map / Saved / Settings.** The wireframe's slot on those screens has no defined
action, and inventing one is a feature, not appearance (out of scope, and a §2 risk if it touches
saved data). If the wireframe's top-right glyph on those screens is purely decorative, leave them as
they are and **say so in your report** — a slot with no behaviour is not an icon to add. This row is
here so you notice it and decide deliberately, not so you fill it.

## The 119 phone glyph

The emergency banner's call link (`MobileShell.tsx`, `href="tel:119"`, text "Call 119") gets a Lucide
`Phone` glyph before the text, as the wireframe shows (`📞 Call 119`).

- **This is a safety surface (§2-4).** The glyph is additive and decorative — `aria-hidden="true"`,
  the link's accessible name stays "Call 119". Do not change the text, the `tel:119` href, the
  colour, or the layout in any way that could make the action less prominent or slower to reach. An
  emergency action is the one place a cosmetic change can do harm.
- The disclaimer strip's "Emergency? Call 119" is **copy, not a button** — leave it exactly as text.
  Do not put a glyph there.

## Boundaries

- `TabBar.tsx`, `MobileShell.tsx` (the 119 link only), and their tests. Nothing else unless the census
  above names it.
- **No copy changes. No behaviour changes. No new dependency** — `lucide-react` is already here from
  #81.
- Every icon `aria-hidden`; every accessible name unchanged. A tab's name is its label; the call
  link's name is "Call 119".
- Tokens only. `pnpm contrast` stays at **0 failures** (an icon with poor contrast is still a
  contrast failure). Zero hard-coded hex.
- Do not touch chat, allergy, drug, facility, or grounding logic. This is icons and layout.

## The parity pass — the wireframe and the app, screen by screen

The icon census finds missing icons. It does **not** find a section the wireframe has and the app
dropped, a control in the wrong order, a filter segment that is not there. Those are structural gaps,
they are invisible to a "did I add the icons" check, and this is where they get caught. Worker A's own
report already flagged one — the emergency screen's nearest-ER line — so this is not hypothetical.

**Open the wireframe in a browser next to the running app, and walk all six screens side by side.**
The wireframe is [`docs/specs/002-mobile-ui/wireframe-v2.html`](../002-mobile-ui/wireframe-v2.html).
For each screen, compare **structure** — the sections present, their order, the controls, the copy
blocks — ignoring only the things this task and its siblings deliberately change (the replaced icons,
the tokenised colours). Aim: apart from those, the app reads as the same screen.

The six screens and what to check each carries:

| wireframe screen | structure to confirm present, and in order |
|---|---|
| **1–2 Chat** | header (title + top-right slot), the answer/drug-card area, the disclaimer strip, the composer, the tab bar. The waiting state (screen 2) shows the progress line + "Working…". |
| **3 Emergency** | the red 119 banner, "Call 119" action, **the nearest-ER line** (`nearest ER: … 1.2km — tap for map`), the source line, disclaimer, tab bar. |
| **4 Map** | filter segments **All / Pharmacies / Hospitals / ER**, the open-now toggle, the legend (✓ open · ? unknown · ✕ closed), the "Set your location" panel, the map. |
| **5 Saved** | the **＋ New conversation** control, the "this tab's conversation" note, the saved-places list with status dots, the on-device note. |
| **6 Settings** | Appearance / Language / Remember-my-allergies / About sections, in that order. |

**Report every difference you find — do not fix the structural ones.** Your task is icons; a missing
section or a reordered control is a *finding*, not a repair, and fixing it silently would sweep a real
gap under an "icons done" commit. List each one: what the wireframe has, what the app has, and which
screen.

**And do not mistake a deliberate deferral for a defect.** Several differences are agreed, in writing,
and must be reported as *expected*, not as gaps:

- **Hospitals / ER results are unavailable** — the backend does not serve them yet. The Map filter may
  therefore show fewer live results than the wireframe; the *segments* should still be present.
- **Settings controls are deferred to spec 009** — the Appearance toggle and the allergy opt-in are a
  separate build. Settings looking thinner than the wireframe is expected here.
- **Onboarding is spec 008**, not in the wireframe's six screens at all.
- **Saved deliberately refuses to show a stale "Open now"** from a stored snapshot (§2-3) — that is
  correct, not a difference to erase.

If you are unsure whether a difference is a deferral or a real gap, **say so and leave it** — a flagged
uncertainty is worth more than a confident wrong repair.

## Done means

The five commands in [VERIFICATION.md §1](VERIFICATION.md), plus `pnpm contrast` at 0, and a browser:

- All four tabs show their icon above the label, at **320 / 390 / 768**, light and dark. Screenshot.
- The active tab's icon reads as active. Switch tabs and show it.
- Trigger the emergency banner and show the `Phone` glyph on the 119 link — and confirm the link still
  reads "Call 119" to the accessibility tree (paste the a11y node).
- A screen reader pass, or the accessibility tree, showing every added icon is `aria-hidden` and no
  accessible name changed.
- **The census, answered**: for each row, say what you added or why you deliberately did not. The
  top-right-slot row in particular wants a decision, not a default.
- **The parity pass, reported**: all six screens walked against the wireframe, with a list of every
  structural difference found — each tagged **expected** (a named deferral above) or **gap** (report it,
  do not fix it). "Walked all six, no unexpected differences" is a valid result *only if you actually
  drove all six in a browser* and can show it. An empty parity report with no screenshots is not a pass.
