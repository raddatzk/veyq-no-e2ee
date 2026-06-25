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
   the refined state. See [`changes/README.md`](../../changes/README.md). CRs are **temporary**:
   deleted when done; the durable trace is git history + the updated state + ADRs.

## Two documentation audiences

- **Internal** (`docs/`): precise, English, for devs + agents. The technical security model lives here.
- **Published** (`wiki/`): plain language, **EN+DE**, for the **non-technical end user** who wants to
  know *what they can do* and *why their data is safe*.
- **Single source, derive the wiki.** Each use-case carries a short plain-language
  "What this means for you / Was das für dich bedeutet" section (EN+DE); the wiki is assembled from
  those + the plain-language safety pages. Never maintain a parallel page tree by hand. (The Python
  generator is deferred; the section is authored from day one so it's free later.)

## Parity & verification surfaces

A feature/use-case is "covered" across **four client surfaces**: `http · web · apple · android`
(plus `backend`). `http/` holds IntelliJ `.http` files **with response-handler assertions**, so it is
a real contract-level verification surface, not docs that rot. CR phases follow the dependency order:
**contracts → backend → http → web → apple → android**.

## Working rules

- **Sparring partner, not yes-man.** Critically question proposals — the user's and my own — surface
  disadvantages, risks and simpler alternatives before accepting. Only the hard invariants are off the table.
- **Language.** Converse with the user in the language they use; **document and implement in English**
  (docs, code, identifiers, commits). The published `wiki/` is the intentional bilingual EN+DE exception.
- **Diagrams as inline PlantUML.** Where a diagram genuinely clarifies, embed it as a ` ```plantuml `
  fenced block directly in the markdown (IntelliJ renders it in the preview). Diagrams-as-code only —
  no binary image files. Skip diagrams that are mere decoration.
- **Scripts and utilities in Python.** Bash only for thin CI/hook glue.
- **`legacy/` is read-only reference.** Never edit it. The inbox may point to it as source material,
  but durable state must not depend on or link to it.
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
