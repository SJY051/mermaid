# mermAid Repository Threat Model

This document is the canonical repository-scoped threat model for the immutable
Git tree identified in the footer. It synthesizes the eighteen independent
worker threat models listed in the source index and the repository-specific
security policy in `AGENTS.md`. It deliberately does not validate, disposition,
or rank any scan candidate. Statements about controls describe intended or
reported control surfaces that later phases must verify on every reachable
branch.

## Overview

mermAid is a public, login-free medical-accessibility web application for
English-speaking people in Korea. A React/Vite browser client sends symptom,
allergy, location, and anonymous-profile requests to a Spring Boot service. The
service performs deterministic emergency screening, asks an OpenAI-compatible
model for retrieval terms, retrieves medicine and facility data from Korean
government and Naver services, and then allows the model to explain only the
records the server retrieved. MariaDB stores deliberately limited opt-in profile
state, Redis caches public-data responses, and the browser loads the Naver Maps
SDK. [R1-W1, R2-W4, R3-W2]

The defining security property is integrity, not chatbot helpfulness. A model
selection is a query, not a fact. Product identity, ingredients, warnings,
allergy state, opening-hours certainty, provenance, and live-versus-fixture
status must remain bound to server evidence. A failure can affect a person who
is unwell and reading in a second language, so medical-safety controls and the
availability of care information are security assets alongside privacy and
credentials. `AGENTS.md:33-52,58-87` is authoritative for this product shape and
its non-negotiable invariants. [R1-W3, R2-W1, R3-W6]

### Primary runtime surfaces

- The React application under `frontend/src/`: chat and drug-card rendering,
  allergy and emergency states, facility maps, browser geolocation, browser
  storage, the same-origin OpenAI SDK client, and the Naver Maps loader.
- Anonymous Spring endpoints and services under
  `backend/src/main/java/com/mermaid/{chat,drug,facility,profile,config,common}`:
  chat completion, emergency/allergy preprocessing, two-pass retrieval,
  structured-output handling, grounding, validation, drug/ingredient lookup,
  facilities, geocoding, and anonymous profile CRUD.
- Outbound clients for an operator-selected OpenAI-compatible endpoint, Naver
  geocoding, and the government medicine/facility APIs.
- MariaDB/JPA/Flyway persistence for anonymous device profiles, opt-in allergy
  memory, and saved facilities; Redis serialization and caching for public API
  data.
- Runtime and build configuration in `backend/src/main/resources`,
  `frontend/vite.config.ts`, environment variables, and deployment topology.
  [R1-W2, R2-W6, R3-W1]

Repository hooks, setup scripts, package lifecycle scripts, CI workflows,
dependency manifests, migrations, schemas, prompts, fixtures, and synonym-review
data are privileged developer or supply-chain surfaces. Tests, docs, generated
copies, fixtures, and nested worktrees are not independent production services
merely because they are tracked; they matter only when loaded by runtime,
packaged into shipped artifacts, executed with developer/CI privilege, or
carrying secrets or authoritative policy. [R1-W4, R2-W5, R3-W5]

### Assets and required security properties

1. **Medical-safety integrity.** Emergency triage, non-diagnostic framing,
   allergy blocking and uncertainty, product identity, ingredients,
   directions/dose semantics, warnings, facility-opening status, disclaimers,
   and safety copy must not be invented, downgraded, hidden, or misbound.
   [R1-W1, R2-W3, R3-W1]
2. **Grounding and provenance integrity.** A rendered fact must correspond to
   the exact server-retrieved record. `sourceRef`, retrieval time, agency,
   `dataStatus`, and fixture/live distinction are server-owned. Provider text
   and model output remain data, not authority. [R1-W6, R2-W5, R3-W2]
3. **Privacy and consent.** Symptom conversations, allergy declarations,
   approximate/current/manual location, address searches, country, aliases,
   memos, and saved places can reveal health or movement information even
   without a legal identity. Transcripts have the strictest lifetime: per-tab
   `sessionStorage` only, never server persistence or `localStorage`. Allergy
   memory is explicit opt-in and off by default. [R1-W3, R2-W2, R3-W6]
