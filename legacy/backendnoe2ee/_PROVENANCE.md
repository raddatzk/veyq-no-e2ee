# `legacy/backendnoe2ee/` — pre-E2EE original backend (BEHAVIOUR / server-logic reference)

> Added by us as a read-only reference (this file is the only non-original file here). **Never edit this tree.**

## What this is

The `backend/` extracted verbatim from commit **`c74aad46`** (`git archive c74aad46:backend`) — the **first**
veyq architecture, **completely without E2EE**. It is the server-side counterpart to
[`legacy/frontend-noe2ee/`](../frontend-noe2ee/_PROVENANCE.md): the clean, pre-E2EE original to mine for
**server behaviour, validation, and API shape** — not for any key/scope concept (there is none here; the server
saw plaintext).

Same stack family as the active backend — **Quarkus 3.36 + Kotlin + MariaDB + Flyway + Hibernate Validator +
WebAuthn** — so server-side logic (bean-validation annotations, normalizers, scheduled purges, mailer flows)
ports across with only the data/crypto layer swapped. 179 files.

## The three backend eras (so the confusion never recurs)

| | era | E2EE? | role now |
|---|---|---|---|
| **this dir** (`c74aad46:backend`) | 1st architecture | **no** | **behaviour / validation / API-shape reference** — port server logic FROM here |
| `legacy/backend/` | 2nd attempt (E2EE, but a scope/key concept we've since rejected) | yes | reference **only** for *how an operator-blind server was built* — NOT its scope/key concept |
| `backend/` (active) | current fresh-start (correct concept) | yes | the real backend — **concept + contracts-first DTOs live here** |

## How to port from here

- **Take:** server behaviour and shape — bean-validation annotations + their validators
  (`src/main/kotlin/com/veyq/api/common/validation/`), input normalizers (`@Normalize`), scheduled purges,
  mailer/invite flows, the host-based tenant resolution.
- **Re-derive, do NOT copy:** anything that assumes a **plaintext-reading server** — this backend stored and
  served content in the clear. The active backend is **operator-blind**: it persists only ciphertext + opaque
  metadata. Never reintroduce a plaintext content path.
- **Wire to:** the active **contracts-first** flow — DTOs are generated from `contracts/`, never hand-written
  ([CLAUDE.md](../../CLAUDE.md)); validation that belongs on a request field rides the OpenAPI schema
  (`pattern` / `x-field-extra-annotation`), not a hand-edited DTO.

## Why it was loaded (the immediate motivation)

`src/main/kotlin/com/veyq/api/common/validation/Host.kt` — a permissive **`@Host`** bean-validation constraint
whose `HostValidator` accepts **`localhost` (optional port)**, **IPv4 (optional port)**, **IPv6 in brackets
(optional port)**, and **dotted domains (optional port)**. The active contract instead pins the tenant `domain`
to `^([a-z0-9](-?[a-z0-9])*\.)+[a-z]{2,}$` (no port, no `localhost`, no IP literals —
`contracts/api/tenants.openapi.yaml`), which is why a self-hosted instance on `localhost:8081` or an IP cannot
register a matching tenant. This validator is the reference for relaxing that (its own CR — a contract change
across backend + all surfaces).
