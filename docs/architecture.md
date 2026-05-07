# Lunva Architektur

## Zielbild

Lunva läuft als eine Instanz mit globalen Usern und mehreren Tenants. Externe Domains, TLS und Root-Pfad-Rewrites werden von einem vorgelagerten Proxy verwaltet. Das Backend kennt keine Host-Mappings und scoped fachliche Daten über den Tenant-Namen im Pfad.

## Laufzeit

- Quarkus Backend liefert API und eingebautes Vue-Frontend aus.
- MariaDB ist die produktive Datenbank.
- Flyway verwaltet das Schema über versionierte Migrationen.
- Docker Compose startet App und Datenbank lokal.
- OpenAPI-Specs unter `docs/api` sind der öffentliche API-Zielvertrag.
- Backend und Frontend verwenden generierte API-Artefakte aus denselben Specs.

## Domänenmodell

- `lunva_user`
  - globale User inkl. `email`, `password_hash`, `display_name`, `role`, `password_set_at`
- `lunva_user_invitation`
  - gehashte Einladungstokens, Ablaufdatum, Accepted-/Revoked-Zeitpunkte
- `lunva_refresh_token` / `lunva_access_token`
  - gehashte Tokens, Ablaufdaten, Revocation und Refresh-Rotation
- `lunva_tenant`
  - tenant-scoped Bereich mit eindeutigem `name` und eindeutiger `domain`
- `lunva_tenant_member`
  - User-Mitgliedschaft pro Tenant mit `TenantOwner` oder `TenantMember`
- `lunva_tenant_group`
  - Gruppen innerhalb eines Tenants
- `lunva_tenant_group_member`
  - Gruppenmitgliedschaft mit `GroupOwner` oder `GroupMember`
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

## Setup- und Einladungs-Flow

1. `GET /api/setup`
   - liefert, ob initiales Admin-Setup oder Tenant-Erstellung im Frontend angeboten werden soll

2. `POST /api/setup/admin`
   - erstellt den ersten globalen Server-Owner
   - ist gesperrt, sobald ein User existiert

3. `POST /api/tenants/{tenantName}/users/invite`
   - lädt durch einen TenantOwner einen User per E-Mail in den Tenant ein
   - verwendet einen bestehenden globalen User mit gleicher E-Mail oder erstellt einen Pending-User
   - erzeugt eine Einladung mit TTL und sendet einen Link im Format `https://{tenant.domain}/invite/{userId}/{token}`

4. `POST /api/users/{userId}/invite/accept`
   - akzeptiert die Einladung mit `{ password, token }`
   - setzt das Passwort und Auth-Cookies

## Berechtigungen

Globale Rechte:

- `ServerRole.ServerOwner`
- `ServerRole.ServerMember`
- `@ServerRolesRequired(...)` schützt serverweite Aktionen wie `POST /api/tenants` und User-Management.

Tenant-Rechte:

- `TenantMembershipFilter` greift automatisch auf Routen mit `@PathParam("tenantName")`.
- Unbekannter Tenant ergibt `404`.
- Anonymer Request ergibt `401`.
- Eingeloggter User ohne Tenant-Membership ergibt `403`.
- `@TenantOwnerRequired` ist nur für Owner-only Tenant-Aktionen gedacht.

Gruppenrechte:

- Wer eine Gruppe erstellt, wird `GroupOwner`.
- `@GroupOwnerRequired` schützt Gruppenverwaltung.
- Normale Gruppenmitgliedschaft ist getrennt von Tenant-Mitgliedschaft.

## API-first Vertrag

- Jede Backend-API-Domäne hat eine eigene OpenAPI-Datei: `auth`, `setup`, `users`, `tenants`, `groups`, `server`, `settings`, `explorer`, `library`.
- Operationen enthalten `summary`, `description`, Beispiele, Fehlerfälle, `x-preconditions`, `x-implementation-status` und bei Bedarf Rollen-/Membership-Hinweise.
- Der Gradle-Build generiert JAX-RS-Interfaces und Kotlin-Models. Resource-Klassen implementieren die generierten Interfaces direkt.
- Services konvertieren an Domain-Grenzen zwischen Domain-Entities/-Enums und generated API-Models.
- `OpenApiContractTest` prüft Spec-Vollständigkeit, implementierte Endpunkte, Resource-Interface-Bindung und dass JSON-Signaturen generated Models verwenden.
- Das Vue-Frontend generiert TypeScript-Fetch-Clients nach `frontend/src/api/generated/<domain>` und nutzt sie über `frontend/src/api/generatedClient.ts`.
- Frontend-spezifische Facades bleiben als stabile Imports erhalten; sie kapseln generated Clients, Error-Mapping, Cookie-Refresh und Media-Sonderfälle.

## Library- und Medienzugriff

Ordner, Alben und Medien haben einen eindeutigen Zugriffsscope:

- `TENANT`: alle Tenant-Mitglieder
- `GROUP`: Mitglieder der jeweiligen Tenant-Gruppe
- `USER`: nur der jeweilige User

Child-Ordner und Alben erben den Scope ihres Parent-Ordners. Medien übernehmen den Scope des Albums, in dem sie hochgeladen werden. Tenant-Membership ist Voraussetzung für jede Tenant-API; innerhalb eines sichtbaren Scopes dürfen Mitglieder lesen und schreiben.

Medien werden über `publicId` adressiert. Metadatenlisten können als NDJSON gestreamt werden; Binärcontent unterstützt Range-Requests. Große Uploads laufen über Upload-Sessions mit Chunks unter `/api/media/uploads`.

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
- OpenAPI-Contract-Tests für API-first Bindung
- Frontend-Codegen-Facades, NDJSON-Parsing, Upload-Composables und UI-Komponenten über Vitest
