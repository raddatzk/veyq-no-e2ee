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
| 43 | At-rest encryption of server-visible PII + multi-ServerOwner / server-scope key | security, crypto, saas, NEW | new framing; reuse `SecretCipher` ([[at-rest-secret-encryption]]); encrypt server-visible emails/PII (incl. `veyq_user.email`, tenant `owner_email`) under an operator-held key — defense-in-depth vs DB-dump, **not** operator-blind; optionally a **server-scope key wrapped to ServerOwner identities** (enables N owners + rotation). Most compelling once SaaS (#41) is live. Decided NOT to bolt onto cr-0009 | inbox |
| 44 | Centralize HTTP auth & authz (Quarkus SecurityIdentity + @RolesAllowed) | conventions, auth, backend, NEW | new framing; today every Resource re-does `@Context headers` + `bearerToken` + `unauthorized()` and each handler takes a raw token + carries `Unauthenticated`/`NotOwner`. Move to a custom `HttpAuthenticationMechanism` + `IdentityProvider` → `SecurityIdentity`; Resources declare `@Authenticated`/`@RolesAllowed(SERVER_OWNER)`/`@PermitAll`; 401/403 body via two central `ExceptionMapper`s; `ServerOwnerGuard` folds in. Decided AGAINST a spec-level `security:` block (leaks role string, forces template fork). Backend-only, wire behaviour unchanged | **done** → cr-0012 (ADR 0008; default-deny live; one delta: anon+malformed body → 401 not 400) |
| 45 | Signed happy-paths on the http/ surface (Ed25519 in pre-request scripts) | testing, http, parity, NEW | new framing; ijhttp can `import` JS, so vendor tweetnacl under `http/_lib/` + an ESM facade and sign the login nonce in a pre-request handler → close the "HTTP client cannot produce an Ed25519 signature" gap. Add separate `*-authed.http` (fresh-instance, self-seed owner) for signed login/unlock/trust-device/create-tenant happy-paths; boundary files stay zero-precondition. Spike proved ijhttp executes the lib | **done** → cr-0013 (authed-flow.http 11/11 + tenant-authed.http 9/9; coverage files now track http e2e; caveats removed) |

## Features (+ use-cases)

| id | topic | tags | legacy source | status |
|----|-------|------|---------------|--------|
| 12 | Account & auth | feature, account | `legacy/docs/wiki/features/account/` | refining → `docs/features/account/` + `uc.account.login` done (cr-0003) & `uc.account.unlock` done (cr-0004, PASSWORD wrap); ADR 0004 (sessions) + ADR 0005 (unlock methods/device trust) accepted; `uc.account.trust-device` done (cr-0005, DEVICE wrap = device key + password, web); passkey/onboard-device(QR·push)/recovery/sessions use-cases pending |
| 13 | Administration & operations | feature, admin | `legacy/docs/wiki/features/admin/` | refining → `docs/features/admin/` + `uc.admin.setup-server` done; cr-0006 done — dropped the instance-name from setup (wrong altitude for multi-tenant — tenant identity = domain); `uc.admin.create-tenant` **refined** (two-party: ServerOwner creates PENDING tenant + invite → invited owner activates via **cold TENANT-scope birth**; ADR 0006); cut into **cr-0009** (Slice A structural — **done**: contracts+backend+http+web, browser-smoke green) + **cr-0010** (Slice B activation — **done**: invite-accept incl. invite-gated cold registration + cold TENANT-scope birth + first OWNER membership/grant → ACTIVE, reuse-detection; first scope-key/grant/membership code; per ADR 0007); other admin use-cases pending |
| 14 | Spaces (tenants/groups/personal) | feature, scopes | `legacy/docs/wiki/features/spaces/` | inbox |
| 15 | Keys (grants, unlock wraps, rotation) | feature, crypto | `legacy/docs/wiki/features/keys/` | inbox |
| 16 | Memory (tree, curation, map/tracks, playlists) | feature, memory | `legacy/docs/wiki/features/memory/` | inbox |
| 17 | Uploads (unified encrypted media path) | feature, media | `legacy/docs/wiki/features/uploads/` | inbox |
| 18 | Journal (markdown, embeds, live co-edit) | feature, journal | `legacy/docs/wiki/features/journal/` | inbox |
| 19 | Sharing (public shares, roles, upload links) | feature, sharing | `legacy/docs/wiki/features/sharing/` | inbox |
| 20 | People & participants | feature, people | `legacy/docs/wiki/features/people/` | inbox |
| 21 | Search (filter, semantic) | feature, search | `legacy/docs/wiki/features/search/` | inbox |
| 22 | Native clients (mobile + desktop + agents) | feature, mobile, desktop, native | `legacy/docs/wiki/features/mobile/`, `legacy/{apple,iOS,android}` (read-only ref) | in progress — **ADR 0010** governs: *shared contracts, not shared code* (per-platform native; parity via OpenAPI + crypto vectors + `http/` acceptance). Matrix (langs pinned): Apple=Swift (macOS=iOS=iPadOS; tvOS=libs-only, UI later via passkey/QR), Android=Kotlin, **Windows=C#/.NET+WinUI 3**, **Linux=Rust+gtk4-rs/relm4+libadwaita** + XDG-portals + Flatpak; headless **agent** = UI-less head per OS (macOS StatusItem / Windows tray / Linux StatusNotifierItem+Docker; GNOME-tray needs AppIndicator ext). **arm64 + amd64, no 32-bit.** **ADR 0009** = crypto per-platform vector-gated. **cr-0014 DONE**: apple (`swift test`) + android (`:crypto:test`) v1 auth/unlock subset byte-identical to vectors; ML-KEM deferred. **cr-0015 DONE**: Apple universal login+unlock vertical (macOS/iOS/iPadOS SwiftUI on VeyqKit) — data layer + full UI round-trip green against a live instance, screenshots committed (iOS; macOS desktop pending an Accessibility grant), `uc.account.{login,unlock}` apple coverage recorded. Next: android (then windows/linux) login+unlock |
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
| 42 | Scheduled purge of expired session tokens | infra, sessions, cleanup, NEW | new — extends the `@Scheduled` purge seam introduced by cr-0009 (tenant invites) to expired/terminal session tokens (`veyq_refresh_token`/`veyq_access_token`, ADR 0004); today they are status/expiry-gated but never physically deleted | **done — cr-0011** (`SessionPurge` deletes expired access/refresh tokens, stale challenges, token-less families; keeps unexpired SUPERSEDED refresh for reuse-detection) |

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
| 37 | UI styleguides (web + native) + cross-platform design parity | ui | `legacy/docs/product/memory-ui-styleguide.md`, `legacy/docs/product/ios-ui-styleguide.md` | refining → **design-parity umbrella** `docs/crosscutting/ui-design-parity.md` (one veyq design, native everywhere: canonical role tokens light+dark, shared component contract, size-class responsive rule, per-surface mapping); per-surface: web `ui-web-styleguide.md` ✅, apple `ui-apple-styleguide.md` ✅ (cr-0015), android `ui-android-styleguide.md` ✅ (governs cr-0016); windows/linux pending with their heads |
| 38 | Hosting & operations strategy (SaaS) | strategy, saas | `legacy/docs/product/hosting-and-operations-strategy.md` | inbox |
| 39 | Future work backlog | backlog | `legacy/docs/product/future-work.md` | inbox |
| 40 | Legal (privacy, trademark, commercial boundary) | legal | `legacy/docs/legal/` | inbox |
| 41 | SaaS: **Subscription** as a billing layer between the deployment (veyq.cloud) and Spaces | saas, billing, scopes, NEW | new framing; reconcile with `legacy/docs/adr/0022-saas-tenancy-byo-s3-hierarchy-and-usage-billing.md`, `legacy/docs/product/hosting-and-operations-strategy.md`, `legacy/docs/legal/commercial-boundary.md` | inbox |
