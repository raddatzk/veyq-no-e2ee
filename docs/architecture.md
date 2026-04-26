# Lunva Architektur (aktueller Stand)

## Zielbild derzeit

Die Anwendung ist bewusst auf einen stabilen Auth-Kern reduziert:

- Username/Passwort-Login
- Access-/Refresh-Token als Cookies
- Rollen am User
- saubere Token-Rotation und Revocation-Grundlagen

Fachmodule wie Alben, Assets und Public Shares sind aktuell nicht Teil des aktiven Backends.

## Laufzeit-Architektur

- Ein Container für App-Auslieferung:
  - Quarkus Backend
  - statisch eingebautes Vue-Frontend (`META-INF/resources`)
- Ein separater MariaDB-Container
- Orchestrierung lokal über Docker Compose

Build/Runtime:

- `runtime-jvm` als Standard für Entwicklung und schnellere Iterationen
- `runtime-native` (GraalVM) explizit für Production-Builds

## Backend-Stack

- Quarkus 3.34.x
- Kotlin + Gradle Wrapper
- Hibernate ORM Panache (Kotlin)
- Flyway Migrationen
- Quarkus Security Identity + eigener `AuthFilter`
- Scheduler (Token-Cleanup vorbereitet)

## Domänenmodell (Auth-relevant)

- `lunva_user`
  - Benutzerstammdaten inkl. `password_hash`
- `lunva_role`
  - Rollenstammdaten
- `lunva_user_role`
  - N:M User zu Rolle
- `lunva_access_token`
  - gehashter Access-Token, Laufzeitdaten, FK auf User und Refresh-Token
- `lunva_refresh_token`
  - gehashter Refresh-Token, Laufzeitdaten, optionaler Verweis auf Nachfolger (`rotated_to`)
- `lunva_config`
  - technische App-Konfiguration (z. B. ermittelte bcrypt-Cost)

## Auth-Flow

1. `POST /api/auth/login`
- validiert Credentials
- optionales Passwort-Rehash bei veralteter Cost
- erzeugt Access- und Refresh-Token
- setzt beide Cookies

2. Geschützte API-Aufrufe
- `AuthFilter` liest Access-Cookie
- User-Auflösung über aktiven Access-Token
- setzt `SecurityIdentity` inkl. Rollen

3. `POST /api/auth/refresh`
- liest primär Refresh-Cookie
- invaldiert alte Token gemäß aktuellem Flow
- erzeugt neues Token-Paar
- setzt neue Cookies

4. `POST /api/auth/logout`
- invaldiert aktuelle Token
- setzt beide Cookies auf `Max-Age=0`

## Passwort-Strategie

- Hashing mit `bcrypt`
- Ziel: konfigurierbare Hashzeit (`app.auth.bcrypt.target-ms`)
- Beim ersten Start:
  - Benchmark innerhalb `min-cost..max-cost`
  - Persistenz in `lunva_config` (`auth.bcrypt.cost`)
- Rehash-on-login:
  - falls gespeicherter Hash-Cost kleiner ist als aktuelle Ziel-Cost

## Cookie-Strategie

- Access Cookie:
  - Name konfigurierbar (`app.auth.cookie.access-token-name`)
  - Path `/api`
  - TTL über `app.auth.token.access-token-ttl-minutes`
- Refresh Cookie:
  - Name konfigurierbar (`app.auth.cookie.refresh-token-name`)
  - Path `/api/auth`
  - TTL über `app.auth.token.refresh-token-ttl-minutes`
- Beide:
  - `HttpOnly`
  - `SameSite=Strict`
  - `Secure` über `app.auth.cookie.secure`

## Token-Rotation und Revocation

- Beim Refresh wird ein neues Token-Paar erzeugt.
- Refresh-Tokens können über `rotated_to` verkettet werden.
- Revocation-Logik:
  - Access-Token wird direkt revoked.
  - Refresh-Token-Revocation kann rekursiv auf Nachfolger wirken.
  - Zugehörige Access-Tokens werden über `refresh_token_id` mit revoked.

Hinweis:
- Reuse-Detection und vollständiges Family-Handling sind konzeptionell vorgesehen und können auf Basis der bestehenden Verkettung weiter ausgebaut werden.

## Berechtigungen

- Rollen sind am User über N:M modelliert.
- Rollen werden im `AuthFilter` in die `SecurityIdentity` geladen.
- Method-Level Authorisierung (z. B. `@RolesAllowed`) kann darauf aufsetzen.

## Testing-Strategie

Backend-Tests laufen mit Quarkus-Test + H2 (Flyway-Migrationen beim Teststart):

- Unit:
  - Passwortlogik
  - Cookie-Erzeugung
- Integration:
  - Login/Logout/Refresh-Endpunkte
  - Token-Erzeugung und Revocation-Verhalten
  - Token-Cleanup
  - bcrypt-Cost-Initialisierung

## Nächste sinnvolle Ausbaustufen

1. CSRF-Schutz finalisieren (Origin/Referer und/oder Double-Submit Token)
2. Refresh-Reuse-Detection mit klarer Family-Invalidierung
3. Device-/Session-Management (aktive Sessions, Logout-all)
4. Multi-Tenant-Erweiterung auf Basis globaler User-Identity