4. **Anonymous-profile isolation.** A browser-generated `deviceId` is a
   bearer-like capability, not an authenticated identity. It selects the
   limited profile aggregate; every allergy/favorite child operation must stay
   inside that aggregate and consent state. [R1-W6, R2-W4, R3-W4]
5. **Credentials and economic authority.** `LLM_API_KEY`,
   `DATA_GO_KR_SERVICE_KEY`, `NAVER_MAP_CLIENT_SECRET`, database/Redis
   credentials, CI/deployment credentials, and the spend or quota they authorize
   must remain outside public bundles, logs, errors, Git history, and
   attacker-selected destinations. The browser Naver client ID is public by
   design. [R1-W5, R2-W2, R3-W3]
6. **Availability and operational integrity.** Request threads, outbound
   connections, memory, database/cache capacity, LLM budget, and scarce public
   API quotas must remain available to people seeking care information. Cold
   chat and multi-provider facility paths make fan-out, cancellation, timeout,
   and high-cardinality abuse material. [R1-W2, R2-W3, R3-W4]
7. **Control and attestation integrity.** Server prompts, schemas, validators,
   synonym-review metadata, cache types, migrations, source references, request
   correlation, CI, and hooks influence what is trusted or shipped. In
   particular, the synonym `reviewer` field is human attestation and cannot be
   manufactured by automation. [R1-W4, R2-W5, R3-W6]

## Threat Model, Trust Boundaries, and Assumptions

### Actors and input ownership

#### Remote unauthenticated user or abusive client

The remote actor can bypass the React UI and directly control HTTP methods,
paths, query parameters, headers, body size and nesting, OpenAI-shaped message
arrays, roles, user and assistant content, stream selection, structured
`mermaid` allergy extensions, drug/facility identifiers, coordinates, radii,
filters, address queries, `deviceId`, allergy/favorite child identifiers,
country, aliases, memos, consent mutations, concurrency, replay, races, and
cancellation timing. TypeScript types, widgets, a dummy browser Authorization
header, and same-origin proxying are not enforcement controls. [R1-W4, R2-W6,
R3-W3]

#### Browser-local or same-origin actor

A user controlling their browser, a malicious extension, an injected same-origin
script, a compromised npm dependency, or the Naver SDK can read and mutate
`sessionStorage` and `localStorage`, obtain the device capability, access current
transcripts and opt-in allergy state, use granted geolocation, and issue
same-origin API mutations. A person with physical access to an unlocked device
has similar local reach. This is stronger than an ordinary cross-origin web
attacker and must be stated as a precondition. [R1-W3, R2-W6, R3-W1]

#### LLM provider/model

The provider controls response status, latency, JSON/prose shape, schema-valid
but unsafe values, suggested terms, and error content. Its behavior is
attacker-influenced through user/assistant history and through retrieved public
data inserted into context. It is untrusted for facts, provenance, safety
policy, emergency decisions, and output structure even when it follows the
prompt or schema. [R1-W1, R2-W3, R3-W5]

#### Government and Naver services

These are authoritative sources only for the facts they actually return; their
network responses are untrusted parser and rendering input. Payloads, record
identities, strings, units, schemas, encodings, content types, redirects, status
codes, latency, size, freshness, and availability can be malformed,
instruction-like, stale, or inconsistent. [R1-W6, R2-W4, R3-W2]

#### Operator/deployer

The operator controls secrets, upstream base URLs, model/allowlists, data mode,
timeouts, database/Redis endpoints, origin registration, logging, TLS/ingress,
egress, and network exposure. These are privileged administrative inputs, not
ordinary HTTP inputs. Misconfiguration can nevertheless collapse boundaries by
publishing secrets, redirecting sensitive text/credentials, exposing datastores,
or mislabelling data. [R1-W5, R2-W2, R3-W3]

#### Developer, contributor, dependency, and CI actor

