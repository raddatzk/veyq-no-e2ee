---
id: cr-0171
title: Two sensors for operator-blindness — declared field visibility, and an object key with no structure
status: planned
satisfies: []
adrs: ["0044", "0013", "0023", "0034"]
invariants:
  - operator-blind E2EE — the server sees only ciphertext and opaque metadata; this CR is the first thing that checks it
  - API-first — contracts are the source of truth; the markers go in the spec, never in generated code
---

## Goal

Operator-blindness is the first hard invariant and the only one nothing checks. Nine validators guard doc links,
feature format, coverage refs and vocabulary; none guards the claim the product exists for. This CR builds the
first two sensors for it. They are unrelated mechanisms guarding the same invariant at the two places it can
break, and neither subsumes the other.

**Phase 1 makes the contract say what the server may read.** Every scalar field in the 9 specs declares its
visibility class, or the build fails ([ADR 0044](../docs/decisions/0044-field-visibility-declared-at-the-field.md)).
This catches the *design* hole: a plaintext `title: {type: string}` added to a spec. Today such a field lands
silently and the client fills it a phase later.

**Phase 2 makes the S3 object key prove it carries no structure.** The key is minted as a bare
`UUID.randomUUID()` today, which is right — and it is one "helpful" edit away from
`tenant/{name}/{title}.jpg`, which would hand the operator every filename and album title while the content
stayed encrypted and every test stayed green. The operator **runs the store**: they list the bucket and read
every key. This is the most operator-visible surface in the system and nothing watches it.

The point of both is the same and it is not detection. It is that the next person to touch these places — a
fresh session, a contributor, us in six months — cannot make the change quietly.

## Context & constraints

Links, not copies:

- **The decision:** [ADR 0044](../docs/decisions/0044-field-visibility-declared-at-the-field.md). It carries the
  class table, why `contentEncoding: base64` needs no marker, and why a name regex and a central allowlist were
  both measured unsound. **Read it before Phase 1.**
- **The rationale it enforces:** [`security-model.md`](../docs/crosscutting/security-model.md) § *What is
  encrypted vs. server-visible* — the classes are that section's own kinds. It is the authority; ADR 0044 only
  makes it executable.
- **The necessity test `pii` records:** [ADR 0023](../docs/decisions/0023-per-tenant-member-identity.md) —
  *"only the plaintext the mechanism requires stays plaintext."*
- **Phase 2's subject:** [ADR 0013](../docs/decisions/0013-media-blob-transport.md) (S3-only, presigned
  direct-to-store). The key is minted in `MediaHandler.kt` and consumed by `BlobStore.chunkKey`.
- **Measured baseline** (2026-07-15, `contracts/api/*.openapi.yaml`): 140 schemas · 401 property occurrences ·
  136 distinct names · **106 already opaque** (`contentEncoding: base64`) · **49 containers** (`$ref` or array of
  `$ref` — no marker) · **246 scalars to mark**. No body defines inline properties, so
  `components.schemas.*.properties.*` is the whole surface.
- **CI needs no edit.** `trust-chain.yml` globs `scripts/validate_*.py` (cefaf47c), so a new validator runs the
  day it lands. Do not add it to a list anywhere — there is no list.

### Traps

- **`ownerKey` is the counterexample the whole design turns on.** It ends in `Key`, it is **not** a key, and it
  is deliberate plaintext (`user:{userId}` / `share:{shareIdentityId}`, [ADR 0034](../docs/decisions/0034-media-uploader-buckets.md)).
  It must get `x-veyq-visibility: pii` **with a rationale** — not `opaque`, not `id`. If the backfill classifies
  it by its name, this CR has produced a validator that blesses the field most likely to leak.
- **`security-model.md` lists MIME type as server-visible and no spec has such a field** (`:70`). The doc is
  ahead of the contract or behind it. Settle it when the backfill reaches `media`; do not invent a field to
  match the doc, and do not silently drop the doc's clause.
