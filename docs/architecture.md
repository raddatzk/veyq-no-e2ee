# Lunva Architektur

## Zielbild

Lunva läuft als eine Instanz mit globalen Usern und mehreren Tenants. Externe Domains, TLS und Root-Pfad-Rewrites werden von einem vorgelagerten Proxy verwaltet. Das Backend kennt keine Host-Mappings und scoped fachliche Daten über den Tenant-Namen im Pfad.

## Laufzeit

- Quarkus Backend liefert API und eingebautes Vue-Frontend aus.
- MariaDB ist die produktive Datenbank.
- Flyway verwaltet das Schema über eine Initial-Migration.
- Docker Compose startet App und Datenbank lokal.

## Domänenmodell

- `lunva_user`
  - globale User inkl. `email`, `password_hash`, `display_name`, `role`
- `lunva_refresh_token` / `lunva_access_token`
  - gehashte Tokens, Ablaufdaten, Revocation und Refresh-Rotation
- `lunva_tenant`
  - tenant-scoped Bereich mit eindeutigem `name`
- `lunva_tenant_member`
  - User-Mitgliedschaft pro Tenant mit `TENANT_OWNER` oder `TENANT_MEMBER`
- `lunva_tenant_group`
  - Gruppen innerhalb eines Tenants
- `lunva_tenant_group_member`
  - Gruppenmitgliedschaft mit `GROUP_OWNER` oder `GROUP_MEMBER`
- `lunva_media`
  - Medien mit interner `id`, öffentlicher 16-Zeichen-`public_id`, Tenant, Ersteller und Zugriffsscope
- `lunva_config`
  - technische App-Konfiguration wie bcrypt-Cost

## Auth-Flow

1. `POST /api/auth/login`
   - validiert E-Mail/Passwort
   - erzeugt Access- und Refresh-Token
   - setzt beide Tokens als Cookies

2. Geschützte API-Aufrufe
   - `LunvaAuthenticationMechanism` liest das passende Cookie
   - `LunvaIdentityProvider` validiert Token und baut die `SecurityIdentity`
   - `RequestContextFilter` befüllt den request-scoped `RequestContext`
   - `LunvaHttpSecurityPolicy` blockt anonyme Requests auf geschützte `/api/**` Pfade

3. `POST /api/auth/refresh`
   - liest Refresh-Cookie
   - rotiert Token
   - setzt ein neues Cookie-Paar

4. `POST /api/auth/logout`
   - invalidiert aktuelle Token
   - löscht beide Cookies

## Berechtigungen

Globale Rechte:

- `ServerRole.SERVER_OWNER`
- `ServerRole.SERVER_MEMBER`
- `@ServerRolesRequired(...)` schützt serverweite Aktionen wie `POST /api/tenants`.

Tenant-Rechte:

- `TenantMembershipFilter` greift automatisch auf Routen mit `@PathParam("tenantName")`.
- Unbekannter Tenant ergibt `404`.
- Anonymer Request ergibt `401`.
- Eingeloggter User ohne Tenant-Membership ergibt `403`.
- `@TenantOwnerRequired` ist nur für Owner-only Tenant-Aktionen gedacht.

Gruppenrechte:

- Wer eine Gruppe erstellt, wird `GROUP_OWNER`.
- `@GroupOwnerRequired` schützt Gruppenverwaltung.
- Normale Gruppenmitgliedschaft ist getrennt von Tenant-Mitgliedschaft.

## Library- und Medienzugriff

Ordner, Alben und Medien haben einen eindeutigen Zugriffsscope:

- `TENANT`: alle Tenant-Mitglieder
- `GROUP`: Mitglieder der jeweiligen Tenant-Gruppe
- `USER`: nur der jeweilige User

Child-Ordner und Alben erben den Scope ihres Parent-Ordners. Medien übernehmen den Scope des Albums, in dem sie hochgeladen werden. Tenant-Membership ist Voraussetzung für jede Tenant-API; innerhalb eines sichtbaren Scopes dürfen Mitglieder lesen und schreiben.

## Testing

Backend-Tests laufen mit Quarkus-Test, H2 im MySQL-Modus und Flyway Clean/Migrate pro Testlauf.

Abgedeckt sind:

- Passwort-Hashing und bcrypt-Cost
- Cookie-Erzeugung
- Token-Rotation, Revocation und Cleanup
- Auth-Endpunkte
- Setup-Flow
- Server-Owner Tenant-Erstellung
- Tenant-Membership-Filter
- Gruppen-Scoping und Gruppen-Owner
- Scope-basierte Library-/Media-Sichtbarkeit und `publicId`