Developers and CI control source, dependencies, prompts, schemas, migrations,
fixtures, synonym-review data, Vite substitution, package lifecycle scripts,
hooks, workflows, and generated artifacts. A malicious contribution or
compromised dependency is a privileged supply-chain input when a normal
developer/CI workflow executes it. An actor who already has unrestricted host,
production-secret, database-administrator, or source-merge authority is outside
the ordinary remote model unless code gives a lesser actor that authority.
[R1-W2, R2-W1, R3-W5]

### Trust boundaries

1. **Anonymous browser/direct client to Spring API.** All caller-supplied data
   crosses an Internet boundary with no account login or general authorization
   layer. Spring binding, Bean Validation, explicit parsing, object scoping,
   medical invariants, and resource budgets are the first enforceable controls.
   [R1-W4, R2-W1, R3-W3]
2. **Conversation to deterministic emergency and allergy gates.** Raw current
   and historical text plus structured exclusions must be interpreted before
   model-selected medication work. Equivalent wording, malformed shapes,
   unresolved terms, and stale state must not silently bypass fail-closed
   behavior. [R1-W5, R2-W6, R3-W2]
3. **Spring to LLM provider.** Server credentials, system instructions,
   conversation data, and retrieved context leave the service. The outbound
   projection must remain server-owned and minimal; the returned object remains
   hostile until coerced, grounded, and validated. [R1-W1, R2-W3, R3-W1]
4. **Model/external records to user-visible medical answer.** This is the
   central integrity boundary. The object released and rendered must be the
   object actually validated. Product/source/ingredient joins, allergy state,
   warnings/directions, urgency/actions, fixture/live state, and disclaimer must
   receive appropriate deterministic controls on JSON, SSE, fallback,
   clarification, emergency, and error branches. [R1-W4, R2-W5, R3-W4]
5. **Spring to government/Naver services.** Operator-owned destinations and
   credentials meet attacker- or model-influenced query values. URI encoding,
   redirect/final-destination policy, timeout/cancellation, response bounds,
   parser behavior, row identity, units, cache keys, provenance, and fallback
   semantics preserve the boundary. [R1-W3, R2-W2, R3-W2]
6. **Bearer `deviceId` to profile aggregate.** Possession selects a profile but
   does not authorize crossing to another aggregate. Child IDs, consent state,
   concurrent updates, and deletion must stay bound to the selected capability.
   Root capability confidentiality, entropy, format/length, leakage, rotation,
   and enumeration behavior affect practical risk. [R1-W6, R2-W4, R3-W4]
7. **Spring to MariaDB and Redis.** External/client-origin strings and public
   data become persistent state or serialized cache objects. Parent ownership,
   consent, parameterized queries, type restrictions, key isolation, TTL,
   data-mode/provenance binding, and datastore network isolation must survive
   this crossing. [R1-W3, R2-W1, R3-W1]
8. **Browser runtime to DOM, storage, device APIs, and Naver SDK.** Model,
   provider, profile, and locally restored values enter text, URL/action,
   attribute, marker/popup, script, and storage contexts. React text escaping is
   context-specific mitigation, not universal trust. The map SDK executes with
   first-party page privilege. [R1-W3, R2-W5, R3-W6]
9. **Configuration/build to shipped application.** Every `VITE_*` value enters
   public JavaScript. Environment values, bundles, source maps, logs, errors,
   container configuration, and generated files cross a publication boundary.
   [R1-W6, R2-W2, R3-W4]
10. **Repository input to developer/CI execution.** Hooks, setup/check scripts,
    Gradle/pnpm lifecycle code, workflows, code generators, filenames,
    environment values, and delegated-tool arguments cross into a privileged
    workstation or runner. [R1-W4, R2-W6, R3-W1]

### Non-negotiable safety invariants

The following are authoritative repository policy from `AGENTS.md:58-87`. A
later finding phase must treat any routed-around §2 invariant as repository P0,
regardless of whether a generic security taxonomy would describe the mechanism
as logic, privacy, configuration, availability, or presentation.

