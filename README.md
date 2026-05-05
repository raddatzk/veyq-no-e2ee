# Lunva

Lunva ist eine Quarkus/Vue-Anwendung mit globalen User-Accounts, cookie-basierter Authentifizierung und tenant-scoped Bereichen. Tenants werden aktuell über einen technischen `name` im Pfad adressiert, z. B. `/friends` im Frontend und `/api/friends/groups` im Backend. Domains und TLS liegen bewusst vor Lunva beim Admin-Proxy.

## Projektstruktur

- `backend`: Quarkus 3, Kotlin, Gradle Wrapper, Flyway, Hibernate ORM Panache
- `frontend`: Vue 3 + TypeScript, OpenAPI-generierte API-Clients
- `infra`: Docker Compose für lokale Umgebung
- `http`: IntelliJ HTTP Client Requests
- `docs`: Architektur- und Designnotizen

## Start mit Docker Compose

```bash
cd infra
docker compose up --build
```

Danach:

- App: `http://localhost:8080`
- Health: `http://localhost:8080/q/health`
- MariaDB: `localhost:3306`

## Lokale Entwicklung

Backend:

```bash
cd backend
./gradlew quarkusDev
```

Frontend:

```bash
cd frontend
npm install
npm run dev
```

Backend-Tests:

```bash
cd backend
./gradlew check
```

Frontend-Checks:

```bash
cd frontend
npm run check
```

## Setup

Eine frische Instanz startet ohne Seed-User. Der Setup-Flow ist zweistufig:

1. `POST /api/setup/admin` erstellt den ersten User mit `ServerOwner`.
2. Der eingeloggte Server-Owner kann optional mit `POST /api/tenants` den ersten Tenant inklusive Domain erstellen.

Sobald ein User existiert, ist Admin-Setup gesperrt. Neue Tenants dürfen nur User mit globaler Rolle `ServerOwner` erstellen. Jeder Tenant hat genau eine Domain, die beim Erstellen angegeben wird.

## API

Die Backend-API ist API-first. Die Zielverträge liegen als OpenAPI 3.1 Specs unter [docs/api](/Users/kevin.raddatz/IdeaProjects/lunva/docs/api). Jede Spec-Operation dokumentiert Vorbedingungen, Beispiele, Fehlerfälle und `x-implementation-status`. Backend-Resources implementieren die generierten JAX-RS-Interfaces; JSON-Payloads werden im Backend als Kotlin-Models und im Frontend als TypeScript-Clients/Models aus denselben Specs generiert.

OpenAPI-Domänen:

- `auth`: Login, Logout, Refresh
- `setup`: initiales Setup
- `users`: aktueller User, User-Management, Einladungen
- `tenants`: Tenant-Listen, Tenant-/Gruppen-Settings
- `groups`: Gruppenverwaltung
- `server`: serverweite Settings
- `settings`: Settings-Navigation
- `explorer`: Explorer-Navigation
- `library`: Ordner, Alben, Medien, Uploads

Wichtige globale Endpunkte:

- `GET /api/setup`
- `POST /api/setup/admin`
- `POST /api/auth/login`
- `POST /api/auth/refresh`
- `POST /api/auth/logout`
- `GET /api/users/me`
- `PUT /api/users/me`
- `POST /api/users/me/password`
- `GET /api/tenants`
- `POST /api/tenants`
- `GET /api/users`
- `POST /api/users`
- `PUT /api/users/{userId}/role`
- `POST /api/users/{userId}/invite`
- `POST /api/users/{userId}/invite/accept`
- `GET /api/settings`
- `GET /api/settings/server`
- `POST /api/media/uploads`
- `GET /api/media/uploads`
- `PUT /api/media/uploads/{uploadId}/chunks/{chunkIndex}`
- `POST /api/media/uploads/{uploadId}/complete`

Tenant-Endpunkte:

- `GET /api/tenants/{tenantName}/settings`
- `PUT /api/tenants/{tenantName}/settings`
- `GET /api/tenants/{tenantName}/me`
- `PUT /api/tenants/{tenantName}/me`
- `GET /api/tenants/{tenantName}/{groupName}`
- `PUT /api/tenants/{tenantName}/{groupName}`
- `GET /api/{tenantName}/groups`
- `POST /api/{tenantName}/groups`
- `GET /api/{tenantName}/groups/{groupName}`
- `POST /api/{tenantName}/groups/{groupName}`
- `GET /api/{tenantName}/explorer`
- `POST /api/{tenantName}/folders`
- `POST /api/{tenantName}/albums`
- `GET /api/{tenantName}/media`
- `GET /api/{tenantName}/media/{mediaId}/content`
- `PATCH /api/{tenantName}/media/{mediaId}/metadata`

Passende IntelliJ-HTTP-Requests liegen unter [http](/Users/kevin.raddatz/IdeaProjects/lunva/http).

