# Lunva (Auth-Only)

Aktueller Stand ist bewusst auf Auth reduziert. Album-/Asset-/Share-Funktionalität ist derzeit nicht Bestandteil des aktiven Codes.

## Scope

- Backend: Quarkus API mit Username/Passwort-Login, Access-/Refresh-Token als Cookies
- Frontend: minimale Shell mit Navigation und Login-Seite
- Datenbank: MariaDB mit Flyway-Migrationen

## Projektstruktur

- `backend`: Quarkus 3, Kotlin, Gradle Wrapper
- `frontend`: Vue 3 + TypeScript
- `infra`: Docker Compose für lokale Umgebung
- `Dockerfile` im Root: Multi-Stage Build für Frontend + Backend in einem Container

## Start mit Docker Compose

```bash
cd infra
docker compose up --build
```

Danach:

- App: `http://localhost:8080`
- Health: `http://localhost:8080/q/health`
- MariaDB: `localhost:3306`

Build-Targets:

- Default: `runtime-jvm` (schneller für Entwicklung/E2E)
- Native (Production): `runtime-native`

```bash
cd infra
LUNVA_BUILD_TARGET=runtime-native docker compose up --build
```

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

## Seed-Daten

Der Initial-Seed wird über Flyway angelegt:

- Username: `admin`
- Passwort: `admin123`

## API (aktuell)

- `POST /api/auth/login`
- `POST /api/auth/refresh`
- `POST /api/auth/logout`
- `GET /api/users/me`

Passende IntelliJ-HTTP-Requests liegen unter [http/auth.http](/Users/kevin.raddatz/IdeaProjects/lunva/http/auth.http).

## Auth- und Security-Modell

- Cookie-basierte Auth mit zwei Tokens:
  - Access Token Cookie (`Path=/api`)
  - Refresh Token Cookie (`Path=/api/auth`)
- Cookies sind `HttpOnly`, `SameSite=Strict`, `Secure` konfigurierbar über `app.auth.cookie.secure`
- Zugriff auf geschützte Endpunkte läuft über `AuthFilter` + `SecurityIdentity`
- Passwörter werden mit `bcrypt` gehasht
- Beim ersten Start wird die bcrypt-Cost gegen ein Zielbudget benchmarked und in `lunva_config` gespeichert
- Rehash-on-login bei zu niedriger Cost

## Wichtige Konfigurationen (Backend)

Beispiele aus `backend/src/main/resources/application.properties`:

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

## Aktueller Testumfang (Backend)

- Unit:
  - `PasswordServiceTest`
  - `CookieServiceTest`
- Integration (Quarkus + DB + Flyway):
  - `AuthResourceTest`
  - `TokenServiceTest`
  - `TokenCleanupTest`
  - `BcryptCostServiceTest`
