---
title: Verification sheet — every worker, every frontend task
status: active
created: 2026-07-15
owner: 윤서진
applies_to: every worker brief that touches frontend/src
---

# Verification sheet

**Every worker brief links here, and no worker's task is done until every row below has an answer with
its evidence attached.** "I checked" is not an answer. A command and its output is an answer; a
screenshot is an answer; a named file and line is an answer.

This exists because a worker's "done" is a claim. Two of them, in one week, came back green and were
not: one silently reverted an invariant the reviewer had already forced us to fix, and one shipped a
guard whose tests passed with the guard removed. Neither was caught by reading the summary. Both were
caught by running one command the summary had not run.

> **Rule zero — report faithfully.** A row you could not complete is reported as *not done*, with the
> reason. It is never reported as passing, and it is never quietly dropped from the list. An honest
> "I could not open a browser" costs an hour. A "verified" that was not costs the afternoon someone
> else spends discovering it.

---

## 1. The commands, re-run at the end

Not remembered from earlier — **re-run at completion, and paste the exit codes.** A runner that fails
to start leaves the previous run's reports on disk, and reading them once produced a confident, wrong
"222 tests passing" in this repository.

```bash
cd backend  && ./gradlew test          # exit 0
cd frontend && pnpm test               # exit 0
cd frontend && pnpm exec tsc -b        # exit 0
cd frontend && pnpm build              # exit 0
cd frontend && pnpm lint               # exit 0
```

- [ ] All five exit 0, **now**, pasted.
- [ ] If a count moved, say by how much and why. A test that disappeared is a finding, not a rounding.

## 2. Every new guard has a test that can fail

For each check, guard, or invariant you added: **break it, watch the test go red, restore it, and say
which test went red.** A test that passes for both the correct and the broken implementation guards
nothing; this repository has shipped one.

- [ ] Named mutation → named test that turned red. One line each.
- [ ] Choose the mutation that *substitutes* the wrong value, not one that deletes the call. Deleting
      the call is the easy mutation and it is the one that lies: an invariant here passed a
      delete-the-call mutation while a swap-the-value mutation left the whole suite green.

## 3. The wireframe is the contract, in both directions

The wireframe is [`docs/specs/002-mobile-ui/wireframe-v2.html`](../002-mobile-ui/wireframe-v2.html) —
six screens: chat (allergy), chat (waiting), emergency, map, Saved, Settings (rendered in dark).

- [ ] **Nothing in the UI that the wireframe does not have**, unless a spec says so *in writing*. List
      anything you found that is on screen but not in the wireframe, and say which spec authorises it.
      If no spec does, it is a finding — report it, do not delete it and do not keep it silently.
- [ ] **Nothing deliberately different without saying so.** Where the implementation departs from the
      wireframe on purpose (a spec required it, or you judged it necessary), name the departure and the
      reason, in one line each. An undeclared departure is the same defect as an undeclared addition.
- [ ] **No text you invented.** Copy on this app is read by someone who is unwell, in a second
      language, deciding whether to take a drug. Do not add a helpful sentence, a tooltip, a
      placeholder, or an empty-state line that no spec or wireframe asked for. If a state genuinely
      has no copy, that is a finding — raise it, do not fill it.

## 4. It runs in a browser, and you drove it

`curl` skips the entire client half — SDK constructors, bundling, env-var inlining, CORS, script
loaders — and every one of them can throw while the server answers 200. This repo's chat shipped with
275 green backend tests and had never once run in a browser.

- [ ] Dev server up, the **actual user flow** driven end to end (not a unit test, not a mock).
- [ ] `read_console_messages` — **zero errors.** Paste them if there are any.
- [ ] Screenshot of the finished screen(s).
- [ ] If something could not be exercised live (a slow provider, a missing key), **say precisely what
      was not exercised**, and do not describe the rest as end-to-end.

## 5. Every width, and both themes