- Do not diagnose. Every response retains non-diagnostic framing and a visible
  disclaimer. Client-supplied `system` policy does not reach the model.
- Preserve allergy states `blocked`, `warning`, `no_match_found`, and `unknown`.
  `no_match_found` never means safe or reassures about cross-reactivity. When an
  allergy is declared, model-origin drug suggestions are discarded as required
  by the fail-closed policy.
- Preserve `isOpenNow: null` as “Hours unknown,” never “Closed.”
- Run `EmergencyTriage` on user text before every LLM call. A match is answered
  from server code and does not co-render distracting model medication advice.
- Keep consultation transcripts in per-tab `sessionStorage` only. Do not write
  them to `localStorage` or server persistence. Allergy memory is opt-in,
  default-off, and dropped on both read and write paths when disabled.
- Only a human may populate metadata meaning “a human reviewed this.” Unsigned
  synonym rows do not gain reviewed authority.
- No secret may carry a `VITE_` prefix, and `.env` is never committed.
- Provenance is server-owned. The model may refer only to source IDs for records
  the server retrieved; fixture data is visibly fixture data.

Repository review policy additionally treats wrong/confusing English in a
safety state, accessibility defects that hide allergy/emergency information,
and absence of loading/progress on the long cold path as P1 because they can
prevent care information from reaching the intended user
(`AGENTS.md:205-227`). This is a review-priority rule, not a validation result.

### Assumptions and scope limits

- Anonymous use is intentional. The lack of login is not itself a finding.
  `deviceId` possession is an accepted capability model only while the ID is
  high entropy and undisclosed, stored data stays deliberately limited, and
  child operations cannot cross aggregates. [R1-W1, R2-W4, R3-W2]
- Production TLS, ingress/WAF rate limits, CSP, CORS/origin policy, header
  rewriting, egress filters, source-map policy, MariaDB/Redis firewalling,
  backups, and datastore authentication are not established by this repository
  and must not be assumed. Local Compose port bindings are not proof of
  production exposure. [R1-W3, R2-W2, R3-W4]
- Operator-selected upstream base URLs are trusted administration in the normal
  remote model. A request-time SSRF story requires a lower-privilege path that
  alters scheme, authority, redirect destination, DNS resolution boundary, or
  credential destination. [R1-W5, R2-W1, R3-W5]
- Redis is assumed private and writable only by trusted services. Polymorphic
  deserialization is a privileged boundary whose severity depends on a
  realistic attacker-write or exposed-deployment path. [R1-W3, R2-W4, R3-W1]
- Government and Naver data are authoritative for returned facts, not trusted
  executable instructions or guaranteed well-formed structures. Provider
  compromise is in scope only where local parsing, prompt separation,
  grounding, rendering, or resource controls fail to contain it. [R1-W6,
  R2-W3, R3-W2]
- Provider-side retention and processing of sent consultation text are outside
  repository control and remain an external-policy unknown. The server-side
  requirement is to minimize and correctly classify what it sends. [R1-W3]
- Local physical/device compromise, a fully malicious extension, root/host
  compromise, unrestricted operator sabotage, and malicious database
  administration exceed the ordinary application boundary unless code grants
  that reach to a lesser actor or unnecessarily amplifies the compromise.
  [R1-W1, R2-W6, R3-W3]
- Clinical cross-reactivity absent from the repository and correctness of human
  clinical judgments are not facts the software or this model may invent. The
  software obligation is to preserve the documented uncertainty and
  human-attestation boundary. [R2-W3, R3-W2]
- No primary runtime upload/archive ingestion, general user-selected URL fetch,
  shell runner, plugin loader, server template engine, or arbitrary filesystem
  API is identified by these source models. Those vulnerability families need
  concrete contrary reachability, not keyword inference. [R1-W4, R3-W1]
- Tests, docs, fixtures, generated output, and nested worktrees are not runtime
  surfaces unless shipped, loaded, or executed with privilege. [R1-W2, R2-W5,
  R3-W1]

