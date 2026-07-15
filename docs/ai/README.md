# Working with veyq

How we build veyq. Concise on purpose; this is the entry for every working session. The hard
invariants in [CLAUDE.md](../../CLAUDE.md) override everything here.

## What the docs are for — and who reads them

**Two audiences, and the project's owner is neither:**

- **The agent implementing** — needs the current state, stated **once**, precisely. Duplication is the tax it
  pays on every task: one rule (web-first) sits on 89 lines across 163 docs, and a guard was built to keep all
  89 citing the right ADR — instead of deleting 88 and linking.
- **Someone outside judging the project** — reads it on GitHub, for twenty minutes, and never installs an IDE
  plugin. A format only IntelliJ renders is a diagram nobody sees.

**The owner reviews in conversation, before the work — not by reading `docs/`.** So: describe what a refinement
or a CR is *actually about*, in two sentences, and let them stop it. That exchange is the review loop. It is the
only check that can ask **"do we need this at all?"** — no validator can, and its absence is how 33,000 lines of
prose accumulated against 28,000 lines of product, with 1 feature in the last 40 commits.

Five rules follow:

1. **Deleting beats reformatting.** Success is lines *removed*. Bullets vs. prose is roughly token-neutral;
   duplication is not. Never restructure what you could delete.
2. **Point at one canonical source; never restate it.** A restated list drifts from the one that runs — the CI
   job is the guard registry, the ADR owns its rule, the use-case owns its behaviour.
3. **No guard unless a human would notice the defect.** Ask what it *cost* before building one: three diagrams
   failed to render for 13 days at an observed cost of **zero**. Thirteen unnoticed days are evidence nobody
   looks — not evidence of urgency. The alternative to a guard is usually a format or structure that cannot
   break quietly.
4. **A claim nothing backs is a lie with a reason attached.** Coverage no CI runs, a diagram no reader can
   render, a work-order nobody reads. Cut it, don't annotate it.
5. **Git is not durable here.** At the v1 move to a fresh repo (`legacy/` splits off to its own archive), the
   history drops — an explicit choice, and a `git filter-repo`/`subtree split` move could keep it. Until that
   is decided, **"let git hold it" buys nothing past v1**: what must survive lives in the **working tree**, or
   is knowingly let go.

## State vs. flow

- **State** = what veyq *is* (durable truth):
  - `docs/architecture/`, `docs/crosscutting/`, `docs/features/<feature>/use-cases/`, `docs/decisions/`
    (internal engineering docs, **English**),
  - `contracts/` (OpenAPI + crypto vectors),
  - `wiki/` (the **published** end-user GitHub Wiki — plain language, **EN+DE**).
  - State is only updated — never a dumping ground for raw ideas.
- **Flow** = what we're *changing*: `docs/inbox.md` (raw topics) → `changes/` (change-requests).

## The pipeline

1. **Inbox** (`docs/inbox.md`) — a single status table of topics. Low friction. Seeded with
   pointers into `legacy/` so the target-picture work isn't lost.
2. **Refine** — pull a topic and refine it *with the user* into durable state. Always check the new
   fits the old: reconcile into existing crosscutting concerns and features/use-cases; never create
   orphan concepts. Durable decisions → an ADR in `docs/decisions/`. This is a sparring
   conversation, not transcription. **Scope each refinement** — don't refine all of crypto at once.
3. **Change-request** (`changes/cr-NNNN-slug.md`) — a self-contained, phased work order derived from
   the refined state. See [`changes/README.md`](../../changes/README.md). CRs are **kept** as a record
   of each piece of work; the durable engineering truth still lives in the updated state + ADRs.

