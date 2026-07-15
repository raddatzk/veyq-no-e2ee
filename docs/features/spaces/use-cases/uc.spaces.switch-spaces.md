# UC: List & switch spaces

- ID: uc.spaces.switch-spaces
- Primary actor: Member (authenticated + unlocked)
- Goal: move between the **tenants** they belong to and the **spaces** within the active tenant — the shared
  **Tenant** space, their **Personal** space, and — once built — their **groups** — from the shell.
- Preconditions: A live session + an unlocked vault; the member belongs to ≥1 tenant.
- Postconditions (success): The shell shows the chosen tenant/space; the active scope key is the one for the
  selected space, and the tree + favorites have been re-entered for it.

## Main flow

1. Under the sidebar's **"Bereiche/Spaces"** header the **active tenant** is shown as a single row — its avatar
   (the tenant's initial), name and domain — from `GET /api/scopes/my-tenants`, each tenant reached at its own
   domain ([ADR 0002](../../../decisions/0002-personal-scope-per-tenant.md)). A **chevron** on that row opens an
   **overview of all the member's tenants** to switch between them; the chevron appears only when the member
   belongs to **more than one** tenant.
2. That **same active-tenant row is the entry into the shared Tenant space**
   ([en.spaces.tenant-scope](../enablers/en.spaces.tenant-scope.md), labelled with the **tenant's name**) — its
   body opens the token-less tenant home, so the tenant *switcher* and the Tenant-*space* entry are **one row**,
   not two. Below it the sidebar lists the remaining spaces in the active tenant: the **Personal** space
   ([en.spaces.personal-scope](../enablers/en.spaces.personal-scope.md)) and the member's **groups**
   ([en.spaces.group-scope](../enablers/en.spaces.group-scope.md), as they land).
3. Selecting a **different tenant** from the overview navigates to that tenant's domain (a full navigation —
   each tenant is its own origin).
4. Selecting a **space** switches the in-shell content position via the URL `#fragment`, resolved entirely
   client-side ([ADR 0015](../../../decisions/0015-operator-blind-readable-urls.md)). The grammar
   ([ADR 0019](../../../decisions/0019-token-less-tenant-home.md)):

   | Space | Root | A child |
   |---|---|---|
   | **Tenant** (shared, the default home) | `/` *(token-less — no bare `#`)* | `/#urlaube` |
   | **Personal** | `/#~me` | `/#~me/urlaube` |
   | **Group** (later) | `/#@<id>` | `/#@<id>/urlaube` |

   The token-less default is the **Tenant** space — the shared commons is home; Personal rides the explicit
   `~me` prefix. The folder/Memory slugs stay in the fragment (operator-blind unchanged); only the leading
   token distinguishes the scope.
5. **Re-entering a scope** (a leading-token change, or first entry) opens that scope's key from its grant
   ([en.spaces.tenant-scope](../enablers/en.spaces.tenant-scope.md) /
   [en.spaces.personal-scope](../enablers/en.spaces.personal-scope.md)) and **resets the per-scope state** —
   the Memory **tree** and **favorites** are cleared and reloaded for the newly active scope, so no stale tree
   or favorites bleed across scopes. Navigating *within* a scope (segment change only) does not re-enter.

## Alternate / exception flows

- A1 — **only one tenant / no groups:** the active-tenant row is shown **without the overview chevron** (there
  is nothing to switch to) and still enters the Tenant space, with Personal below; the groups section is hidden
  until a group exists.
- A2 — **no tenant grant → Personal is home.** If the member holds **no `TENANT` grant** (today the common
  case for anyone but the cold-birth owner — tenant member-invite is not built yet), the token-less default is
  **not** openable, so the shell **falls back to Personal** (`~me`) as the effective home and surfaces the
  Tenant entry as unavailable ([ADR 0019](../../../decisions/0019-token-less-tenant-home.md),
  [en.spaces.tenant-scope](../enablers/en.spaces.tenant-scope.md)). No empty shared room is shown.
- E1 — **a space can't be opened** (grant unwrap fails): that space is surfaced as unavailable; the others
  remain switchable.

## Quality notes

- **Operator-blind.** The tenant list returns only **membership** metadata (sanctioned by the invariant) —
  tenant name + domain + the caller's role; **group names are sealed** and decrypted on device
  ([ADR 0014](../../../decisions/0014-group-scopes-and-governance.md)). Switching a space is **client-side**
  navigation; the server is not asked to resolve a path.
- **Auth ≠ content.** Switching shows a space only if its grant opens (the identity-unwrapped scope key); a
  session alone reveals nothing.

## What this means for you / Was das für dich bedeutet

**EN —** Everything you can reach lives in one place: switch between the families/teams (tenants) you belong to,
and — within one — between the **shared space** you all use, your **private** space, and your groups, right from
the sidebar. Opening the app drops you in the shared space by default; your private space is one click (or the
`~me` link) away. The app knows your spaces because you hold a key to each — the server only knows *that* you're
a member, never what's inside.

**DE —** Alles, worauf du Zugriff hast, an einem Ort: wechsle zwischen den Familien/Teams (Tenants), zu denen du
gehörst, und — innerhalb eines Tenants — zwischen dem **gemeinsamen Bereich**, den ihr alle nutzt, deinem
**privaten** Bereich und deinen Gruppen — direkt in der Seitenleiste. Beim Öffnen landest du standardmäßig im
gemeinsamen Bereich; dein privater Bereich ist einen Klick (oder den `~me`-Link) entfernt. Die App kennt deine
Bereiche, weil du für jeden einen Schlüssel hältst — der Server weiß nur, *dass* du Mitglied bist, nie was darin
liegt.

## Related

- Feature: [Spaces (scopes)](../_index.md)
- Enablers: [en.spaces.tenant-scope](../enablers/en.spaces.tenant-scope.md) (the shared space, opened),
  [en.spaces.personal-scope](../enablers/en.spaces.personal-scope.md),
  [en.spaces.group-scope](../enablers/en.spaces.group-scope.md)
- Decisions: [ADR 0002](../../../decisions/0002-personal-scope-per-tenant.md) (tenant-bound scopes),
  [ADR 0014](../../../decisions/0014-group-scopes-and-governance.md) (groups, sealed names),
  [ADR 0015](../../../decisions/0015-operator-blind-readable-urls.md) (fragment routing),
  [ADR 0019](../../../decisions/0019-token-less-tenant-home.md) (tenant is the token-less default home)