## API-Codegenerierung

Backend:

- Gradle validiert alle OpenAPI-Specs in `./gradlew check`.
- JAX-RS-Interfaces werden in `backend/build/generated/*-openapi` erzeugt und von den Resource-Klassen implementiert.
- Kotlin-Payload-Models werden in `backend/build/generated/*-models` erzeugt.
- Handgeschriebene API-DTOs sind nicht Teil des öffentlichen Backend-API-Vertrags.

Frontend:

- `npm run generate:api` erzeugt TypeScript-Clients und Models nach `frontend/src/api/generated/<domain>`.
- `frontend/src/api/generatedClient.ts` bindet die generierten Clients an `credentials: include`, `VITE_API_BASE`, Refresh-Flow und die bestehenden App-Fehler.
- Fachliche Frontend-Module importieren weiter schlanke Facades wie `api/authClient.ts` oder `features/explorer/api/mediaApi.ts`; diese delegieren auf generated Clients.
- NDJSON-Streaming und Binary-/Chunk-Uploads bleiben adaptergestützt, verwenden aber generated Request-/Response-Typen.

## Auth- und Security-Modell

- Auth läuft über `LunvaAuthenticationMechanism` und `LunvaIdentityProvider`.
- Access- und Refresh-Tokens werden als `HttpOnly` Cookies gesetzt.
- `LunvaHttpSecurityPolicy` erlaubt nur explizit öffentliche API-Pfade ohne Login.
- `ServerOwner` ist eine globale Server-Rolle am User.
- Neue User werden über `POST /api/users` eingeladen und müssen beim Anlegen direkt einem Tenant zugewiesen werden.
- Einladungslinks werden über die Domain des zugewiesenen Tenants im Format `https://{tenant.domain}/invite/{userId}/{token}` erstellt.
- Tenant-Membership wird im `TenantMembershipFilter` für alle Routen mit `{tenantName}` geprüft.
- `TenantOwnerRequired` und `GroupOwnerRequired` sind Marker-Annotationen für stärkere Owner-Rechte.
- Tenant- und Gruppenrollen werden nicht in globale `SecurityIdentity`-Roles gemischt.

## Medienzugriff

Medien haben eine interne `Long`-ID und eine öffentliche `publicId` mit 16 Zeichen. Die API gibt die `publicId` als `id` nach außen zurück.

Ordner, Alben und Medien verwenden einen Scope:

- `/urlaub`: tenantweiter Scope
- `/@familie/urlaub`: Gruppen-Scope
- `/~me/urlaub`: persönlicher Scope

Alle Mitglieder eines sichtbaren Scopes dürfen dort lesen und schreiben. Medien bleiben in Alben und übernehmen deren Scope.

## Wichtige Konfigurationen

- Datenbank:
  - `DB_URL`
  - `DB_USER`
  - `DB_PASSWORD`
- Cookies:
  - `COOKIE_ACCESS_TOKEN_NAME`
  - `COOKIE_REFRESH_TOKEN_NAME`
  - `COOKIE_SECURE`
- Token TTL:
  - `ACCESS_TOKEN_TTL_MINUTES`
  - `REFRESH_TOKEN_TTL_MINUTES`
- Cleanup:
  - `ACCESS_TOKEN_AUDIT_TTL_DAYS`
  - `REFRESH_TOKEN_AUDIT_TTL_DAYS`
  - `TOKEN_CLEANUP_CRON`
- bcrypt:
  - `BCRYPT_TARGET_MS`
  - `BCRYPT_MIN_COST`
  - `BCRYPT_MAX_COST`
  - `BCRYPT_BENCHMARK_SAMPLES`
- Einladungen:
  - `INVITATION_TTL_DAYS`
  - `INVITATION_MAIL_FROM`
- Medien:
  - `MEDIA_STORAGE_DIR`
  - `MEDIA_CHUNK_SIZE_BYTES`
  - `MEDIA_UPLOAD_SESSION_TTL_HOURS`
  - `MEDIA_UPLOAD_SESSION_CLEANUP_CRON`
  - `MEDIA_MAX_UPLOAD_SIZE`

## Aktueller Testumfang

- Backend: Passwort-, Cookie-, Token-, Token-Cleanup-, Auth-, Setup-, Tenant-, User-Management-, Settings-, Explorer-, Library-, Upload- und Media-Streaming-Tests
- Backend Contract-Tests: OpenAPI-Beschreibungen/Beispiele/Vorbedingungen, implementierte Endpunkte, Resource-Interface-Bindung und generated Payload-Nutzung
- Frontend: `vue-tsc`, ESLint, Vitest und Vite-Build über `npm run check`

## TODO

- manage server users
- manage tenant users
- create group
- manage group
- change email requires email confirmation