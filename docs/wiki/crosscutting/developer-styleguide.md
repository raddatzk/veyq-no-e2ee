# veyq Developer Styleguide

> How we write code in veyq — the **code-level** companion to the architecture docs. It consolidates the
> conventions the codebase already lives by, so a new contributor (or agent) gets it right the first time.
>
> This page does **not** repeat what's already documented elsewhere — it links it:
> - **Invariants & project rules:** [Working Methodology](../../ai/working-methodology.md)
> - **Architectural / binding patterns:** [Patterns & Conventions](patterns-and-conventions.md)
> - **Module boundaries & service-cutting:** [System Architecture → Backend Modularity](../overview/architecture.md)
> - **Commit format & dev environment:** [`docs/development.md`](../../development.md)
> - **Docs & use-cases:** [Documentation Guide](../../ai/documentation-guide.md)
> - **Crypto wire format:** [Crypto Spec](crypto-spec.md) · **Threat model:** [Security Model](security-model.md)
> - **UI/visual:** the Memory UI styleguide + the iOS UI styleguide (this page is *code*, not visuals)
>
> **Litmus test for adding a rule here:** *would a competent developer get it wrong without this written down,
> and is it a rule everyone follows?* If it's just how one class happens to work, leave it to the code.

## 0. Read first — the invariants that override style

- **Operator-blind is a code rule, not just a goal.** The server stores/serves ciphertext and must never gain
  unilateral or silent access to content, keys or passwords. Re-read the [Security Model](security-model.md)
  before touching any data, key, media, sharing, search or storage path. The paramount invariant — an
  unshared personal space is confidential even from the operator — wins over any convenience below.
- **Self-hostable by default.** Managed hosting, push gateways, federation are optional layers, never required.
- **Terminology:** **Memory** (product), **Chapter** for nested memories, **Folder** is organizational only.
  No Album/Explorer models, APIs or tables.

## 1. Code organization (binding)

- **Feature-sliced, identically across stacks:** `features/<area>/<usecase>/…`. Backend pairs a thin
  `…Resource` with a `…Handler` per use-case (e.g. `features/account/login/{LoginResource,LoginHandler}`);
  the frontend mirrors `features/<area>/<usecase>/…`.
- **Cross-cutting code lives in `shared/`** — backend `shared/{security,crypto,content,keymgmt,storage,identity}`,
  frontend `shared/{crypto,…}` (`apiError`, `base64`, `pool`). `shared/` must not depend on a feature.
- **Dependencies stay directed.** If `memory` builds on `content`, `content` must not reach back into `memory`;
  share neutral rules through small interfaces.

## 2. Backend layering

- **Resource = HTTP adapter.** It implements the generated `…Api` interface and handles JAX-RS `Response`,
  headers, multipart/temp files and parameter normalization — nothing else. (See `ShareResources.kt`: every
  method is a one-line delegate.)
- **Handler/Service = one responsibility,** describable in one sentence. Constructor dependencies are a
  refactoring signal: 6–9 → review the responsibility; 10+ → actively split out policies/resolvers/mappers/stores.
- **Authorization through small guards,** not inline checks: `ScopeAccessGuard` (a valid `veyq_scope_grant` +
  ACTIVE membership), `TenantAdminGuard`, `NodeStructure.requireContainment`.
- **Mappers produce the target model directly** from the richer source (`NodeApiMapper`, `MediaApiMapper`); no
  `domain.toSummary().toSomethingElse()` chains. Generated API models stay at the resource/mapper boundary;
  handlers, guards and stores work with domain types.

## 3. API-first contract

- The **OpenAPI spec under `docs/api/` is the contract.** The Gradle build generates JAX-RS interfaces +
  Kotlin/TS models; resources implement the interfaces directly. Never hand-write a DTO that duplicates a
  generated model. The API is internal and may change with its consumers.

## 4. Input validation & sanitization

The mechanism is already wired; the **convention is to actually use it**.

- **The OpenAPI schema is the single source of validation truth.** Declare constraints on every request field —
  `maxLength`/`minLength` on strings, `pattern`, `format` (`uuid`, `email`, `date-time`), `minimum`/`maximum`
  on numbers, `required`. The generator (`useBeanValidation=true`) emits Bean Validation annotations, the
  generated interface already carries `@Valid @NotNull` on bodies, and `quarkus-hibernate-validator` rejects a
  bad request with **400 before the handler runs**. Exemplar to copy: `media.openapi.yaml`
  (`totalChunks: { minimum: 1, maximum: 10000 }`, `byteSize: { maximum: 10737418240 }`).
  **Do not** hand-validate in a handler what a schema constraint can express. *(Current gap: only
  `media.openapi.yaml` declares constraints — adding them to the other specs is the standing task this rule
  drives.)*