> **Refined state comes before the CR — always.** A change-request is only written once the durable state it
> builds on already exists as written docs: its **use-case(s) (`uc.*`)**, **enabler(s) (`en.*`)**, **crosscutting
> concern(s) (`docs/crosscutting/`)**, and any governing **ADR(s) (`docs/decisions/`)**. A CR's `satisfies:` /
> `adrs:` must resolve to docs that are already there — **never invent the concept inside the CR**. The concept is
> decided in refinement (step 2, with the user); the CR only turns it into a work-order. And a CR grants no
> licence to build: **implementation waits for the user's explicit go-ahead on that CR.**

## Session topology — one phase per session

Refine and implement are **separate sessions**, and **refine runs in its own fresh session, up front** — never
tacked onto the tail of an implementation session. Refinement is the highest-leverage, cheapest-to-catch phase (a
design fault caught here is a doc edit; the same fault caught after multi-surface implementation is expensive), so
it must not run in the polluted, compressed context left over from implementing — its best conditions, not its
worst.

- **Refine session** — fresh context, one CR. Ground it by reading recent **retrospectives** + targeted subagent
  spikes over the surface; produce a tight CR (decisions + inventory + a Definition of done that carries the
  enforcement guard). Delegate broad reading to subagents so the main context stays lean.
- **Implement session** — fresh context; reads the refined CR + only what it points at. If a fresh session
  **cannot** proceed from the doc alone, the CR was under-refined — a cheap signal, fed straight back into the
  CR. Produce code + proof + a filled retrospective, and **close with an active process retro** (below).

**Same-session refine+implement is a named, risk-based exception — not the norm.** A **tightly-scoped, mechanical,
behaviour-preserving** CR backed by a **strong automatic gate** (e.g. `./gradlew check` over a broad slice-test
suite that pins the behaviour, as in cr-0161) may be refined and implemented in one session: the gate stands in
for the clean-room completeness proof (the fresh implementer would only have re-derived what the suite already
verifies). **Design-heavy, multi-surface, or novel-abstraction CRs — anything whose own doc is the main safety
net — stay split.** The warm refine context is precisely what the clean-room gate distrusts, so leaning on it is a
deliberate trade, not a free win; when in doubt, split. The exception never applies to the *other* direction —
**refine always runs fresh, never tacked onto an implementation's tail.** The buffer below keeps "split" from
meaning "wait": a CR refined 1–2 ahead starts the next session warm *from the doc*, not from zero.

**The closing process retro is mandatory and spoken, not just filed.** When implementation is done, before the
session ends, I proactively tell the user — in the conversation, not only in the CR's `## Retrospective` — **what
could improve about *how we work together*** (the Arbeitsweise): the process, the collaboration, the tooling, the
conventions we did or didn't honor. It runs **both directions** (my misses *and* the user's — a premature
instruction, a missing constraint, an internally-inconsistent CR) and **every time, even when the technical work
went cleanly** — a clean run still teaches process. Keep it 2–4 concrete, actionable points, and invite the
user's side. This is the compounding channel that makes the *next* refinement better; the filed retro is its
durable record, the spoken hand-off is what actually reaches the user.

Why the split holds:
- **The retrospective is the channel from implementation to the next refinement** — not a warm context window.
  Lessons an implementation surfaces (e.g. "an entity relocation touches every referencing unit *and* its tests")
  land in the CR's retro and are read by the next refine session, across the session boundary, in durable state.
- **Clean-room implementation is a quality gate.** "Always a new session" earns its keep because a fresh
  implementer *proves* the CR is complete; that proof is void if the same session refined it.
- **Keep a shallow buffer (1–2 refined CRs ahead), not a deep queue.** A CR refined far ahead goes stale as
  neighbouring code drifts; refine just ahead of need.

## Refinement checklist

