# Plan: Admin/Settings-UI auf das E2EE-Backend bringen (Punkt 3c)

## Kontext & Ziel
Die alten Settings-Panels (`ServerSettingsPanel`, `TenantSettingsPanel`, `GroupSettingsPanel`,
`PrivacySettingsPanel`) sind komplett auf das alte hierarchische Settings-API (`getSettings(path)` →
`subject.type`) + alte Typen (numerische IDs, `passkeyCount`/`avatarUrl`/…) verdrahtet und gegen das neue
operator-blind E2EE-Backend nicht erreichbar (404). Sie verursachen die verbleibenden ~21 Typecheck-Fehler.

**Gute Nachricht:** Die **Datenschicht implementiert die krypto-schweren Flows bereits** — Member-Invites
(temp-wrap), Scope-Rotation (Re-Key aller Member + Re-Seal des Contents), Grant-Rewrapping, Group-CRUD,
Server-User-Rollen. Die Arbeit ist also v. a. **UI-Rewiring + Ersatz der `getSettings`-Dispatch-Logik**, plus
einige echte Backend-Lücken.

Verfügbare Datenschicht-Module:
`features/admin/serverUsers`, `features/admin/inviteMembersRoles`, `features/admin/createTenantInviteOwner`,
`features/spaces/groups`, `features/spaces/invite`, `features/account/privacy/mapConsent`, `app/spaces`.

## Architektur-Entscheidungen
1. **`getSettings`-Dispatch ersetzen.** `SettingsView` ruft kein hierarchisches `getSettings(path)` mehr auf.
   Stattdessen routen wir explizit:
   - `/~me/settings` → `UserSettingsForm` (bereits erledigt).
   - `/server/settings` → `ServerSettingsPanel` (nur `serverRole === 'ServerOwner'`).
   - Tenant/Group-Settings: an die aktive Space-Auswahl (`session.activeSpace` + `app/spaces`) koppeln statt an
     Tenant-Permalinks; Owner-Gating über die Member-/Group-Rolle des aktiven Scopes.
   Breadcrumbs/Toolbar aus dem alten Flow entfallen oder werden minimal aus dem Space-Namen gebaut.
2. **Datenschicht wiederverwenden, alte API-Clients raus.** Panels rufen die `features/admin|spaces`-Funktionen
   (Bearer-Session aus `app/session`) statt `features/settings/api` / generierter Clients. Alte Clients
   (`features/settings/api/*`, `api/userClient`, `api/entrypointClient`, `components/tenant/CreateTenantDialog`
   alte Pfade) werden entfernt.
3. **IDs sind jetzt Strings.** Alle Panels auf String-User/Tenant-IDs umstellen (Quelle der meisten Typfehler).
4. **Krypto bleibt in der Datenschicht** — die UI ruft nur die fertigen Funktionen; kein neues Krypto im UI.

## Lücken, die Backend-/Datenschicht-Arbeit brauchen (vor/again während der Phasen entscheiden)
| Feature (alt) | Neuer Stand | Lücke / Entscheidung |
|---|---|---|
| Server-User **enable/disable/delete** | nur `setServerRole` (Rolle) | Backend-Endpoints fehlen → **deferren** oder neu bauen (`PUT /api/admin/users/{id}/status`) |
| Tenant **Terms** set/get | Backend `PUT/GET /api/tenants/{id}/terms` **existiert** | **TS-Wrapper fehlt** → `features/admin/.../terms.ts` ergänzen (klein) |
| Tenant **Rename / Domain ändern** | kein Endpoint | deferren (Backend-Erweiterung) |
| **Mehrfach-Registrierungs-Invite-Links** (Tenant) | nur 1-time owner-invite + member-invites | deferren oder Backend-Feature; für E2EE-Member reicht der Member-Invite-Link-Flow |
| **Reinvite / pending-Invites listen** | nicht vorhanden | deferren |
| **Gruppe umbenennen** | `sealedName` unveränderlich | kleines Backend-/Datenschicht-Add (sealedName-Update) oder deferren |
| **Member entfernen → Forward-Secrecy** | `removeMember` (instant) + `rotateScope` (eventual) | UI **muss** nach Remove eine Scope-Rotation anbieten (siehe Krypto-Hinweise) |
| Privacy-Panel (OSM-Tiles) | neu: `features/account/privacy/mapConsent` (local-only) | nur auf das neue local-only Consent umstellen (klein) |
| Tenant/Server **Logo** | `uploadTenantLogo`/`clearTenantLogo` vorhanden | direkt nutzbar |

## Krypto-Hinweise (Invarianten, die das UI einhalten muss)
- **Member-Remove ist zweistufig (AD-24):** `removeMember` widerruft Grant+Sessions sofort; für echte
  Forward-Secrecy muss der Owner danach **`rotateScope`** ausführen (neuer Scope-Key + frischer Content-Key +
  Re-Seal aller Items/Nodes). → Das UI nach jedem Remove „Zugriff endgültig entziehen (Scope rotieren)" anbieten.
- **Member hinzufügen / Invite annehmen:** Grant-Rewrapping passiert client-seitig (`issueMemberInvite` →
  temp-wrap; `acceptMemberInvite`/`registerViaMemberInvite` → re-wrap auf eigene Identität). UI nutzt die fertigen
  Funktionen; Invite-Link trägt Secrets nur im URL-Fragment.