### Reconciliation notes and unresolved disagreements

The synthesis preserves these differences rather than choosing a finding-level
conclusion:

- **Artifact identity.** Worker footers alternate between repository path,
  target hash, immutable Git revision, and one snapshot digest. This canonical
  artifact uses the scan target ID and immutable Git revision required by the
  scan contract. The disagreement is documentary, not semantic. [R2-W1,
  R2-W2, R3-W1, R3-W3]
- **Public-API timeout coverage.** Some models list public-client timeouts as an
  existing mitigation; others state that the shared public-API client lacks
  connect/response/read/total coverage. Timeout behavior therefore remains
  unknown until code and runtime validation settle every client. [R2-W5,
  R3-W1, R3-W3, R3-W4]
- **Availability severity.** All models recognize anonymous cost/quota/resource
  abuse. Some place it at High when modest effort reliably exhausts shared care
  capacity; others place it at Medium absent demonstrated service-wide reach.
  Severity depends on amplification, attacker cost, cancellation, external
  containment, and shared impact. [R2-W1, R2-W3, R3-W2, R3-W4]
- **Bearer-profile severity.** All models agree that `deviceId` is possession
  authority. Pure guessing of a high-entropy ID is treated as unlikely or an
  accepted limitation; practical leakage/enumeration, sensitive contents, or
  cross-aggregate child access materially raises impact. [R1-W1, R1-W6,
  R2-W4, R3-W4]
- **CSRF relevance.** Traditional cookie/ambient-authority CSRF is less central
  because there is no login session. Cross-origin mutation remains relevant if
  a capability is ambiently exposed, a browser can send a meaningful request,
  origin policy is permissive, or future authentication introduces cookies.
  [R1-W2, R1-W6, R2-W6, R3-W1]
- **Same-origin deployment.** Some models assume production preserves the
  development single-origin shape; others explicitly leave production ingress,
  CORS, CSP, and edge controls unknown. Cross-origin deployment requires a new
  explicit authorization, CORS, CSRF, and privacy model. [R1-W5, R2-W2, R3-W3]
- **Generic security severity versus repository priority.** Source models use
  Critical/High/Medium/Low based on reach and consequence. `AGENTS.md` separately
  declares every §2 bypass P0 and specific care-delivery failures P1. Both axes
  must be reported without translating one mechanically into the other.

## Attack Surface, Mitigations, and Attacker Stories

### Chat, emergency, allergy, and model-output pipeline

`POST /api/v1/chat/completions` accepts complex anonymous JSON and can trigger
deterministic triage, search-term extraction, multiple public-data lookups, and
one or more provider calls. Realistic abuse classes include:

- smuggling privileged or privileged-looking instructions through roles,
  multipart/nested content, assistant history, structured extensions, or fields
  that the server should own;
- direct prompt injection from user/history and indirect prompt injection from
  government/provider strings inserted into privileged model context;
- emergency wording or message shapes that evade deterministic pre-model
  screening, or later branches that reintroduce model participation;
- incomplete, malformed, stale, or differently normalized allergy state that
  becomes reassurance or re-enables model-selected medicines;
- schema-valid but semantically unsafe output, fallback parser differences,
  null/duplicate/type confusion, product/source/ingredient misbinding,
  fabricated provenance, diagnosis, cure/safety language, directions/dose,
  urgency, actions, links, or markup;
- validating one object but returning/rendering another, or emitting SSE/JSON
  content before the same complete grounding and validation boundary;
- oversized/deep histories, repeated cold requests, response parsing, long
  provider waits, disconnected clients, or request fan-out exhausting memory,
  threads, outbound capacity, spend, or quota. [R1-W1, R2-W4, R3-W1]

Intended or reported controls include a positive/rebuilt outbound request,
server-pinned model/credentials/system context, privileged-role filtering,
disabled provider tools, `store: false`, output-token and LLM-time bounds,
pre-model `EmergencyTriage`, allergy clarification/fail-closed retrieval,
`StructuredOutputFallback`, exact-record grounding, server replacement of
provenance/allergy/direction fields, `AnswerValidator`, safe fallbacks, and one
complete post-validation SSE chunk. Later validation must confirm exact ordering,
field coverage, and parity across every sibling branch. [R1-W6, R2-W5, R3-W4]

