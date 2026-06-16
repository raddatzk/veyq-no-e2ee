# Spike: spec-generated mobile API models

**Question:** can iOS + Android share the backend's OpenAPI specs as the single source of truth for
the API wire shape, instead of hand-redeclaring every DTO three times (server-generated, Swift by
hand, Kotlin by hand)?

**Answer:** yes, for the *models*. The hand-written transport + crypto layer stays.

## What this spike does

`scripts/gen_mobile_models.py` generates **model structs/data classes only** from `docs/api/*.openapi.yaml`:

- **Swift** → `apple/Sources/VeyqApiModels/` — pure `Codable` structs (Foundation only), exposed as the
  `VeyqApiModels` SwiftPM product. `useJsonEncodable=false` keeps them free of generator infrastructure.
- **Kotlin** → `android/core/src/generated/kotlin/app/veyq/apimodels/` — `@Serializable` data classes
  (kotlinx.serialization), wired into `:core` via an extra source dir.

Both compile clean (`swift build --target VeyqApiModels`, `:core:compileKotlin`). 94 models each.

## How to run

```
python3 scripts/gen_mobile_models.py            # both platforms
python3 scripts/gen_mobile_models.py --kotlin   # one platform
```

Needs `npx` (pulls `@openapitools/openapi-generator-cli` 7.23.0, pinned in `openapitools.json`),
Java 17+, and PyYAML. The generated files are committed (like the backend's generated interfaces and
the yniffi binding) so normal builds don't need the generator.

## Why the bundling step

The domain specs reference shared schemas via **external file refs**
(`./common.openapi.yaml#/components/schemas/Blob`). swagger-parser inlines those and loses the name,
so `Blob` would explode into `CreateGroupRequestWrappedScopePrivateKey`-style duplicates. The script
therefore mirrors `backend/gradle/openapi.gradle.kts`: it first **bundles** all specs into one doc with
the common components inlined under their real names, then generates the model set once. Result:
shared types (`Blob`, `UserSummary`, `ServerRole`, …) are emitted once, named correctly.

## Scope / not-yet

- Models only. The hand-written `URLSession` / `java.net.http` calls + the AEAD wrap-chain are
  unchanged — generating those would fight the operator-blind crypto layer (the DTO fields are
  ciphertext blobs we seal/unseal by hand anyway).
- No call-site migration yet: `VeyqApiModels` / `app.veyq.apimodels` are built and compile, but the
  existing slice code still constructs its own JSON. Adopting the generated types call-by-call is the
  follow-up if we want the drift protection.
- The backend remains the only consumer that's *enforced* at build time; the value here is removing two
  of the three hand-maintained DTO copies and catching spec drift at mobile compile time.
