# Veyq Architektur

## Zielbild

Veyq läuft als eine Instanz mit globalen Usern und mehreren Tenants. Externe Domains, TLS und Root-Pfad-Rewrites werden von einem vorgelagerten Proxy verwaltet. Das Backend kennt keine Host-Mappings und scoped fachliche Daten über den Tenant-Namen im Pfad.

## Laufzeit

- Quarkus Backend liefert API und eingebautes Vue-Frontend aus.
- MariaDB ist die produktive Datenbank.
- Flyway verwaltet das Schema über versionierte Migrationen.
- Docker Compose startet App und Datenbank lokal.
- OpenAPI-Specs unter `docs/api` sind der öffentliche API-Zielvertrag.
- Backend und Frontend verwenden generierte API-Artefakte aus denselben Specs.

## Domänenmodell

- `veyq_user`
  - globale User inkl. `email`, `password_hash`, `display_name`, `role`, `password_set_at`
- `veyq_user_invitation`
  - gehashte Einladungstokens, Ablaufdatum, Accepted-/Revoked-Zeitpunkte
- `veyq_refresh_token` / `veyq_access_token`
  - gehashte Tokens, Ablaufdaten, Revocation und Refresh-Rotation
- `veyq_tenant`
  - tenant-scoped Bereich mit eindeutigem `name` und eindeutiger `domain`
- `veyq_tenant_member`
  - User-Mitgliedschaft pro Tenant mit `TenantOwner` oder `TenantMember`
- `veyq_tenant_group`
  - Gruppen innerhalb eines Tenants
- `veyq_tenant_group_member`
  - Gruppenmitgliedschaft mit `GroupOwner` oder `GroupMember`
- `veyq_media_blob`
  - tenant-scoped, content-addressed Originale und Previews mit internem SHA-256, Storage-Key, Preview-Storage-Key, MIME-Type, Byte-Größe und extrahierten Kamera-/GPS-/Dimensionsdaten
- `veyq_media`
  - Memory-spezifische Medien-Assets mit interner `id`, öffentlicher 16-Zeichen-`public_id`, Tenant, Uploader oder Share-Identität, Zugriffsscope, direkter Memory-Zuordnung, Titel, Originaldateiname und Referenz auf `veyq_media_blob`
- `veyq_memory`
  - neue Memory-Zielstruktur mit öffentlicher `public_id`, Parent-Memory, Zugriffsscope, Cover-Media und direkter Media-Zuordnung
- `veyq_config`
  - technische App-Konfiguration wie bcrypt-Cost

## Auth-Flow

1. `POST /api/auth/login`
   - validiert E-Mail/Passwort
   - erzeugt Access- und Refresh-Token
   - setzt beide Tokens als Cookies

2. Geschützte API-Aufrufe
   - `VeyqAuthenticationMechanism` liest das passende Cookie
   - `VeyqIdentityProvider` validiert Token und baut die `SecurityIdentity`
   - `RequestContextFilter` befüllt den request-scoped `RequestContext`
   - `VeyqHttpSecurityPolicy` blockt anonyme Requests auf geschützte `/api/**` Pfade

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

- Jede Backend-API-Domäne hat eine eigene OpenAPI-Datei: `auth`, `setup`, `users`, `tenants`, `groups`, `server`, `settings`, `memories`.
- Operationen enthalten `summary`, `description`, Beispiele, Fehlerfälle, `x-preconditions`, `x-implementation-status` und bei Bedarf Rollen-/Membership-Hinweise.
- Der Gradle-Build generiert JAX-RS-Interfaces und Kotlin-Models. Resource-Klassen implementieren die generierten Interfaces direkt.
- Services konvertieren an Domain-Grenzen zwischen Domain-Entities/-Enums und generated API-Models.
- `OpenApiContractTest` prüft Spec-Vollständigkeit, implementierte Endpunkte, Resource-Interface-Bindung und dass JSON-Signaturen generated Models verwenden.
- Das Vue-Frontend generiert TypeScript-Fetch-Clients nach `frontend/src/api/generated/<domain>` und nutzt sie über `frontend/src/api/generatedClient.ts`.
- Frontend-spezifische Facades bleiben als stabile Imports erhalten; sie kapseln generated Clients, Error-Mapping, Cookie-Refresh und Media-Sonderfälle.

## Memory- und Medienzugriff

Ordner, Memories und Medien haben einen eindeutigen Zugriffsscope:

- `TENANT`: alle Tenant-Mitglieder
- `GROUP`: Mitglieder der jeweiligen Tenant-Gruppe
- `USER`: nur der jeweilige User

Child-Ordner und root-level Memories in Ordnern erben den Scope ihres Parent-Ordners. Child-Memories erben den Scope ihres Parent-Memory. Medien übernehmen den Scope der Memory, in die sie hochgeladen werden. Tenant-Membership ist Voraussetzung für jede Tenant-API; innerhalb eines sichtbaren Scopes dürfen Mitglieder lesen und schreiben.

Medien werden öffentlich über `Media.publicId` adressiert. Memory-Media-Listen laufen über `/api/{tenantName}/memories/{memoryId}/media`; Binärcontent und Previews laufen über `/api/{tenantName}/memory-media/{mediaId}/...` und unterstützen Range-Requests für Originalcontent. Intern liest Content über `Media.blob.storageKey`; Hashes, Blob-IDs und Storage-Keys werden nicht über öffentliche APIs exponiert.

## Memory-Schicht

Memory UI v1 und die Memory APIs sind die aktive fachliche Ebene. Tenant Home, persönliche Bereiche, Gruppenbereiche, Ordner und Memory-Details werden über Memory-Navigation, Folder- und Memory-Endpunkte abgebildet.

Memories sind verschachtelbar und gehören zu genau einem Tenant-, Group- oder User-Scope. Child-Memories erben den Scope ihres Parent-Memory. Neue Memory-Media-Uploads schreiben die direkte `memory_id` an `veyq_media`; ein Asset gehört im Memory-Modell genau zu einem Memory. Identische Uploads innerhalb eines Tenants können denselben `veyq_media_blob` wiederverwenden, bleiben aber getrennte `veyq_media`-Assets. `includeChildren=true` bei Memory-Media-Listen aggregiert Medien aus sichtbaren Descendant-Memories, ohne die direkte Ownership zu verändern.

Originale und Previews gehören zu `veyq_media_blob`. Previews werden pro Blob gespeichert und wiederverwendet. Beim Löschen eines Media-Assets wird der Blob erst entfernt, wenn kein weiteres Media mehr darauf zeigt. Explizite Memory-Cover-Uploads bleiben davon getrennte Memory-Dateien; Cover können alternativ auf ein bestehendes Memory-Media-Asset zeigen.

Die Migration `V2__memory_model.sql` legt die Memory-Tabelle und die direkte `memory_id`-Zuordnung auf Medien an. `V10__media_blobs.sql` trennt tenant-scoped Blob-Speicher von Memory-spezifischen Media-Assets. Album-Tabellen sind aus dem Schema entfernt; nicht-produktive Alt-Daten werden nicht weiter migriert.

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
- Scope-basierte Memory-/Media-Sichtbarkeit und `publicId`
- Memory-Tree-Invarianten, Scope-Sichtbarkeit, Memory-Media-Ownership und Cover-Regeln
- OpenAPI-Contract-Tests für API-first Bindung
- Frontend-Codegen-Facades, NDJSON-Parsing, Upload-Composables und UI-Komponenten über Vitest