### Drug, facility, geocode, public API, and cache pipeline

Anonymous callers control medicine terms, exclusion sets, facility/product IDs,
coordinates, radius, type/open filters, limits, and address queries. Model output
also influences drug lookup terms. External responses then cross parsers,
normalizers, joins, caches, facility schedules, model context, and UI cards.
Relevant attacker stories include:

- query/path smuggling, double encoding, request-derived destination or redirect
  escape, DNS/final-destination confusion, and credential forwarding;
- wrong JSON-format parameters, XML/JSON/content-type confusion, entity or
  decompression hazards where parsers permit them, malformed numeric values,
  unexpectedly large collections, and deterministic parser failure;
- response-row or fixture identity not matching the requested product,
  mixed-product joins, wrong agency distance units, schedule mistakes,
  `null`-to-closed conversion, and source/data-mode laundering;
- cache-key omissions, cross-mode or cross-request collisions, high-cardinality
  key growth, poisoned serialized objects, stale provenance, and data returned
  under a query it did not satisfy;
- unique requests, per-result detail fan-out, stalled upstreams, retries, and
  quota exhaustion that disable facilities or medicine verification;
- sensitive address, coordinates, provider URIs, or service keys entering logs,
  errors, redirects, or client responses. [R1-W3, R2-W2, R3-W2]

Reported mitigations include typed/fixed base URLs, centralized parameter
encoding, provider-specific adapters, Bean Validation and explicit query/radius/
result bounds, response memory caps, concurrency controls, caches/TTLs,
fixture/hybrid modes, server-owned source records, generic error envelopes, and
sanitized geocode logging. Exact redirect behavior, response/content bounds,
per-client timeout/cancellation, cache keys, row binding, and error redaction are
explicit verification unknowns. [R1-W5, R2-W4, R3-W4]

`AGENTS.md:308-328` records safety-significant semantic traps: `_type=json`
versus `type=json`; pharmacy kilometres versus hospital metres; a 404 meaning a
wrong operation name rather than absence of a service; delayed Naver
authentication failure after a successful script load; model-dependent
`response_format`; Redis serializer/record incompatibility; and server bugs
misclassified through `IllegalArgumentException`. These are repository-context
failure classes, not findings asserted by this threat model.

### Anonymous profile, consent, and persistence pipeline

Public routes under `/api/v1/profiles/{deviceId}` read and mutate country,
allergy consent and rows, and favorite facilities with aliases/memos. Plausible
abuse includes capability guessing, theft, replay, leakage in URLs/logs/referrers
or browser state, response-difference enumeration, cross-profile child-ID
mutation, mass assignment, race/lost-update behavior, reappearance of deleted
allergies, consent bypass, stored-content injection, identifier truncation or
collision, and unbounded anonymous row growth. [R1-W2, R2-W6, R3-W4]

Reported controls include browser UUID generation, DTO/Bean Validation, JPA
parameterization, profile-aggregate child lookup/removal, foreign keys and
uniqueness constraints, transactions, explicit opt-in, and deletion/drop of
allergy state on withdrawal. They limit neither root-capability disclosure nor
anonymous storage abuse by themselves. [R1-W6, R2-W3, R3-W3]

### Browser rendering, storage, maps, and device capabilities

The frontend renders model-adjacent summaries, server warnings, product and
facility cards, profile strings, restored storage, errors, source metadata, and
opening/allergy/emergency states. It loads Naver JavaScript and can receive
geolocation. Relevant stories include DOM or map-popup injection, dangerous URL/
action schemes, raw marker HTML, script callback/order mistakes, malicious
third-party code, stored-state forgery, runtime-shape crashes that remove the
whole safety UI, transcript migration into durable storage, stale facility
snapshots treated as current, device-capability leakage, and browser permission
use without explicit application-level intent. [R1-W3, R2-W6, R3-W1]