The app is a handheld at every size it is looked at ([007] FR-001): fluid from **320px**, drawn for
**390px**, bounded and centred at **768px**, framed by inert space above that.

- [ ] **320px** — nothing overflows, nothing clips, no horizontal scroll on `body`. Check every tab.
- [ ] **390px** — the wireframe's own proportions hold.
- [ ] **768px** and **1600px** — the shell stops growing and centres; overlays (`fixed`) stay inside
      it. A `fixed inset-0` drawer is exactly how a screen escapes this bound, and one did.
- [ ] **Light and dark.** Not "it looks fine" — run the scan:

```bash
cd frontend && pnpm contrast     # exit 0 = zero WCAG AA failures, light AND dark
```

Contrast is machine-decidable, so eyeballing it samples what a computer can census. 4.2:1 and 4.5:1
are indistinguishable by eye, and a colour that fails only in dark is invisible on a light frame.

## 6. Icons come from the named set, and read as what they are

One set for the whole interface: **Lucide** (ISC — permissive, tree-shaken). No hand-drawn SVG; hand
drawing is what produced the mixed stroke weights and corner radii the design review flagged.

- [ ] Every icon imported from `lucide-react`. **Zero hand-authored `<svg>` paths** in
      `frontend/src/components` — grep and paste the result.
- [ ] The three map glyphs, which are inlined from Lucide's path data because Naver markers take an
      HTML string rather than a React component:

  | kind | glyph | not |
  |---|---|---|
  | pharmacy | half-filled **capsule** | a rotated square |
  | hospital | **cross** | (a cross is right — keep it) |
  | emergency room | **heart-pulse** | a lightning bolt, which reads as *fast* or *electrical* before it reads as *emergency* |

- [ ] **Shape = kind, fill = state**, and the state also carries a **non-colour glyph** (✓ open · ?
      unknown · ✕ closed). This pairing is why the map is readable to someone who cannot distinguish
      red from green. It is not a style choice and it may not be simplified away.

## 7. The §2 invariants, read against your own diff

Before you report done, walk your diff through [AGENTS.md §2](../../../AGENTS.md#2-invariants) and the
Review guidelines. The ones a frontend change actually endangers:

- [ ] **§2-1** the disclaimer is present, on every screen you touched, in every state.
- [ ] **§2-2** no green, no badge and never the word **"safe"** anywhere near an allergy state.
      `no_match_found` is not reassurance.
- [ ] **§2-3** `isOpenNow: null` renders as **"Hours unknown"**, never "Closed".
- [ ] **§2-5** chat stays in `sessionStorage`. Nothing consultation-shaped reaches `localStorage`, and
      no user text reaches a log line.
- [ ] **§2-9** provenance is the server's. Fixture data is labelled as fixture, never as live.
- [ ] **Silence is not safety.** Wherever a value is missing, the UI *says so*. A blank where a warning,
      a dose, or an opening time belongs reads as "there is nothing here to worry about" — the same
      trap as `no_match_found` read as "safe". Every empty state you can produce has words in it.

## 8. Scope

- [ ] You touched only what the brief named. Tests, docs, fixtures and git history are protected —
      edited only when they are the explicit target.
- [ ] No `git add -A`. Stage the files you changed, by name.
- [ ] No new dependency unless the brief names it.
- [ ] `.env` is not in the diff, and no key or secret appears in any file, log line, or commit message.

---

## What to hand back

A short report, in this order:

1. **What you did** — one paragraph, in your own words.
2. **The five commands and their exit codes**, pasted, from the final run.
3. **Mutations** — one line each: what you broke, what went red.
4. **The wireframe rows** — additions, declared departures, and any copy you had to invent (if any).
5. **Browser** — screenshot(s), console output, the widths you drove.
6. **What you did NOT verify**, and why. This section is never empty by default and never a formality;
   if it is genuinely empty, say that explicitly.
