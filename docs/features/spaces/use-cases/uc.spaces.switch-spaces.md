# UC: List & switch spaces

- ID: uc.spaces.switch-spaces
- Primary actor: Member (authenticated + unlocked)
- Goal: move between the **tenants** they belong to and the **spaces** within the active tenant (their personal
  space, and — once built — their groups), from the shell.
- Preconditions: A live session + an unlocked vault; the member belongs to ≥1 tenant.
- Postconditions (success): The shell shows the chosen tenant/space; the active scope key is the one for the
  selected space.

## Main flow

1. The sidebar lists the member's **tenants** (the "Bereiche/Spaces" switcher) from `GET /api/scopes/my-tenants`
   — each its own domain ([ADR 0002](../../../decisions/0002-personal-scope-per-tenant.md)).
2. The sidebar lists the **spaces** in the active tenant: the **Personal** space
   ([en.spaces.personal-scope](../enablers/en.spaces.personal-scope.md)) and the member's **groups**
   ([en.spaces.group-scope](../enablers/en.spaces.group-scope.md), as they land).
3. Selecting a **different tenant** navigates to that tenant's domain (a full navigation — each tenant is its
   own origin).
4. Selecting a **space** switches the in-shell content position via the URL `#fragment` (`#~me`, `#@familie`,
   [ADR 0015](../../../decisions/0015-operator-blind-readable-urls.md)); the client opens that scope's key (from
   its grant) and browses its tree.

## Alternate / exception flows

- A1 — **only one tenant / no groups:** the switcher still shows the single tenant + Personal; the groups
  section is hidden until a group exists.
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
and between your private space and your shared groups, right from the sidebar. The app knows your spaces because
you hold a key to each — the server only knows *that* you're a member, never what's inside.

**DE —** Alles, worauf du Zugriff hast, an einem Ort: wechsle zwischen den Familien/Teams (Tenants), zu denen du
gehörst, und zwischen deinem privaten Bereich und deinen geteilten Gruppen — direkt in der Seitenleiste. Die App
kennt deine Bereiche, weil du für jeden einen Schlüssel hältst — der Server weiß nur, *dass* du Mitglied bist,
nie was darin liegt.

## Related

- Feature: [Spaces (scopes)](../_index.md)
- Enablers: [en.spaces.personal-scope](../enablers/en.spaces.personal-scope.md),
  [en.spaces.group-scope](../enablers/en.spaces.group-scope.md)
- Decisions: [ADR 0002](../../../decisions/0002-personal-scope-per-tenant.md) (tenant-bound scopes),
  [ADR 0014](../../../decisions/0014-group-scopes-and-governance.md) (groups, sealed names),
  [ADR 0015](../../../decisions/0015-operator-blind-readable-urls.md) (fragment routing)
- Work orders: cr-0031 (the switcher + fragment routing), cr-0032 (groups appear in the switcher)