- **Gruppe anlegen/Member adden:** `createGroupSpace`/`addGroupMember` wrappen Scope/Content-Key — fertig.
- **Atomar & vollständig:** Scope-Rotation muss exakt die aktiven Member abdecken (Backend validiert).

## Phasen
### Phase A — SettingsView-Routing + Gerüst
- `SettingsView.vue`: `getSettings`-Aufruf entfernen; Rendering rein nach `route.name` + aktiver Space-Rolle.
- Owner-Gating-Helfer (ServerOwner via `session.unlocked.user.serverRole`; Tenant/Group-Owner via Member-Liste
  des aktiven Scopes). Toolbar/Breadcrumbs minimal.
- Sidebar: „Server-Einstellungen"-Link nur für ServerOwner; Space-Settings-Einstieg pro aktivem Space.

### Phase B — Server-Settings (klein)
- `ServerSettingsPanel.vue` neu auf `listServerUsers` + `setServerRole` (String-IDs, Rolle-Selector).
- Server-Identität/Version/License/Support aus `GET /api/setup` (statt altem serverApi).
- Update-Log/Notifications: **deferren** (kein neuer Endpoint nötig; Abschnitt ausblenden).
- enable/disable/delete-User: **deferren** (Backend-Lücke) — Buttons entfernen oder als „nicht verfügbar".

### Phase C — Tenant-Settings (mittel)
- Mitglieder: `listTenantMembers` + `issueMemberInvite`/`inviteToGroup`(Link) / `emailInviteToGroup` /
  `removeMember` / `changeMemberRole`. Nach Remove: Scope-Rotation anbieten (`rotateScope`).
- Logo: `uploadTenantLogo`/`clearTenantLogo` (+ `scaleTenantLogo`).
- Terms: kleinen TS-Wrapper für `GET/PUT /api/tenants/{id}/terms` ergänzen; Multi-Locale-Markdown-Editor +
  Terms-Template-Dialog behalten.
- Tenant löschen: `deleteTenant`/`cancelTenantDeletion` (Grace-Period-Hinweis).
- Mehrfach-Registrierungs-Links: deferren (oder separater Backend-Task).

### Phase D — Group-Settings (mittel)
- `GroupSettingsPanel` neu auf `features/spaces/groups`: `createGroupSpace`, `listGroupMembers`,
  `addGroupMember`, `setGroupMemberRole`, `removeGroupMember`, `deleteGroup`. Namen via `openSealedName`.
- Gruppenliste aus `app/spaces` (kind GROUP). Rename: deferren bis sealedName-Update existiert.
- `CreateGroupFlowDialog`/Sidebar-„Gruppe erstellen" an `createGroupSpace` hängen.

### Phase E — Privacy (klein)
- `PrivacySettingsPanel` + `RegistrationPrivacyOptIns` auf `features/account/privacy/mapConsent`
  (`readMapTilesConsent`/`setMapTilesConsent`, reaktiver `mapTilesConsent`). Alten `osmTiles`-Helfer ersetzen.

### Phase F — Aufräumen + Tests
- Entfernen: `features/settings/api/*`, `api/userClient.ts`, `api/entrypointClient.ts`, tote generierte
  Clients, veraltete Panel-Tests. → beseitigt die restlichen Typecheck-Fehler.
- Neue, schlanke Component-Tests pro Panel (mount + `[router, PrimeVue]`, Datenschicht gemockt).
- `npm run check` (typecheck + lint + test + build) grün.

## Kritische Dateien
- `frontend/src/views/SettingsView.vue` (Dispatch-Rewrite)
- `frontend/src/features/settings/components/{Server,Tenant,Group,Privacy}SettingsPanel.vue` (Neuverdrahtung)
- `frontend/src/components/tenant/CreateTenantDialog.vue`, `components/auth/RegistrationPrivacyOptIns.vue`
- Datenschicht (wiederverwenden): `features/admin/*`, `features/spaces/*`, `features/account/privacy/mapConsent.ts`,
  `app/spaces.ts`
- Neu (klein): `features/admin/.../terms.ts` (Terms get/put-Wrapper)
- Entfernen: `features/settings/api/*`, `api/userClient.ts`, `api/entrypointClient.ts`

## Verifikation (E2E gegen Dev-Backend, MinIO läuft)
1. Server-Owner: `/server/settings` → User-Liste, Rolle ändern (`setServerRole`) wirkt.
2. Tenant: Member einladen (Link) → in 2. Browser annehmen → erscheint in Liste; Rolle ändern; entfernen →
   Scope-Rotation anbieten + ausführen; Logo hoch/runter; Terms speichern.
3. Gruppe: erstellen (Sidebar zeigt sie) → Member adden → in Gruppe wechseln → Inhalt sichtbar; löschen.
4. Privacy: Map-Tiles-Consent toggeln (persistiert lokal; Map lädt erst nach Opt-in).
5. `npm run check` grün; betroffene Vitest-Suites grün.

## Aufwand (grob)
Phase A klein · B klein · C mittel · D mittel · E klein · F klein. Backend-Lücken (enable/disable-User,
Tenant-Rename/Domain, Multi-Use-Registrierungs-Links, Group-Rename) sind separate, optionale Erweiterungen und
für die Kernfunktion nicht nötig.