- **`x-field-extra-annotation` is not a classifier.** It binds request DTOs only, so it cannot reach the 59
  response-only field names. It is a codegen hook that happens to sit in the same place. Do not extend it.
- **Do not touch `web/src/api/generated/`.** It is gitignored derivation; the codegen copies spec descriptions
  into it. If a marker shows up there, that is the generator working, not a file to edit.

## Phases

### Phase 1 — contracts: every field declares its visibility

1. Write `scripts/validate_field_visibility.py`. Rule, per ADR 0044: for every
   `components.schemas.*.properties.*` in `contracts/api/*.openapi.yaml` — a property with `contentEncoding:
   base64` is opaque and needs nothing; a property that is a `$ref` or an array of `$ref` is a container and
   needs nothing; **every other property must carry `x-veyq-visibility`** from the class set, and
   `x-veyq-visibility: pii` must be accompanied by a non-empty `x-veyq-rationale`. An unknown class is an error.
2. **The docstring states the incident and the scope**, per the convention every other validator follows. The
   scope sentence is not optional: this is a *body-surface* sensor. It does not see path params, query params,
   `X-Contributor-Token` / `X-Share-Password`, presigned-URL query strings, DB columns without a contract, error
   `message` free text, or the S3 object key. Green means *"no unclassified field in a JSON body"*. Anyone who
   reads it as *"operator-blind, verified"* has been misled by this file.
3. Backfill the **246** scalar occurrences. Mechanical, but not blind: each class is a claim, and
   `security-model.md` § *What is encrypted vs. server-visible* is what licenses it. Where a field's class is
   not obvious from that section, stop and ask — an invented `x-veyq-visibility: structure` on a field that is
   really `pii` is worse than the unmarked field, because it looks settled.
4. `security-model.md` § *What is encrypted vs. server-visible* gains one line pointing at ADR 0044 — the section
   stays the rationale, the ADR carries the mechanism. Do not restate the class table there.

**Done when:** `python3 scripts/validate_field_visibility.py` is green over all 9 specs; every `pii` field carries
a rationale that names a mechanism; `./gradlew check` and `npm run check` are green (the markers are inert to
both generators — prove it, do not assume it); the full glob `for f in scripts/validate_*.py` is green.

**Bite it before believing it:** add a `title: {type: string}` to a spec with no marker → the validator must go
red. Add `x-veyq-visibility: pii` without a rationale → red. Snapshot-and-restore the spec; never `git checkout`.

### Phase 2 — backend: the object key carries no structure

1. A hermetic slice test asserting the **minted** `veyq_blob.storageKey` matches a bare UUID and nothing else —
   no tenant, no user, no filename, no extension. It runs on H2 in `./gradlew check`; no store and no e2e are
   needed, because the key is minted server-side and persisted before any object is written.
2. Cover `BlobStore.chunkKey` too: its output is `<uuid>/<index>` and carries nothing else.
3. Name it so the next reader knows what it defends — the test's own comment says *why* a structured key would
   break operator-blindness while leaving every content assertion green.

**Done when:** the test is green; **and it has been bitten** — temporarily mint the key as
`"$tenantId/$mediaId"` and watch it go red, then snapshot-restore. A guard nobody has seen fail is a guard
nobody has tested.

## Definition of done

- Both phases done, each ending repo-working.
- `./gradlew check`, `npm run check`, and every `scripts/validate_*.py` green.
- Both new guards **bitten** — each has been observed failing on the defect it exists to catch.
- ADR 0044 needed no edit to survive contact. If it did, the edit landed in the ADR, not in a workaround here.
- Inbox #97 deleted; what it taught lives in ADR 0044, the two validators' docstrings, and the Phase 2 test's
  comment. The row is scaffolding.
- The `x-veyq-visibility` classes that turned out wrong or missing during the backfill are named in the
  retrospective — the class table is ADR 0044's claim, and 246 fields is the first real test of it.

## Retrospective

_(filled at implement time)_
