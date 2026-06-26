---
id: cr-0006
title: Drop the instance name from setup (wrong altitude for a multi-tenant instance)
status: done             # planned → in-progress → done (then delete the file)
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

- [x] **1 — contracts.** Removed `instanceName` from `CreateServerOwnerRequest` in `setup.openapi.yaml`
  (required list, property, example). **Done:** both clients regenerate with zero `instanceName` references
  (`CreateServerOwnerRequest` web + backend models). Backend compile + web typecheck are restored in
  phases 2 / 4 (the hand-written setup code still references the field until then).
- [x] **2 — backend.** Added `V6__drop_instance_name.sql` (DROP COLUMN); removed the field from
  `ServerSetup`, `OwnerRegistration`, the `SetupResource` mapping, and `SetupStore.persistOwner`; dropped
  `instanceName` from the `SetupServerSliceTest` bodies + the `SetupRequestSanitizationTest` body/assertion.
  **Done:** `./gradlew check` green (V6 applies on H2; setup tests 1 + 14, 0 failures).
- [x] **3 — http.** Dropped `instanceName` from the four `http/admin/setup.http` request bodies. **Done:**
  ijhttp green (6/6) against a fresh instance — owner-create succeeds without the field (V6 applied on the
  dev MariaDB, now at v6).
- [x] **4 — web.** Removed the instance-name input from `SetupView.vue` (and moved `autofocus` to the new
  first field, displayName), the `instanceName` param from `setupServer.ts`
  (`registerServerOwner`/`buildCreateServerOwnerRequest`) + the request field, the `setup.instanceName` i18n
  keys (en+de), and the `setupServer.test.ts` arg/assertion. **Done:** `npm run check` green (57). Preview
  smoke on a fresh instance: the setup form shows only Name / Email / Password (no instance-name field) and
  owner-create succeeds → kit screen, no console errors.
- [x] **5 — docs.** Updated `uc.admin.setup-server.md` (postcondition, main-flow step 2, both sequence
  lines) to drop the instance display name and made the Quality-notes host paragraph state explicitly that
  **no instance name is collected** (with the multi-tenant rationale). Also swept two stragglers: the
  `login.http` owner-seed body (dropped `instanceName`) and the `trim-name-fields` coverage criterion text
  ("Email and display name…"). **Done:** `validate_coverage.py` green (4 use-cases); login.http re-run
  green (11/11).

## Definition of done

`instanceName` is gone from contracts, backend (incl. the dropped column), http, web, and the use-case doc;
setup collects only what it needs (email, password → auth keypair, the wrapped key material + Secret Key) and
establishes the Server Owner. The Emergency Kit still identifies the instance by its host/URL. A human label,
if ever needed, is a per-tenant concern tied to the tenant's domain (future tenant-creation use-case), not a
server-setup field. After merge, keep this file as a record (per the cr convention).
