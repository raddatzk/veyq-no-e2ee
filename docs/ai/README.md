# Working with veyq

How we build veyq. Concise on purpose; this is the entry for every working session. The hard
invariants in [CLAUDE.md](../../CLAUDE.md) override everything here.

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

## Two documentation audiences

- **Internal** (`docs/`): precise, English, for devs + agents. The technical security model lives here.
- **Published** (`wiki/`): plain language, **EN+DE**, for the **non-technical end user** who wants to
  know *what they can do* and *why their data is safe*.
- **Single source, derive the wiki.** Each use-case carries a short plain-language
  "What this means for you / Was das für dich bedeutet" section (EN+DE); the wiki is assembled from
  those + the plain-language safety pages. Never maintain a parallel page tree by hand. (The Python
  generator is deferred; the section is authored from day one so it's free later.)

## Parity & verification surfaces

A feature/use-case is "covered" across **three client surfaces**: `web · apple · android` (plus
`backend`). `http/` holds IntelliJ `.http` files with response-handler assertions — a **dev aid** for
firing requests by hand, **not** a coverage surface: they run in no CI job, so the coverage trust chain
does not count them ([ADR 0040](../decisions/0040-coverage-gaps-and-http-not-a-surface.md)); the same
behaviour is proven by the backend slice tests. CR phases follow the dependency order:
**contracts → backend → web → apple → android** (a `.http` file may still be written alongside the
backend as a manual smoke, but it is not a phase gate).

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
- **Diagrams as inline PlantUML.** Where a diagram genuinely clarifies, embed it as a ` ```plantuml `
  fenced block directly in the markdown (IntelliJ renders it in the preview). Diagrams-as-code only —
  no binary image files. Skip diagrams that are mere decoration.
- **Scripts and utilities in Python.** Bash only for thin CI/hook glue.
- **Durable state is edited in place — no addendums.** State every doc (ADRs, use-cases, enablers, crosscutting)
  as it is **now**; when a decision changes, **edit the doc** and let **git** hold the history. Never accrete
  `Amended: <date>` lines or `## … addendum <date>` sections — a change-log belongs in git, not the doc. Canonical
  rule: [`../README.md`](../README.md) (§ current-state) + [`../decisions/README.md`](../decisions/README.md).
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
| a hook | a validator/formatter exists and we've run it manually a few times |
| a slash command | the same prompt framing has been typed ~3 times |
| split the inbox | it needs scrolling to navigate |