React text escaping, explicit marker escaping/structural construction,
same-origin API routing, schema/version storage checks, `sessionStorage` chat,
opt-in allergy persistence, display-only saved snapshots, Naver auth-failure
callbacks, and public/private key separation are reported controls. Every raw
HTML/DOM, URL/action, attribute, script-loader, marker/popup, error, and restored
state sink still requires context-specific verification. [R1-W1, R2-W5, R3-W2]

### Secrets, logs, errors, deployment, and supply chain

Attackers may seek credentials or sensitive medical/location text in public
bundles, source maps, Git history, `.env`, generated artifacts, provider URLs,
redirects, exceptions, stack traces, application/access logs, request IDs,
actuator responses, CI logs, dependencies, and locally published MariaDB/Redis.
Repository contributors may target package install/prepare scripts, setup and
check utilities, Git/Codex policy hooks, workflow interpolation, shell/argument/
path/symlink handling, or dependency execution with developer/CI privilege.
[R1-W4, R2-W3, R3-W5]

Reported defenses include server-only typed properties, `.env` ignore rules,
pre-commit and gitleaks scanning, a Vite secret-name refusal, request correlation
without intended body logging, generic controlled error envelopes, limited
health/info exposure, pinned CI actions and restricted permissions, and fixed-
argument subprocess use where present. Naming heuristics and uninstalled hooks
are defense in depth; they do not make a value public-safe or an arbitrary
repository script trusted. [R1-W2, R2-W4, R3-W2]

### Lower-relevance or conditional vulnerability classes

- Traditional account takeover, role escalation, and session fixation are less
  applicable because the service has no accounts or server login sessions.
  Capability theft, child-object scoping, and persistent-state consent are the
  corresponding concerns.
- Cookie-based CSRF is conditional, but cross-origin persistent mutation can
  matter if capability or ambient authority is sent or inferred.
- Classic request-time SSRF is lower likelihood while destinations are fixed
  and request values are encoded data. Redirects, DNS/final-destination policy,
  mutable configuration, or a request-selected URL change that conclusion.
- Raw SQL injection is lower likelihood while JPA-derived/parameterized queries
  are used. Native/dynamic queries or attacker-controlled selectors reopen it.
- Upload traversal, archive extraction, template injection, command injection,
  and arbitrary file access require a concrete runtime or privileged workflow
  source-to-sink path because no general public surface is assumed.
- Generic missing headers, local-only defaults, cosmetic UI, or a version string
  alone are not material without a repository-specific medical, privacy,
  credential, object-isolation, availability, or privileged-workflow impact.
  [R1-W6, R2-W1, R3-W1]

## Severity Calibration (Critical, High, Medium, Low)

This section calibrates vulnerability classes only. It does not rank any current
candidate. Repository P0/P1 review priority remains the separate authoritative
axis described above.

### Critical

- Unauthenticated remote code execution, arbitrary host control, or a normal
  contributor/dependency path to broad production/CI compromise.
- Broad reusable theft of LLM, government, Naver, database, CI, or deployment
  credentials with infrastructure-scale consequence.
- A systemic, deterministic, remotely exploitable failure that causes dangerous
  attacker/model-authored treatment, allergy, emergency, or provenance content
  to appear server/government verified at scale with a credible severe-harm
  path, especially while defeating multiple independent safeguards.
- Unsafe deserialization, injection, or privileged-tool abuse that crosses an
  ordinary public boundary into equivalent infrastructure control.
  [R1-W4, R2-W3, R3-W1]

### High

- A reliable normal-path bypass of emergency, allergy, grounding, provenance,
  dose/product binding, or fixture/live controls that can present materially
  unsafe medical guidance.
- Practical scalable cross-profile disclosure or mutation of opt-in allergy or
  other sensitive/safety-critical state through enumeration, leakage, or broken
  parent-object binding.
