# backend (Quarkus / Kotlin)

Operator-blind API service. Loaded in addition to the root `CLAUDE.md` when working here.

## Prove your work
- `./gradlew check` (compile + slice tests) — runs on **in-memory H2** (`%test`, hermetic + fast).
  Runnable acceptance lives in `../http/` (ijhttp).
- **Run the app (`quarkusDev`) on a persistent MariaDB** (`%dev`): `docker compose up -d` (from `backend/`,
  see `docker-compose.yml`) starts it; data survives restarts (Flyway migrates once, then "up to date").
  `docker compose down -v` wipes it for a fresh instance. The H2 fallback is only the test default now.

## Conventions (see [developer styleguide](../docs/crosscutting/developer-styleguide.md))
- **API-first.** Edit the spec in `../contracts/api/*.openapi.yaml`; the Gradle build generates Kotlin
  models + JAX-RS interfaces (`openApiGenerateSpecs`). Never hand-write a DTO that duplicates a generated model.
- **Feature-slice + two axes ([ADR 0001](../docs/decisions/0001-backend-http-adapter-vs-application-slicing.md)):**
  `features/<area>/<usecase>/…`. A `…Resource` implements the generated `…Api` (thin adapter), delegates to
  a `…Handler`; persistence in a `…Store` (Panache). Resources are resource-shaped, not per-use-case.
- **Validation + sanitization belong in the spec** via `x-field-extra-annotation` (bean-validation
  `@NotBlank`/`@Size`/`@Pattern` + a Jackson trim deserializer) → enforced by `@Valid` at the boundary,
  **400 before the handler**. Don't re-validate field shape in the handler.
- **Migrations:** Flyway, append-only `V<n>__*.sql`, H2 ∩ MariaDB common subset (`TEXT` for opaque base64,
  `VARCHAR` for short); `schema-management.strategy=none` — Flyway owns the schema.

## Invariant
The backend performs **no content crypto** and never sees the password (Model B,
[ADR 0003](../docs/decisions/0003-operator-blind-password-auth.md)). It stores only ciphertext, public
keys and low-sensitivity operational metadata. Re-read
[security model](../docs/crosscutting/security-model.md) before touching any key/data/auth path.