A refined CR nails **decisions, not keystrokes**:
- **Decisions over keystrokes.** Fix the *what / why / shape* — does this belong in `shared`? which invariant is
  at risk? what is the Definition of done? Do **not** script line-level edits ("change line 46 of X"): prose can't
  compile or be tested, line numbers drift, and a `grep`/build finds the exact sites for free at implement time.
  Name the *inventory* (which files/surfaces, as a table) + the *decisions*; implementation discovers the exact
  *where* (which line, which site), but any **count** in the inventory is a cited command's output, not an estimate
  (see [`changes/README.md`](../../changes/README.md)). **The inventory greps the *test* trees too, not just
  source** — a symbol move/rename's blast radius includes every test that references it: cr-0160's guessed "~8
  cross-slice imports" was really +2 same-package users **and 22 test files**, a gap a `grep -rc` at refine would
  have closed instead of the build.
- **Spike when the shape is unclear.** The right abstraction often only emerges from N real usages (that is why
  `shared/membership/MembershipStore` came out clean — 5 usages, not a guess). If refinement can't settle the
  shape from reasoning, run a time-boxed subagent spike over the surface and fold the inventory back in — don't
  guess an abstraction into the CR.
- **Carry the enforcement guard into the Definition of done.** If the CR rests on a structural invariant ("no
  duplication of X", "Y lives only in `shared/`"), its one-shot proof grep becomes a **permanent** guard — see
  [`changes/README.md`](../../changes/README.md).

## Two documentation audiences

- **Internal** (`docs/`): precise, English, for devs + agents. The technical security model lives here.
- **Published** (`wiki/`): plain language, **EN+DE**, for the **non-technical end user** who wants to
  know *what they can do* and *why their data is safe*.
- **Single source, derive the wiki.** Each use-case carries a short plain-language
  "What this means for you / Was das für dich bedeutet" section (EN+DE); the wiki is assembled from
  those + the plain-language safety pages. Never maintain a parallel page tree by hand. (The Python
  generator is deferred; the section is authored from day one so it's free later.)

## Parity & verification surfaces

**The coverage surfaces are `backend` and `web` — that is the whole set.** CR phases follow the dependency
order **contracts → backend → web**.

The rule underneath, from [ADR 0040](../decisions/0040-coverage-gaps-and-http-not-a-surface.md): **a surface
that no CI job runs makes no coverage claim.** It is not about which directories exist — it is about what a
green build actually proves. Two surfaces have failed that test:

- **`http/`** was IntelliJ requests fired by hand. ADR 0040 stopped counting it; it has since been **deleted**.
- **`apple/` and `android/`** hold parked code ([ADR 0043](../decisions/0043-web-first-v1-native-parked.md)).
  No CI job builds or tests them, so **by the same rule their tests are not coverage** — however real the code
  is. They re-enter the surface set at an explicit un-park, together with the net ADR 0010 owes then.

**Do not add a surface back to the coverage model before its CI job is green.** The claim follows the build,
never the intention.

## Working rules

- **Sparring partner, not yes-man.** Critically question proposals — the user's and my own — surface
  disadvantages, risks and simpler alternatives before accepting. Only the hard invariants are off the table.
- **Structured over fast.** There is **no time pressure to ship something demoable.** Approach each topic
  structured from the start: build the enabler/foundation properly and slice for *soundness*, not for the
  earliest visible result. A throwaway stopgap to "see something" is the wrong trade here — prefer doing it
  right (e.g. the media derivatives before the gallery). Correctness + clean structure first; speed-to-demo
  is not a goal.
- **Language.** Converse with the user in the language they use; **document and implement in English**
  (docs, code, identifiers, commits). The published `wiki/` is the intentional bilingual EN+DE exception. German
  user-facing text follows [neutral-first inclusive language](../crosscutting/cc.inclusive-language.md).
- **Diagrams as inline Mermaid.** Where a diagram genuinely clarifies, embed it as a ` ```mermaid `
  fenced block directly in the markdown. Diagrams-as-code only — no binary image files. Skip diagrams that
  are mere decoration. **Mermaid, not PlantUML, because GitHub renders it** — and `docs/` is read by people
  judging the project there, who cannot install an IDE plugin. A format only IntelliJ renders is a diagram
  nobody sees: all three diagrams in `architecture/README.md` were syntactically broken from their first
  commit and nobody noticed for 13 days. Mermaid covers every kind veyq draws (sequence, state, component,
  flow); its `%%{init}%%` styling is not worth using — the defaults theme themselves, and the one PlantUML
  construct used everywhere (`skinparam`) is what broke. Two characters must be escaped in label text:
  **`;` → `#59;`** (it separates statements → parse error) and **`#` → `#35;`** (it opens an entity → the
  rest of the label is swallowed **silently**, with no error at all).
- **Scripts and utilities in Python.** Bash only for thin CI/hook glue.
- **Durable state is edited in place — no addendums.** State every doc (ADRs, use-cases, enablers, crosscutting)
  as it is **now**; when a decision changes, **edit the doc**. Never accrete `Amended: <date>` lines or
  `## … addendum <date>` sections — a dated change-log is not state. Canonical rule:
  [`../README.md`](../README.md) (§ current-state) + [`../decisions/README.md`](../decisions/README.md).
  **But do not offload the *reason* to git** (§ What the docs are for, rule 5 — history drops at the v1 move).
  The test is not "old vs. new", it is **"does this still bind a future decision?"**: if a change leaves a live
  obligation, that obligation *is* current state and stays in the doc — as the obligation, not as a diary entry.
  ADR 0010 is the worked example: it does not say *"we removed the `http/` clause on 2026-07-14"*; it says the
  drift net is gone, that one live surface is why that is survivable, and that **a replacement is due at the
  native un-park**. The date is noise; the debt is state. Where nothing binds, let it go.
- **`legacy/` is read-only reference.** Never edit it. The inbox may point to it as source material,
  but durable state must not depend on or link to it.
- **Frontends reuse the existing UI/UX — never invent new UI.** Port the design/UX and change only the
  underlying data layer (contracts-generated clients + client-side crypto). **Web design source =
  `legacy/frontend-noe2ee/`** (the pre-E2EE original, `0075f77c` — the clean product UX; see its
  `_PROVENANCE.md`). `legacy/frontend/` is the **E2EE-but-wrong-concept** descendant — consult it **only** as a
  reference for *how an operator-blind server forces a flow client-side* (breadcrumbs, name-decrypt,
  thumbnail-less gallery), never for its scope/key concept. **Native reuses its own legacy UI the same way:**
  Apple ports the **first iOS UI** (`legacy/ios/`) **component-for-component** — copy the SwiftUI views, swap
  only the data layer to the E2EE client (exactly the web approach); Android is built **fresh in parallel** to
  Apple (no legacy Compose UI to copy) matching the same screens. **Both native heads are responsive universal
  apps:** Apple adapts across **iPhone / iPad / macOS** (size classes — compact ↔ regular, multi-column on
  regular), Android across **phone / tablet** (WindowSizeClass). Design for the **size class**, not a fixed
  width — the sidebar/detail split collapses to a stack on compact and expands to columns on regular.
- **Start light.** Heavier machinery — validators, hooks, the wiki generator, the full `contracts/`
  tree, `.claude/` commands + subagents — is added when there is code/feature surface that justifies
  it, not up front.

## When to add a tool (so we don't over-build)

Two rules under everything: **pain before tool** (no tool until there's a felt cost) and
**rule of three** (a manual action repeated three times is a candidate to automate). I monitor against
these within a session and *propose* — I don't add tooling silently. Concrete triggers:

| Tool | Trigger |
|---|---|
| graphify on `legacy/` | I run 2–3 broad Explore sweeps over legacy in one refine session |
| graphify on veyq | code exists across ≥3 surfaces and tracing one use-case through them gets lossy |
| trust-chain validators | the first use-case + first test exist (something to link) |
| an invariant guard | a CR removes a duplication or establishes "X only in Y" — promote its one-shot proof grep to a permanent check |
| a hook | a validator/formatter exists and we've run it manually a few times |
| a slash command | the same prompt framing has been typed ~3 times |
| split the inbox | it needs scrolling to navigate |