- SSRF to cloud metadata/internal control planes or credential forwarding;
  meaningful SQL/query/command/template injection; arbitrary deployed file
  access; remotely reachable unsafe deserialization; or stored/broad XSS that
  steals capability/medical state or changes safety UI.
- Cheap, repeatable anonymous amplification that reliably exhausts shared LLM
  spend, government quota, request pools, or care-information availability with
  modest attacker effort and no effective external containment.
  [R1-W1, R2-W5, R3-W4]

### Medium

- One-profile disclosure or mutation requiring prior theft of a high-entropy
  bearer capability, without a scalable acquisition path.
- Bounded or interaction-dependent XSS/content injection, consent or location
  privacy loss, or log/error leakage of health-adjacent data without broader
  compromise.
- Deterministic malformed-request/provider parsing failures, stalled-response
  resource occupation, sustained quota amplification, or cache/database growth
  that affects requests/workers or an important product path but is not a cheap
  service-wide takeover.
- Single-key, bounded, or time-limited cache/provenance/open-hours confusion, or
  a safety-state defect requiring substantial upstream/deployment/user
  preconditions and constrained by independent warnings.
- Privileged build/deployment weakness requiring a realistic but non-default
  developer/CI invocation or misdeployment.
  [R1-W3, R2-W2, R3-W6]

### Low

- Non-sensitive implementation or version disclosure, harmless verbose errors,
  or low-amplification failures without medical, privacy, credential, object, or
  shared-availability consequence.
- Local-development defaults without a plausible production or credential-
  bearing path, and tooling weaknesses requiring an already fully privileged
  actor.
- Self-only storage corruption or transient request failure that cannot cross
  into server-verified facts, hide safety information, or affect another user.
- Defense-in-depth gaps where an exact independent control defeats the complete
  attacker story.
  [R1-W2, R2-W6, R3-W5]

### Source index

- `[R1-W1]` `artifacts/deep_discovery/round-01/worker-01/threat_model.md`
- `[R1-W2]` `artifacts/deep_discovery/round-01/worker-02/threat_model.md`
- `[R1-W3]` `artifacts/deep_discovery/round-01/worker-03/threat_model.md`
- `[R1-W4]` `artifacts/deep_discovery/round-01/worker-04/threat_model.md`
- `[R1-W5]` `artifacts/deep_discovery/round-01/worker-05/threat_model.md`
- `[R1-W6]` `artifacts/deep_discovery/round-01/worker-06/threat_model.md`
- `[R2-W1]` `artifacts/deep_discovery/round-02/worker-01/threat_model.md`
- `[R2-W2]` `artifacts/deep_discovery/round-02/worker-02/threat_model.md`
- `[R2-W3]` `artifacts/deep_discovery/round-02/worker-03/threat_model.md`
- `[R2-W4]` `artifacts/deep_discovery/round-02/worker-04/threat_model.md`
- `[R2-W5]` `artifacts/deep_discovery/round-02/worker-05/threat_model.md`
- `[R2-W6]` `artifacts/deep_discovery/round-02/worker-06-valid/threat_model.md`
- `[R3-W1]` `artifacts/deep_discovery/round-03/worker-01/threat_model.md`
- `[R3-W2]` `artifacts/deep_discovery/round-03/worker-02/threat_model.md`
- `[R3-W3]` `artifacts/deep_discovery/round-03/worker-03/threat_model.md`
- `[R3-W4]` `artifacts/deep_discovery/round-03/worker-04/threat_model.md`
- `[R3-W5]` `artifacts/deep_discovery/round-03/worker-05/threat_model.md`
- `[R3-W6]` `artifacts/deep_discovery/round-03/worker-06/threat_model.md`
- `[POLICY]` `AGENTS.md:33-87,205-227,308-328` at immutable revision
  `654f906e00e81648d1482210b6a9171747dddd75`

Repository: target_sha256_3b79a0a9591bbdee6ac51053b05ea9ecc32c6b6d7bb58211be3c77de70ea2356
Version: 654f906e00e81648d1482210b6a9171747dddd75
