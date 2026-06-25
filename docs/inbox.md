# Inbox

The Sammelbecken: every topic and idea that still needs handling. Low friction — drop a row,
refine later. Status flow: `inbox → refining → refined → planned → in-progress → done`.

Rows are seeded as **pointers into `legacy/`** so the previous target-picture work isn't lost.
When a topic is refined, its ideas move into durable state under `docs/wiki/` (+ `contracts/`,
+ ADRs) and the row's status advances. `legacy/` is read-only source material only.

> Single-file by choice. This seed is already large (~40 rows); once refining starts in earnest,
> expect to split by area (one file per area) rather than let this rot.

## Crosscutting concerns

| id | topic | tags | legacy source | status |
|----|-------|------|---------------|--------|
| 1 | Security model (blind store, threat model) | security, crypto | `legacy/docs/wiki/crosscutting/security-model.md` | refined → `docs/crosscutting/security-model.md` + `wiki/{en,de}` safety page |
| 2 | Crypto suite (hybrid PQ, suite versioning, vectors) | crypto | `legacy/docs/wiki/crosscutting/crypto-spec.md`, `legacy/docs/api/crypto-vectors/v1.json` | inbox |
| 3 | Account & key model (master key, wraps, recovery) | crypto, keys, auth | `legacy/docs/wiki/crosscutting/account-key-model.md` | refined → `docs/crosscutting/account-key-model.md` + `wiki/{en,de}`; auth = Model B (ADR 0003); scope/invite keys deferred to #4 |
| 4 | Spaces & scopes (personal/group/tenant, opaque IDs) | scopes, tenancy | `legacy/docs/wiki/crosscutting/space-navigation.md` | refined → `docs/crosscutting/scopes-and-tenancy.md` (end-user spaces page deferred to #14) |
| 5 | Privacy & non-enumerability | privacy | `legacy/docs/wiki/crosscutting/privacy-in-plain-language.md` | inbox |
| 6 | Live collaboration (blind CRDT relay) | journal, crdt | `legacy/docs/wiki/crosscutting/live-collaboration.md` | inbox |
| 7 | Search under E2EE | search | `legacy/docs/wiki/crosscutting/search.md` | inbox |
| 8 | Capability negotiation & version channels | platform, versioning | `legacy/docs/wiki/crosscutting/capability-scenarios.md` | inbox |
| 9 | Platform concepts (auth vs. unlock, error codes) | platform, auth | `legacy/docs/wiki/crosscutting/platform-concepts.md` | inbox |
| 10 | Agent mode (on-device processing) | processing, ml | `legacy/docs/wiki/crosscutting/agent-mode.md` | inbox |
| 11 | Dev styleguide & patterns (feature-slicing, guards, mappers) | conventions | `legacy/docs/wiki/crosscutting/developer-styleguide.md`, `legacy/docs/wiki/crosscutting/patterns-and-conventions.md` | refined → `docs/crosscutting/developer-styleguide.md` (consolidated both legacy files) |

## Features (+ use-cases)

| id | topic | tags | legacy source | status |
|----|-------|------|---------------|--------|
| 12 | Account & auth | feature, account | `legacy/docs/wiki/features/account/` | inbox |
| 13 | Administration & operations | feature, admin | `legacy/docs/wiki/features/admin/` | refining → `docs/features/admin/` + `uc.admin.setup-server` done; other admin use-cases pending |
| 14 | Spaces (tenants/groups/personal) | feature, scopes | `legacy/docs/wiki/features/spaces/` | inbox |
| 15 | Keys (grants, unlock wraps, rotation) | feature, crypto | `legacy/docs/wiki/features/keys/` | inbox |
| 16 | Memory (tree, curation, map/tracks, playlists) | feature, memory | `legacy/docs/wiki/features/memory/` | inbox |
| 17 | Uploads (unified encrypted media path) | feature, media | `legacy/docs/wiki/features/uploads/` | inbox |
| 18 | Journal (markdown, embeds, live co-edit) | feature, journal | `legacy/docs/wiki/features/journal/` | inbox |
| 19 | Sharing (public shares, roles, upload links) | feature, sharing | `legacy/docs/wiki/features/sharing/` | inbox |
| 20 | People & participants | feature, people | `legacy/docs/wiki/features/people/` | inbox |
| 21 | Search (filter, semantic) | feature, search | `legacy/docs/wiki/features/search/` | inbox |
| 22 | Mobile (deep links, resumable upload) | feature, mobile | `legacy/docs/wiki/features/mobile/` | inbox |
| 23 | Agent (workers, transcode, face recognition) | feature, processing | `legacy/docs/wiki/features/agent/` | inbox |
| 24 | Platform (rate limiting, app shell, web views) | feature, platform | `legacy/docs/wiki/features/platform/` | inbox |

## Infrastructure & operations

| id | topic | tags | legacy source | status |
|----|-------|------|---------------|--------|
| 25 | Architecture & runtime topology | infra | `legacy/docs/wiki/overview/architecture.md` | inbox |
| 26 | Deployment (compose, helm, channels, updates) | infra, deploy | `legacy/docs/wiki/overview/deployment.md` | inbox |
| 27 | Constraints & solution strategy | infra, strategy | `legacy/docs/wiki/overview/constraints-and-strategy.md` | inbox |
| 28 | Goals, stakeholders & quality | quality | `legacy/docs/wiki/overview/goals-stakeholders-quality.md` | inbox |
| 29 | Risks | infra, risk | `legacy/docs/wiki/overview/risks.md` | inbox |
| 30 | Operations runbook (install, backup, upgrade) | ops | `legacy/docs/operations.md` | inbox |

## Contracts

| id | topic | tags | legacy source | status |
|----|-------|------|---------------|--------|
| 31 | OpenAPI contracts (per-domain specs) | contracts, api | `legacy/docs/api/*.openapi.yaml` | inbox |
| 32 | Crypto conformance vectors | contracts, crypto | `legacy/docs/api/crypto-vectors/v1.json` | inbox |

## Decisions

| id | topic | tags | legacy source | status |
|----|-------|------|---------------|--------|
| 33 | ADR backlog (which decisions still hold) | decisions | `legacy/docs/adr/` (0001–0023), `legacy/docs/wiki/decisions/` (0029–0033) | inbox |

## Product & strategy

| id | topic | tags | legacy source | status |
|----|-------|------|---------------|--------|
| 34 | Memory product context (the vision) | product | `legacy/docs/product/memory-product-context.md` | inbox |
| 35 | Journal markdown & embed syntax | journal, product | `legacy/docs/product/journal-markdown.md` | inbox |
| 36 | Playlists & music references | product, music | `legacy/docs/product/playlists.md` | inbox |
| 37 | UI styleguides (web + iOS) | ui | `legacy/docs/product/memory-ui-styleguide.md`, `legacy/docs/product/ios-ui-styleguide.md` | inbox |
| 38 | Hosting & operations strategy (SaaS) | strategy, saas | `legacy/docs/product/hosting-and-operations-strategy.md` | inbox |
| 39 | Future work backlog | backlog | `legacy/docs/product/future-work.md` | inbox |
| 40 | Legal (privacy, trademark, commercial boundary) | legal | `legacy/docs/legal/` | inbox |
| 41 | SaaS: **Subscription** as a billing layer between the deployment (veyq.cloud) and Spaces | saas, billing, scopes, NEW | new framing; reconcile with `legacy/docs/adr/0022-saas-tenancy-byo-s3-hierarchy-and-usage-billing.md`, `legacy/docs/product/hosting-and-operations-strategy.md`, `legacy/docs/legal/commercial-boundary.md` | inbox |
