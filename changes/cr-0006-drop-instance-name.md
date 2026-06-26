---
id: cr-0006
title: Drop the instance name from setup (wrong altitude for a multi-tenant instance)
status: planned          # planned → in-progress → done (then delete the file)
satisfies: [uc.admin.setup-server]
adrs: [0002]
invariants: [operator-blind]
---

## Goal

Remove the **`instanceName`** field from first-run setup across every surface. It is collected as the first
required field at setup, sent to the server, and stored in `veyq_server_setup.instance_name` — but **nothing
ever reads it back**: no endpoint returns it, no UI shows it, and the Emergency Kit labels its "Instance" with
the request **host/URL** (`session.apiBase`), not the name. So today it is write-only friction.

Deeper than "unused": a single operator-set, cleartext, **server-wide** name is the **wrong altitude** for
this product. Per [Scopes & Tenancy](../docs/crosscutting/scopes-and-tenancy.md) + [ADR 0002](../docs/decisions/0002-personal-scope-per-tenant.md):
**one server hosts many tenants, each with its own domain**, tenants are **resolved by request `Host`** (not
by a name), users are **global to the server** and join tenants **over time**, scopes are addressed by
**opaque UUID "never by name"**, and every human **name is E2EE / client-decrypted** ("the operator must not
learn private names"). There is therefore no coherent single name for an instance: the nameable/identifiable
units are the **tenant (= a domain)** and **spaces/Memories (E2EE, owner-set)**. The first owner cannot name,
at server-setup time, tenants that don't exist yet and will be added later by others. The field is a leftover
from a single-tenant mental model; setup should establish the **Server Owner** only.

## Context & constraints

- Use-case: [uc.admin.setup-server](../docs/features/admin/use-cases/uc.admin.setup-server.md) — its own
  text already notes the host is visible from the request `Host` "so it need not be a separately typed field".
- Concepts: [Scopes & Tenancy](../docs/crosscutting/scopes-and-tenancy.md) (host-resolved tenants, opaque
  scope IDs, E2EE names), [ADR 0002](../docs/decisions/0002-personal-scope-per-tenant.md).
- **Not an operator-blind *fix*** (an operator labelling their own box was always allowed) — the driver is
  **scope correctness + dead-data removal**; a happy side effect is a touch less server-visible cleartext.
- **Greenfield, no production data** — dropping the column is safe; a tenant-level display name, if ever
  wanted, belongs to **tenant creation** (a future use-case), keyed to the tenant's domain, not to setup.

## Phases

- [ ] **1 — contracts.** Remove `instanceName` from `CreateServerOwnerRequest` in `setup.openapi.yaml` (the
  `required` list, the property, the example). **Done when:** the spec lints and the backend/web clients
  generate without it.
- [ ] **2 — backend.** Append migration `V6__drop_instance_name.sql`
  (`ALTER TABLE veyq_server_setup DROP COLUMN instance_name;`); drop the field from `ServerSetup`, the
  `OwnerRegistration` (`SetupHandler`), the `SetupResource` mapping, and `SetupStore.persistOwner`; update
  `SetupServerSliceTest` + `SetupRequestSanitizationTest` (no `instanceName` in the body / assertions).
  **Done when:** `./gradlew check` green (runs against both H2 (test) and the migration applies on MariaDB
  (dev)).
- [ ] **3 — http.** Drop `instanceName` from the request bodies in `http/admin/setup.http`. **Done when:**
  ijhttp green against a live instance.
- [ ] **4 — web.** Remove the instance-name input from `SetupView.vue`, the `instanceName` param from
  `setupServer.ts` (`registerServerOwner` / `buildCreateServerOwnerRequest`), the `setup.instanceName` i18n
  keys (en+de), and the assertion in `setupServer.test.ts`. **Done when:** `npm run check` green and a preview
  smoke shows setup → owner created with no instance-name field.
- [ ] **5 — docs.** Update `uc.admin.setup-server.md` to drop the "instance display name" from the goal /
  main flow / sequence (setup establishes the Server Owner; tenant identity = domain, deferred to tenant
  creation). Re-run `python3 scripts/validate_coverage.py` (no acceptance criterion references the name, so
  coverage stays green). **Done when:** the validator is green and the doc no longer claims a name is set.

## Definition of done

`instanceName` is gone from contracts, backend (incl. the dropped column), http, web, and the use-case doc;
setup collects only what it needs (email, password → auth keypair, the wrapped key material + Secret Key) and
establishes the Server Owner. The Emergency Kit still identifies the instance by its host/URL. A human label,
if ever needed, is a per-tenant concern tied to the tenant's domain (future tenant-creation use-case), not a
server-setup field. After merge, keep this file as a record (per the cr convention).
