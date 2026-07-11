# AGENTS.md

Operating instructions for everyone working in this repository — humans and AI agents follow the same rules. Each rule states what to do first and why second; a rule whose reason is unknown gets neither followed nor generalised.

> 한국어 요약과 빠른 시작은 [README.md](README.md)에 있습니다. 비개발자 팀원 안내는 [§12](#12-for-non-developer-teammates)를 보세요 — 번역기를 써도 좋고, 물어봐도 좋습니다.

If you find something here that is wrong, fix it and open a PR. Documentation goes stale faster than code.

---

## 0. Quick reference

```bash
./bin/setup.sh                       # hooks + .env + dependencies (once)
docker compose up -d                 # MariaDB + Redis

cd backend  && ./gradlew bootRun     # http://localhost:8080
cd frontend && pnpm dev              # http://localhost:5173
```

Before calling any work finished, run all three and watch them exit 0 **now**:

```bash
cd backend  && ./gradlew test        # 275 tests
cd frontend && pnpm test             # 58 tests
cd frontend && pnpm build            # includes tsc -b
```

Branches look like `feat/DEV-203-hospital-adapter`. Commits follow Conventional Commits, in English. Nobody pushes to `main` directly.

---

## 1. What this service is

An English speaker in Korea describes symptoms, without signing in, and gets:

1. medicine information **verified against government data** (식약처, 심평원, 국립중앙의료원), explained in English, and
2. a map of nearby pharmacies and hospitals that are open right now.

The word that matters is **verified**. The service never repeats what an AI happens to know. The AI does exactly two jobs: decide *which ingredients to look up*, and *explain in English the facts the server retrieved*. This is the **two-pass RAG**:

```
Browser ──(openai JS SDK, baseURL=/api/v1)──▶ Spring proxy ──▶ LLM
                                                  │
                                                  └──▶ 8 public government APIs
```

- **Pass 1a** — ask the LLM: "which *ingredients* should we look up for these symptoms?" → `["Acetaminophen", "Ibuprofen"]`
- **Pass 1b** — the **server** queries the 식약처 APIs with those names.
- **Pass 2** — give the LLM only the retrieved drugs: "explain these, and nothing else."

**Pass 1a's output is a query, not a fact.** If the model names a product the government API did not return, the server rejects the whole answer (post-processing invariant 6).

Read [`docs/specs/001-foundation/spec.md`](docs/specs/001-foundation/spec.md) before writing code — §2 (what changed from the original requirements and why) and §3 (verified external constraints) in particular.

---

## 2. Invariants

These are not style preferences. Each one marks a place where being wrong can hurt someone. If you believe one must change, open an issue first — do not route around it in code.

**2-1. Never diagnose.** Provide information and recommend professional care. The disclaimer is attached to every response and always visible on screen. The proxy discards any client-sent `system` message, so a user cannot prompt the model into playing doctor.

**2-2. `no_match_found` is not "safe".** Allergy checks have four states: `blocked` / `warning` / `no_match_found` / `unknown`. `no_match_found` means "no match in the ingredient list we hold" — a user allergic to ibuprofen sees exactly this for naproxen, even though NSAID cross-reactivity is common. We hold no cross-reactivity table and must not invent one without a clinical reviewer (AR-01). Therefore: no green badge, never the word "safe", and when an allergy is declared the model's own drug suggestions are discarded entirely (SA-08). Enforced by `frontend/src/components/AllergyBadge.test.tsx`.

**2-3. `isOpenNow: null` is not "Closed".** `null` means "we could not determine it" and renders as *Hours unknown*. No public API provides an open-now filter; we compute it from schedules, and a facility without a schedule is unknown, not closed. Rendering it "Closed" walks a sick person past an open pharmacy at night.

**2-4. Emergency triage runs before the model.** `EmergencyTriage` screens the user's text with rules *before* any LLM call; on a hit it answers from code (31 ms) and never calls the model. Reason: a live model answered `urgency: "unknown"` to *"crushing chest pain and I cannot breathe properly"*. A rule that fires only when the model says "emergency" catches nothing when the model doesn't.

**2-5. Consultation transcripts never persist.** Chat lives in `sessionStorage` and dies with the tab. Never write it to `localStorage` (outlives the tab, readable by anyone holding the device) and never to the server. The server DB holds only what the ERD shows: saved places, notification settings, and — with explicit consent — an allergy profile. Allergy memory is opt-in and **off by default**; when off, both read and write paths drop the list. Enforced by `frontend/src/lib/storage.test.ts`.

**2-6. Only a human fills a "a human checked this" field.** The `reviewer` column in `backend/src/main/resources/ingredients/synonyms.tsv` *is* the fact that a person looked. If an AI fills it, the column means nothing, permanently, for every future reader. Unsigned rows stay `blocked` (AR-02). PM/QA signs, using the prepared [review sheet](docs/specs/001-foundation/DEV-305-synonym-review.md).

**2-7. No secret ever carries a `VITE_` prefix.** Vite inlines every `VITE_*` variable **as a string literal into the shipped JavaScript** — not read at runtime, compiled in, public by definition. (A Naver Client Secret was compiled into `dist/` this way on 2026-07-10; it was rotated.) `vite.config.ts` now refuses to build when a `VITE_` name matches `SECRET|PASSWORD|PRIVATE_KEY|TOKEN|CREDENTIAL`.

| Name | Lives in | Public? |
|---|---|---|
| `VITE_NAVER_MAP_CLIENT_ID` | browser bundle | yes, by design |
| `NAVER_MAP_CLIENT_SECRET` | server only | **secret** |
| `DATA_GO_KR_SERVICE_KEY` | server only | **secret** |
| `LLM_API_KEY` | server only | **secret** |

**2-8. Never commit `.env`.** This repository is public; a leaked key is scraped within minutes. The pre-commit hook and CI's gitleaks are the last line of defence, not the first.

The hook only runs when `core.hooksPath` points at `.githooks`, and that setting lives in `.git/config` — per-clone, never committed. `pnpm install` sets it for you (`bin/install-hooks.mjs`, via `prepare`), so a clone that skipped `bin/setup.sh` is still guarded. Verify yours: `git config core.hooksPath` must print `.githooks`.

**2-9. Provenance is server-owned.** Every fact card carries a `sourceRef` (which agency, when). The server writes it; the model only references a `sourceRefId`. Fixture data is never presented as live — when `dataStatus` is `fixture`, the UI says so.

---

## 3. Running the project

**First time:** `./bin/setup.sh`, then fill `.env`:

| Key | Where | Trap |
|---|---|---|
| `DATA_GO_KR_SERVICE_KEY` | [data.go.kr](https://www.data.go.kr) | Use the **Decoding** key; the Encoding key yields `SERVICE_KEY_IS_NOT_REGISTERED_ERROR` |
| `VITE_NAVER_MAP_CLIENT_ID` | NCP console → Maps → Application | The **Client ID**, not the Secret. Register `http://localhost:5173` in the Web service URL allowlist |
| `LLM_API_KEY` | any OpenAI-compatible endpoint | |

**Every day:** `docker compose up -d`, then backend and frontend as in §0. Vite proxies `/api` to Spring, so the browser sees a single origin and CORS never enters the picture.

**Develop offline:** `DATA_MODE=fixture ./gradlew bootRun` serves real captured responses without touching the network. The pharmacy API allows **1,000 calls per day** — four people refreshing a map can spend that before lunch, so fixture mode is the default working style. Note that fixture mode **ignores query parameters**; test filtering logic with `hybrid` or unit tests.

**Check API access:** `./bin/check-api-access.py` — all 8 endpoints must print `[OK]`.

### On Windows

Everything works, with one rule: **run this repo's own scripts from Git Bash**, which ships with Git for Windows. PowerShell and `cmd` are fine for everything else.

| What you run | Where |
|---|---|
| `./bin/setup.sh`, `./bin/verify-api-doc.sh` | Git Bash (they are bash scripts) |
| `python bin/check-api-access.py` | anywhere — standard library only, but the `python3` shebang is a Unix thing, so name the interpreter |
| `gradlew.bat bootRun` | anywhere (`./gradlew` in Git Bash also works) |
| `pnpm dev`, `docker compose up -d` | anywhere |

Two traps we defused rather than documented, so you should never meet them:

- **Line endings.** Git for Windows rewrites LF to CRLF on checkout by default, and a shell script with CRLF dies on its own shebang: `bad interpreter: No such file or directory`. The pre-commit hook fails the same way, which would silently remove the secret guard. The root `.gitattributes` pins `*.sh`, `.githooks/*`, `*.py`, and `*.tsv` to LF. Don't remove it.
- **Symlinks.** `CLAUDE.md` used to be a symlink to this file. Windows checks a symlink out as a plain text file containing the target's path unless you have Developer Mode on, so it became a 9-byte file reading `AGENTS.md` — which Claude Code would then load as its entire instruction set. It is now a one-line `@AGENTS.md` import, which Anthropic recommends for exactly this reason. Don't reintroduce the symlink.

> **This path has not been exercised on a real Windows machine.** If you are the first to clone here on Windows, run `./bin/setup.sh` in Git Bash, then `cd backend && ./gradlew test` and `cd frontend && pnpm test`, and tell us what broke. Fixing it is a `fix(config)` commit and a favour to whoever comes next.

---

## 4. Definition of done

Done means the commands in §0 pass **at this moment**, evidenced by the runner's **exit code** — not by a report file, and not by memory of an earlier run. (A runner that failed to start leaves the previous run's reports on disk; reading them once produced a confident, wrong "222 tests passing".)

If you touched anything a browser renders, **open a browser and click it**. `curl` skips the entire client half — SDK constructors, bundling, CORS, script loaders — and every one of them can throw while the server answers 200. This repo's chat shipped with 275 green backend tests and had never once run in a browser.

---

## 5. Branches

```
<type>/<task-id>-<short-english-summary>
```

```bash
git switch -c feat/DEV-203-hospital-adapter
git switch -c fix/DEV-206-manual-location-entry
git switch -c docs/DEV-305-synonym-review-sheet
```

Always include the task ID from [`tasks.md`](docs/specs/001-foundation/tasks.md) — it chains WBS → branch → commit → PR, and **traceability itself is graded** (NFR-05).

### Lanes

Four people cover five lanes — BE-1 and FE-1 share an owner — split so work doesn't collide. Who owns which lane is in [`tasks.md` §1](docs/specs/001-foundation/tasks.md).

| Lane | Primary area | Example branch |
|---|---|---|
| **BE-1** | `backend/…/chat/`, `drug/`, `common/`, `config/` | `feat/DEV-102-answer-schema` |
| **BE-2** | `backend/…/facility/` | `feat/DEV-203-hospital-adapter` |
| **FE-1** | `frontend/src/App.tsx`, chat & drug-card components | `feat/DEV-308-drug-card` |
| **FE-2** | `FacilityMap.tsx`, `hooks/`, `lib/storage.ts` | `feat/DEV-207-detail-drawer` |
| **PM/QA** | `docs/`, `synonyms.tsv` review column | `docs/DEV-305-synonym-review` |

Talk to the owner before editing another lane's files. `frontend/src/lib/types.ts` and `backend/…/chat/dto/` are the FE–BE contract: change one side alone and the other renders fields that never arrive.

---

## 6. Commits

[Conventional Commits](https://www.conventionalcommits.org), in English:

```
<type>(<scope>): <what and why, lowercase, no period>
```

**Types:** `feat` (user-visible capability) · `fix` (bug) · `docs` · `test` · `refactor` (same behaviour, new shape) · `perf` (same behaviour, faster) · `chore` (build/config/deps)

**Scopes:** `chat`, `drug`, `facility`, `web`, `config`, `ci`, `docs`

Good, from this repo's own history:

```
perf(drug): fetch the ministry's APIs concurrently, not one at a time
fix(config): correct the 심평원 hospital endpoints, and tell 403 from 404
test(web): give the frontend its first tests, starting with the bugs that shipped
```

Weak: `fix: 버그 수정` (which bug? and in Korean), `update code` (no type, no information), `feat: WIP` (don't commit it, keep it on the branch).

The diff already says *what* changed. The subject line is for *why*.

---

## 7. Pull requests

- One PR, one concern, small enough to review in one sitting.
- **Before you open it, read your own diff against the [Review guidelines](#review-guidelines).** They are written for the author as much as the reviewer — the same P0/P1 lens, run by you first. This is the cheap catch: the reviewer and CI are the last line, not the first.
- Fill the [PR template](.github/pull_request_template.md) checklist **by actually checking each item**. Ticking an unverified box is the same lie as §2-6.
- Merge only on green CI (backend tests / frontend tests + typecheck + build / secret scan).
- Review for: does this route around a §2 invariant; does new logic have tests; if it diverges from the spec, was `docs/specs/` updated with it.
- "Why did you do it this way?" in review is a request for a comment or commit message, not an attack.

An automated Codex review runs on every pull request. To aim one at a specific worry, comment `@codex review for <what to look at>` — for example `@codex review for a §2 invariant routed around`. It reviews inside a container that can run our tests but cannot open a browser, call a public API, or reach the network at all; what that container is, and why it holds no secrets, is in [`docs/codex-cloud-environment.md`](docs/codex-cloud-environment.md).

---

## Review guidelines

*This heading is unnumbered on purpose: Codex looks for a section named exactly this, and applies the guidance from the `AGENTS.md` closest to each changed file. It surfaces only P0 and P1 findings, so the severity budget belongs on things that hurt someone.*

*One list, two readers. The reviewer runs it against your diff after you open the PR; you run it against your own diff before you do. They are not the same pass — you catch what you can see (a §2 invariant you knowingly touched, a test you didn't break to confirm), the reviewer catches what you're blind to (the logic error you'd not have written had you seen it). Doing both is not redundant; each layer stops a different class of mistake. Human reviewers read it too.*

**Everything in §2 is P0.** An invariant routed around in code is the highest-priority finding in this repository, above any bug.

Flag, in this order:

1. **A §2 invariant weakened or bypassed.** Anything that diagnoses rather than informs, or that lets a client-sent `system` message reach the model. A disclaimer that can be absent from a response or from the screen. Chat written to `localStorage`. `no_match_found` rendered as reassurance, or the word "safe" anywhere near an allergy state. `isOpenNow: null` drawn as "Closed". An LLM call placed before `EmergencyTriage`. A `reviewer` column filled by anything but a person. A secret behind a `VITE_` prefix, or a `.env` reaching a commit. A `sourceRef` written by the model rather than the server, or fixture data presented as live.
2. **A test that cannot fail.** A test asserting an operation the code under test never performs; a test whose assertion holds for both the correct and the broken implementation; a `toThrow()` that would pass for the wrong reason. Say which mutation should turn it red — if none would, the test is decoration. This repository shipped one: it asserted `new URL(path, baseURL)` while the SDK concatenates, so it stayed green while the client silently bypassed the `/api/v1` route.
3. **A claim the code does not support.** A comment, javadoc, README line, or WBS status describing behaviour that is not there. Doubly so for a measured number with no date, or a task marked done whose code does not exist. Stale documentation here has cost real afternoons.
4. **Public-API traps (§11) reintroduced.** `distance` parsed with the wrong unit for its agency. `type=json` where `_type=json` is required. A 404 read as "the service is gone" rather than "the operation name is wrong". A radius parameter omitted.
5. **Correctness where the language hides it.** A Java `record` cached into Redis's JDK serializer. A function passed to `Parallel.map` that can return `null` — `Mono.fromCallable` drops it and shifts every later index. `IllegalArgumentException` mapped to a client error.

**Treated as P1 in this repository.** Codex surfaces only P0 and P1 on GitHub — the filter is fixed, not something these guidelines can widen. But a concern classified here as P1 rides that channel, so a few things that are ordinarily P2/P3 are declared P1 *because of what this app is*. The test is one question: does it decide whether care information reaches someone who is unwell and reading in a second language?

- **Wrong or confusing English in a safety state** (error, blocked, emergency, empty). Ordinarily a P3 wording nit; here the reader decides whether to take a drug from it. A `no_match_found` or `blocked` message that reads as reassurance is P1.
- **An accessibility defect that hides safety information.** If the allergy badge or the emergency banner is unreadable to a screen reader or too low-contrast to see, the safeguard never reaches the person it is for. P1.
- **No loading or progress state on the cold path** (chat answers exceed 100s cold). Ordinarily a P2 UX gap; here the user reads a frozen screen as broken, leaves, and gets no information. P1.

Keep this list short. Everything escalated to P1 spends the signal budget the filter exists to protect; if it grows, that is the filter being defeated, not widened. A new entry earns its place only by passing the same question — would a maintainer block merge on it here?

Do not flag:

- Formatting, import order, or naming that matches the surrounding code (§8).
- Missing defensive checks for cases that cannot happen. We validate at system boundaries only, on purpose (§8).
- Korean prose in issue bodies, PR descriptions, or domain comments — that is the convention (§8).
- The mistakes preserved on purpose in commit messages and comments. They are documentation, not debt.
- Test count drift in prose. Assert against the runner, not the README.

**Worked examples — where the tiers actually land.** Each is a hypothetical pull request. The lesson is the boundary, so near-identical cases are paired: what separates them is the point.

| A pull request that… | Tier | Why |
|---|---|---|
| renders `no_match_found` as plain text, no badge, no "safe" | *none* | Correct (§2-2). The state is not the finding; the rendering is. |
| renders `no_match_found` with a green badge, or the word "safe" | **P0** | Reads as permission to take a drug the user may react to (§2-2). |
| reads `VITE_NAVER_MAP_CLIENT_ID` in browser code | *none* | Public by design — the client ID, not the secret (§2-7 table). Flagging it is a false positive. |
| puts a real secret behind a `VITE_` name (`…_SECRET`, `…_TOKEN`) | **P0** | Vite inlines it into the shipped bundle (§2-7). The build already refuses; the review says why. |
| asserts something that holds for both the correct and the broken code | **P1** | A test that cannot fail. Our chat shipped exactly this — it asserted a URL operation the SDK never runs. Name the mutation that should turn it red; if none would, it guards nothing. |
| replaces that with an assertion a real regression turns red | *none* | It now guards the invariant. |
| renders `isOpenNow: null` as "Closed" | **P0** | Walks a sick person past an open pharmacy (§2-3). "Hours unknown" is the correct label. |
| ships the chat's cold path (>100s) with no loading or progress state | **P1** | Escalated: the user reads a frozen screen as broken and leaves. A missing spinner on an instant local toggle is *none*. |
| writes its description, or a domain comment, in Korean | *none* | The convention (§8). Not every difference is a defect. |

When a finding is uncertain, say so, and name the single observation that would settle it. A confident wrong finding costs more than an honest "I could not tell".

---

## 8. Code style

**Match the surrounding code.** This one rule outranks everything below.

| | Backend (Java) | Frontend (TypeScript) |
|---|---|---|
| Classes & types | `PascalCase` — `DrugService` | `PascalCase` — `MermAidAnswer` |
| Methods & functions | `camelCase` — `retrieve()` | `camelCase` — `fetchFacilities()` |
| Constants | `UPPER_SNAKE` — `MAX_CONTEXT_DRUGS` | `UPPER_SNAKE` — `SEOUL_CITY_HALL` |
| Components / hooks | — | `FacilityMap.tsx` / `useNaverMap.ts` |

- Our JSON response bodies use `camelCase`; query parameters use `snake_case` (`radius_m`, `open_now`). Requests take `lat`/`lng`, responses answer `latitude`/`longitude` — the asymmetry is real and `bin/verify-api-doc.sh` asserts it, so don't "fix" one side.
- **Never rename fields the public APIs give us** (`XPos`, `dutyTime1s`, `MAIN_INGR_ENG`): parse them verbatim, translate names only when mapping into our domain types.
- Code, comments, commits, PR titles → **English**. Issues, PR descriptions, discussion → Korean is fine. Korean domain terms (심평원, 식약처, 수출용) are welcome in comments where they are the precise word.
- Validate at system boundaries only (user input, external APIs). Internal code trusts internal code; defensive checks for impossible cases cost every future reader five minutes of "when is this null?".

---

## 9. Comments

Write a comment only for what the code cannot show: **why it must be this way, and what breaks otherwise.**

```java
// ✅ a constraint the code can't express
/** Four, and raising it will not help. Measured 2026-07-10: four DUR calls take
 *  5.77s in sequence and 2.70s together — a 2.1× speed-up, not 4×. */
private static final int UPSTREAM_CONCURRENCY = 4;
```

```ts
// ✅ what breaks otherwise
// Naver markers are not React children. Nothing removes them for us, and a second
// render would silently stack a new pin on every old one.
markersRef.current.forEach((m) => m.setMap(null))
```

Worth writing down: measured numbers with their dates; approaches tried and abandoned, with the reason; public-API traps; safety rules and their grounds. Not worth writing: what the next line does, notes to the reviewer, changelog entries (`git log` remembers), commented-out code.

---

## 10. Tests

```bash
cd backend  && ./gradlew test        # 275
cd frontend && pnpm test             # 58   (pnpm test:watch while developing)
```

- New logic gets tests, without exception when it touches a §2 invariant.
- **A passing test proves nothing until it has failed.** After writing one, break the code it guards and watch it go red. Every frontend test file here was checked this way — move chat storage to `localStorage` and four tests fail immediately.
- **Never weaken, skip, or delete a test to make it pass.** If a test looks wrong, it might be — say so instead of fixing it silently. This repo once had a test named *"hospitals are not implemented yet and return nothing rather than lying"* whose protected behaviour (`200 []`) was itself the lie "no hospitals near you"; the truth was "we cannot look yet", and the endpoint now answers `501`.

---

## 11. Known traps

All of these were hit for real. The full list of seventeen, with the actual response samples, is in [`fixtures/README.md`](backend/src/main/resources/fixtures/README.md).

**Public APIs**
- The JSON-format parameter differs per agency: `_type=json` (약국, 심평원) vs `type=json` (식약처). One underscore, and you silently get XML.
- `distance` units are **opposite per agency**: 국립중앙의료원 (pharmacies) answers **km**, 심평원 (hospitals) answers **metres**. Copy one parser to the other and the radius filter is off by 1000×.
- **A 404 means the operation name is wrong, not that the service is gone.** `MadmDtlInfoService2.8/getDtlInfo` is 404; the real operation is `getDtlInfo2.8` — the version suffix appears on the service *and* the operation.
- Gateway codes: `401` = key unknown · `403` = key known, service unapproved · `404` = operation name wrong · `500 Unexpected errors` = service path itself doesn't exist.

**Naver Maps**
- The key parameter is `ncpKeyId`; the `ncpClientId` in older blog posts fails auth outright.
- **A wrong key still gets `maps.js` 200, `onload`, a defined `naver.maps`, and a constructed map** — only then does `navermap_authFailure()` fire. Register that callback *before* appending the script, or users read a blank grey box as "loading".

**LLM**
- Without an explicit User-Agent on `WebClient`, Cloudflare answers 403 to everything.
- `response_format` support varies per model: it is an allowlist in config, and a 400 triggers one retry without the schema.

**Spring**
- Redis's default serializer is JDK; Java `record`s are not `Serializable` and explode at runtime. Tests on `cache.type=simple` can never catch this.
- Don't map `IllegalArgumentException` to `INVALID_REQUEST`: Spring and Jackson throw it for their own bugs, and server errors get reported as the user's fault.

---

## 12. For non-developer teammates

You produce the artifacts that certify work is *done* — that's the PM/UX/QA lane, and it doesn't require writing code.

- **Synonym dictionary review** — the most urgent item. The [review sheet](docs/specs/001-foundation/DEV-305-synonym-review.md) is prepared so you only render a verdict. Read §2-6 first: this column is yours alone.
- **English copy** — every state (empty, error, loading, blocked) needs wording. A sick person reads it.
- **Fixture verification** — do the real responses in `backend/src/main/resources/fixtures/` render correctly on screen?
- **Bug reports** — one report with reproduction steps beats ten "it doesn't work"s. Include the `request_id` from the response (or the `X-Request-Id` header) so the log can be found.

You can edit files directly on GitHub in the browser: pencil icon → edit → choose "Create a new branch for this commit" → open PR. No terminal needed.

**Asking a question is the cheapest action on this team.** Thirty minutes stuck alone costs more than any question ever will.

---

## 13. Working with AI assistants

Rules the AI must follow, and guidance for the human steering it. Team members run different daily-driver models — most on the GPT family, some on Claude — so everything here is written to hold **regardless of which model sits in which seat**. The model-specific dials at the end distill the official guides for [Claude Fable 5](https://platform.claude.com/docs/en/build-with-claude/prompt-engineering/prompting-claude-fable-5) and the [GPT-5.6 family](https://developers.openai.com/api/docs/guides/latest-model).

### 13-1. Rules for any model working here

Everything above applies unchanged. In addition:

- **Never claim behaviour of code you haven't read.** Open the file first.
- **"Done" means verified now.** State the completion criteria as re-runnable commands up front, and re-run them at completion. The evidence is the exit code (§4).
- **Never conclude absence from truncated output.** A list you saw through `head` proves nothing about what it didn't show.
- **A 404 means "that name is wrong", not "it doesn't exist"** — and a wrong premise manufactures its own confirmation. Before concluding, ask "if I were wrong, what would look different?" and spend one more observation on exactly that.
- **If it has a browser, click it in a browser** before saying it works (§4).
- **Never fill the reviewer column** (§2-6) or any field meaning "a human checked this". Instead, gather the evidence that makes the human's check cheap.
- **Stay in scope.** Tests, docs, fixtures, and git history are edited only when they are the named target of the task. Never `git add -A` — unrelated work gets swept into commits (it happened here).
- **Report faithfully.** Failing tests are reported with their output; skipped steps are named as skipped. Audit each progress claim against an actual tool result before stating it.
- **Self-review before you open a PR.** Walk your own diff through the [Review guidelines](#review-guidelines) — the same P0/P1 lens the Codex reviewer will use — and fix what you find before pushing. It will not catch what you're blind to; that is the reviewer's job. It will catch the invariant you knowingly touched and the half of a pair you fixed while missing the other.

### 13-2. How to brief a model on a task

Both vendors' guidance converges on the same shape, so use it regardless of model:

1. **Give the goal and the reason, not a recipe.** State what you need, who it's for, and what the output enables — then let the model plan. Current frontier models degrade when every step is prescribed. *"I'm adding hospital search (DEV-203) so the map can show ERs at night. The adapter pattern is in `PharmacyApiClient`. Traps 12–17 in `fixtures/README.md` apply. Build the hospital adapter."* beats twenty numbered steps.
2. **Define the autonomy boundary per request.** Say what the model may do without asking (edit files in its lane, run tests) and what needs sign-off (schema changes, deleting anything, touching another lane). A model told its boundary continues safe work without pausing; one without a boundary either stalls on questions or overreaches.
3. **State each instruction once, and keep the prompt lean.** OpenAI measured leaner system prompts improving evaluation scores 10–15% while cutting tokens 41–66%; Anthropic reports over-prescriptive prompts written for older models actively degrade current ones. Point at documents (`AGENTS.md`, spec §3) instead of pasting their contents.
4. **Require evidence with the result.** End the brief with the completion criteria as commands: *"Done when `./gradlew test` and `bin/verify-api-doc.sh` pass — show the output."*
5. **Distinguish "assess" from "act".** If you're describing a problem or thinking out loud, say so — otherwise a capable model may implement a fix you only wanted diagnosed.

### 13-3. A working rhythm that needs no special tooling

For anything substantial, walk these phases in order. Each phase is just a prompt to your model plus a file in the repo — no custom skills or plugins required:

1. **Research.** *"Explore the code and docs relevant to X; report the constraints, options, and trade-offs. Don't implement."* Skipping this is how you get a clean solution to the wrong problem.
2. **Spec.** Have the model draft a one-page spec in `docs/specs/NNN-<feature>/spec.md` — goals, requirements, open questions — and review it *yourself* before any code exists. A wrong plan caught here costs a page; caught during implementation it costs days.
3. **Scaffold.** Skeleton plus test skeleton, no real logic, committed on its own. Every diff after this is small and readable.
4. **Implement & verify.** Tests alongside code; done per §4.
5. **Capture.** When something bit you, write it where the next person will look: a new trap into [`fixtures/README.md`](backend/src/main/resources/fixtures/README.md) or §11 here, a measured constraint into a comment with its date.

Collapse phases for small mechanical tasks. The rhythm is not ceremony — it moves the expensive mistakes to the cheap end.

### 13-4. Orchestrator and workers

Both vendors now recommend the same structure for larger work: one model **plans, dispatches, and verifies** (the orchestrator — normally your daily-driver model) while cheaper or parallel **workers** execute bounded tasks. Any pairing works — Claude orchestrating Codex workers, GPT-5.6 orchestrating its own subagents, or one model wearing both hats on a small task. The rules that keep it safe are pairing-independent:

- **Brief a worker like §13-2 says**: a bounded, self-contained task with completion criteria. Workers fill gaps by inventing; leave no gaps.
- **Commit the scaffold before dispatching**, so each worker's exact diff is auditable from git.
- **Route by cost the right way round**: planning, review, and safety-relevant judgment go to your strongest model; mechanical, well-bounded transforms go to the cheap ones — never the reverse.
- **The orchestrator — and ultimately you — owns the outcome.** Verify worker output with the §4 commands yourself; a worker's own "done" is a claim, not evidence.

### 13-5. Model-specific dials

**Claude (Fable 5 / Opus 4.8).** Effort is the main quality/latency/cost dial: `high` by default, `medium`/`low` for routine work, `xhigh` only for the hardest tasks. Long silent turns on hard problems are normal — check asynchronously rather than killing the run. The single highest-leverage line in a brief is the *why*. On long runs, add *"before reporting progress, audit each claim against a tool result from this session"* — Anthropic reports this nearly eliminates fabricated status reports. Don't ask it to transcribe its reasoning into the response (that can trigger refusal classifiers); read the structured thinking output instead.

**GPT-5.6 family (Sol / Terra / Luna).** Pick the tier by the job: `sol` for complex implementation and review, `terra` for cost-balanced daily work, `luna` for high-volume mechanical transforms. Start `reasoning.effort` one level *lower* than instinct suggests (`medium` is the balanced default) and raise it only on measured improvement — assuming maximum effort is best is the documented anti-pattern. Verbosity is a separate dial (`text.verbosity`), and 5.6 is already concise: don't stack extra brevity instructions, they over-truncate. Pro mode (`reasoning.mode: "pro"`) trades latency for one deeper answer — right for high-value review with clear criteria, wrong for iteration loops.

**Both families.** Leaner prompts measurably win: OpenAI reports 10–15% better evaluations with 41–66% fewer tokens from trimming system prompts; Anthropic reports over-prescriptive prompts written for older models actively degrade current ones. State each instruction once, point at documents instead of pasting them, and always give the autonomy boundary (§13-2).

### 13-6. Three rules when the agent writes the code

An agent amplifies skill in both directions: it also lets you ship a plausible PR you cannot explain. Three rules keep that from happening here:

1. **Understanding before output.** Your first task with an agent in a new area is to have it *explain* the code — then summarize it back in your own words. If you can't, you're not ready to change it.
2. **The PR description is yours.** Write what changed and why in your own language; a pasted agent summary is not a description, and reviewers treat it as an unreviewed change. This is a merge condition.
3. **A red test means the code is suspect, not the test.** Never let an agent weaken, skip, or delete a test to get green (§10). If you believe the test itself is wrong, say so to the lead before touching it.

---

## 14. When stuck

1. [`spec.md`](docs/specs/001-foundation/spec.md) §3 — verified external constraints
2. [`fixtures/README.md`](backend/src/main/resources/fixtures/README.md) — 17 traps found in real responses
3. Search the code for `TODO(team)` — the fill-in points are marked
4. Still stuck after 30 minutes? **Ask.** It's cheaper than the afternoon you're about to lose.

Found a bug? File it with the [issue template](.github/ISSUE_TEMPLATE/bug.yml): reproduction steps, expected, actual, evidence, and the `request_id`.

---

The mistakes made in this repository — and what they taught — are preserved in commit messages and comments on purpose. Please don't clean them up; they are there so the next person doesn't lose the same afternoon.
