# Lunva

Lunva ist eine Quarkus/Vue-Anwendung mit globalen User-Accounts, cookie-basierter Authentifizierung und tenant-scoped Bereichen. Tenants werden aktuell über einen technischen `name` im Pfad adressiert, z. B. `/friends` im Frontend und `/api/friends/groups` im Backend. Domains und TLS liegen bewusst vor Lunva beim Admin-Proxy.

## Projektstruktur

- `backend`: Quarkus 3, Kotlin, Gradle Wrapper, Flyway, Hibernate ORM Panache
- `frontend`: Vue 3 + TypeScript
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

Tests:

```bash
cd backend
./gradlew test
```

## Setup

Eine frische Instanz startet ohne Seed-User. Der Setup-Flow ist zweistufig:

1. `POST /api/setup/admin` erstellt den ersten User mit `SERVER_OWNER`.
2. Der eingeloggte Server-Owner kann optional mit `POST /api/tenants` den ersten Tenant erstellen.

Sobald ein User existiert, ist Admin-Setup gesperrt. Neue Tenants dürfen nur User mit globaler Rolle `SERVER_OWNER` erstellen.

## API

Globale Endpunkte:

- `GET /api/setup`
- `POST /api/setup/admin`
- `POST /api/auth/login`
- `POST /api/auth/refresh`
- `POST /api/auth/logout`
- `GET /api/me`
- `GET /api/tenants`
- `POST /api/tenants`

Tenant-Endpunkte:

- `GET /api/{tenantName}/groups`
- `POST /api/{tenantName}/groups`
- `GET /api/{tenantName}/groups/{groupName}`
- `POST /api/{tenantName}/groups/{groupName}`
- `GET /api/{tenantName}/media`

Passende IntelliJ-HTTP-Requests liegen unter [http](/Users/kevin.raddatz/IdeaProjects/lunva/http).

## Auth- und Security-Modell

- Auth läuft über `LunvaAuthenticationMechanism` und `LunvaIdentityProvider`.
- Access- und Refresh-Tokens werden als `HttpOnly` Cookies gesetzt.
- `LunvaHttpSecurityPolicy` erlaubt nur explizit öffentliche API-Pfade ohne Login.
- `SERVER_OWNER` ist eine globale Server-Rolle am User.
- Tenant-Membership wird im `TenantMembershipFilter` für alle Routen mit `{tenantName}` geprüft.
- `TenantOwnerRequired` und `GroupOwnerRequired` sind Marker-Annotationen für stärkere Owner-Rechte.
- Tenant- und Gruppenrollen werden nicht in globale `SecurityIdentity`-Roles gemischt.

## Medienzugriff

Medien haben eine interne `Long`-ID und eine öffentliche `publicId` mit 16 Zeichen. Die API gibt die `publicId` als `id` nach außen zurück.

Die Media-Liste wird repository-seitig gefiltert:

- Owner sieht eigene Medien.
- Direkt freigegebene User sehen Medien.
- Mitglieder freigegebener Gruppen sehen Medien.
- Server-Owner und Tenant-Owner bekommen keinen automatischen Medienzugriff.

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

## Aktueller Testumfang

- Passwort-, Cookie-, Token- und Token-Cleanup-Tests
- Auth-Resource-Tests für Login/Logout/Refresh
- Multi-Tenant-Resource-Tests für Setup, Tenant-Erstellung, Tenant-Membership-Filter, Gruppen und Media-Sichtbarkeit