- **Domain checks beyond the schema stay imperative, in a guard/handler, close to the invariant** — and small,
  named, fail-closed. Good existing examples: `CryptoSuiteRegistry.require(suiteId)` (suite allow-list),
  `BlobStore` path-traversal guard (`require(!key.contains(".."))` + `require(p.startsWith(root))`), the
  rate-limit CIDR/`resolveClientIp` checks.
- **Operator-blind boundary:** the server may validate only **structure and server-visible fields** (IDs,
  sizes, MIME, `suiteId`, lengths, counts). It **cannot** validate or sanitize **content** — content is
  ciphertext. Never introduce a server path that must read content to validate it.
- **Sanitization is a client duty, and safe-by-construction.** Decrypted content rendered as HTML — above all
  **journal Markdown** — must never reach `v-html`/`innerHTML` unsanitized. The journal uses a deliberately
  small **escape-first renderer** (`features/memory/journal/markdown.ts` `renderMarkdown`: HTML-escapes
  every line, then applies a fixed safe subset; links are forced to `https?://` + `rel="noopener noreferrer"`),
  so no raw HTML or script can pass through — copy that property for any new decrypted-content render (extend
  the escape-first renderer, or run **DOMPurify**; never feed unescaped content to `v-html`). The CodeMirror
  editor is plain text and safe. This is a security rule, not formatting.

> This "spec is the single validation source of truth" is a stable decision → worth an [ADR](../decisions/README.md).

## 5. Naming (de-facto conventions)

- Classes by role suffix: `…Resource`, `…Handler`, `…Guard`, `…Store`, `…Mapper`, `…Service`, `…Filter`.
- Use-case folders are kebab-case (`create-tree`, `journal-live`); DB tables are `veyq_<snake>`; project config
  keys are `veyq.<kebab>`; crypto domain labels are `veyq/v1/<purpose>`.
- All repository docs and code identifiers are **English** (table names, annotations, config keys unchanged).

## 6. Comments — explain *why*, not *what*

This is one of the codebase's strongest habits; keep it. A comment earns its place by explaining an
**invariant, a threat, or a non-obvious decision** — not by restating the code.

- ✅ Good: the file headers in `media.ts`, `BlobStore.kt`, and the XFF reasoning in `RateLimitFilter.kt` —
  each says *why the design is shaped this way and what breaks otherwise*.
- ❌ Avoid: `// loop over the items` and comments that drift from the code.

## 7. Error handling

- **Backend:** return `ErrorResponse` with a **stable error code** (the client localizes). Never put content,
  keys or secrets in an error body — the operator-blind boundary applies to errors too.
- **Frontend:** build errors with the shared `apiError(res, label)` helper — a stable `"<label> failed: <status>"`
  message, with the response body appended **only in dev builds**. Don't couple UI/error-report text to whatever
  the server happens to echo.

## 8. Configuration

- Env-driven with safe defaults: `quarkus...=${ENV:default}`, with `%dev`/`%test` profile overrides; the
  in-memory/H2 path stays the dev/test default so slices run offline.
- **Project-owned `veyq.*` config:** model it with a complete **`@ConfigMapping`** class, not per-value
  `@ConfigProperty` (internal `quarkus.*` may use direct injection). *Known debt: several `veyq.*` values
  (e.g. in `RateLimitFilter`, `BlobStore`) still use `@ConfigProperty` and should migrate.*

## 9. Crypto & data (binding)

- **One suite, byte-identical everywhere.** Any change to crypto suite `v1` (or a new suite) requires: a
  **conformance vector in `docs/api/crypto-vectors/v1.json`** and a **passing conformance test on all stacks**
  (backend where applicable, web, iOS, Android) — ideally cross-checked by an independent implementation. Use
  domain-separated labels (`veyq/v1/<purpose>`), length-prefixed encoding (`lp`), and bind context into the AAD.
  Never roll your own primitive; never skip the vector.
- **Migrations are portable + Flyway-owned.** Use only column types valid on **both H2 and MariaDB** (the
  common subset; large binary → `longblob`); `schema-management.strategy=none` — Flyway is the single schema
  authority.

## 10. Testing

- **Slice tests** boot a fresh in-memory DB per suite (each registers its own single-owner setup). Cover
  **positive and negative** paths where behavior has real success/failure branches; prefer risk-based coverage
  over percentages.
- **Conformance tests** pin cross-stack crypto (§9). After changing coverage docs/tests run
  `node scripts/validate-feature-coverage.mjs`; map every behavior to its use-case coverage file.

## 11. Cross-platform parity

- Keep web / iOS / Android / API behavior in parity; when a platform intentionally differs, **mark it
  explicitly** in code and client-parity docs. Crypto parity is enforced mechanically by the conformance
  vectors (§9).

## 12. Git & PR

- **Domain-scoped Conventional Commits** — `type(scope): summary`, scopes `backend|web|ios|android|infra|ci|docs|repo`
  (migrations are `backend`, multi-domain lists each). Full rules in [`development.md`](../../development.md).
- `apple/Package.resolved` is build-generated — keep it out of commits.
