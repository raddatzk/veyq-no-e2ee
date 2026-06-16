# Härtungsrunde 2 + Medien-Tiering — Ablaufplan

Folgeplan nach der Vollreview vom 2026-06-16 (Stand: nach Abschluss von `hardening-plan.md`).
Bündelt die Review-Findings mit dem neuen Medien-Tiering-Feature (Thumbnail/Preview/Original).

**Reihenfolge-Prinzip:** zuerst die kleinen Sicherheits-Regressionen schließen, die die gerade
abgeschlossene Härtung untergraben (billig, hoher Wert) → dann Crypto-/Interop-Korrektheit →
dann das Medien-Feature (großer Produktblock, fixt zugleich die Galerie-Perf/Memory-Punkte) →
dann Backend-Performance → dann Wartbarkeit → zuletzt CI/Backlog.

Effort: **S** < 1 h · **M** ein paar Stunden · **L** Tag+ / mehrere Plattformen.
Status: `[ ]` offen · `[~]` in Arbeit · `[x]` erledigt.

---

## Phase A — Sicherheits-Regressionen schließen (klein, hoher Wert, zuerst)
- [x] **A1 · X-Forwarded-For Rate-Limit-Bypass** *(Security, S)* — `RateLimitFilter.kt`.
  XFF wird jetzt nur vertraut, wenn der Socket-Peer ein konfigurierter Trusted-Proxy ist
  (`veyq.ratelimit.trusted-proxies`, CIDR/IP, leer = keiner) — dann der rechteste Nicht-Proxy-Hop, sonst der
  Socket-Peer. Spoofing des Headers mintet keinen frischen Bucket mehr. Pure `resolveClientIp` + 5 Unit-Tests
  (IPv4/IPv6/CIDR, untrusted Peer, kein XFF).
- [x] **A2 · Session-Token raus aus der WebSocket-URL-Query** *(Security, plattformübergreifend, M)* —
  Neuer `JournalLiveTicketService` + `POST /api/journal-live/ticket` (authed → kurzlebiges 30s-Single-Use-
  Ticket); der Relay authentifiziert Member jetzt über `?ticket=` statt `?token=` (Session-Token nie in der
  URL). **Wichtig:** alle drei Provider holen ein *frisches* Ticket pro (Re)Connect via URL-Factory — ein
  fest verdrahtetes Ticket würde Reconnect brechen, sobald es verbraucht ist (Web `EncryptedRelayProvider`,
  iOS/Android `JournalLiveProvider`). Share-Pfad (`?share=`) unverändert (wiederverwendbares Capability).
  Verifiziert: Web journalLive- + persistence-E2E grün (Member-Konvergenz + Bad-Ticket-Reject + Share-Live);
  Android `:core`/:app + iOS-Package kompilieren.
- [x] **A3 · Share-Contribute kann fremde Scope-Blobs anhängen (IDOR)** *(Security, S)* —
  `PublicShareContributeHandler.createShareMedia` prüft jetzt, dass `originalBlobId` + jedes Derivat-`blobId`
  von einem COMPLETED-Upload **dieses** Shares stammen (`createdBy == "share:<id>"`, via
  `BlobUploadRepository.existsCompletedBy` — dedup-sicher, da die abgeschlossene Upload-Row die Attribution
  hält). E2E erweitert: Legit-Contribution grün, Forge eines fremden Blobs → 403 `BLOB_NOT_FROM_SHARE`.
- [x] **A4 · Single-Server-Owner-Setup TOCTOU** *(Security, S)* — `createServerOwner` row-lockt jetzt eine
  geseedete Singleton-Row (`veyq_server_setup`, V27, `ServerSetupRepository.lockForSetup`,
  `PESSIMISTIC_WRITE`) **vor** der `isComplete()`-Prüfung, sodass parallele Setups serialisieren und der
  Verlierer ein sauberes 409 statt eines 500 bekommt. Direkt verifiziert: zwei gleichzeitige
  `POST /api/setup/owner` → genau ein 200 + ein 409 `ALREADY_SETUP`.
- [~] **A5 · Android: rohe Response-Bodies in Exceptions + ungeescapte JSON-Interpolation** *(Security, Android, M)* —
  **Teil 1 erledigt:** alle 44 `${resp.body()}`-Interpolationen aus den Throws über 18 `:core`-Dateien entfernt
  (nur noch der Statuscode — keine Server-Payloads mehr in Crash-Logs); `:core` kompiliert.
  **Teil 2 verschoben nach E3:** die 56 Triple-Quote-JSON-Bodies (10 Dateien) → `JSONObject`/`JSONArray`
  migrieren beim zentralen `ApiClient`-Refactor (riskanter: Wire-Format, per Android-E2E zu verifizieren).

## Phase B — Crypto-/Interop-Korrektheit
- [x] **B1 · Invite-Passphrase Argon2-Parameter angeglichen** *(Security/Interop, M)* — **Echter Interop-Bug
  bestätigt:** die Invite-Temp-Key-KEK-Params werden NICHT im Link getragen (nur Salt), und Mobile nutzte
  `inviteArgon m=65536` während Web `kekRecovery` den `ARGON`-Default m=32768 nutzt → ein passwortgeschützter
  Invite, der auf Web gemintet und auf Mobile (oder umgekehrt) eingelöst wird, leitet eine andere KEK ab →
  AEAD-Fehler. NFC war bereits konsistent (alle drei normalisieren). Fix: iOS/Android `inviteArgon` 65536 →
  **32768** (= Web + 32-MiB-Projektstandard). Verifiziert: Params auf allen drei Plattformen gleich,
  Web-Round-Trip-Unit-Test grün, iOS/Android kompilieren. Cross-Platform-Pin folgt in **B2**.
- [x] **B2 · Conformance-Lücken geschlossen** *(Tests/Crypto, S)* — (a) `journalSeal`→`journalOpen`
  Round-Trip pro Plattform asserted (Seal-Seite war ungetestet); (b) der `hkdf_sha256_rfc5869_case1`-KAT
  jetzt in allen drei Conformance-Tests (Web `hkdfSha256`-Export, iOS via `@testable`, Android `hkdf`
  `private`→`internal`). Alle drei Suiten grün. Kein extra Invite-Vektor nötig: `recovery_argon2id` pinnt
  `kekRecovery` bereits cross-platform — zusammen mit B1 (Param-Alignment) ist die Invite-KEK abgedeckt.
- [x] **B3 · Journal erster Append serialisieren statt 500** *(Korrektheit, S)* — `MemoryTreeHandler.createNode`
  legt für Memory/Chapter jetzt bei Geburt eine leere Journal-Row an (suiteId = Node-Content-Suite, kein
  Literal), sodass `lockedJournal.findByIdForUpdate` immer trifft und zwei parallele Erst-Appends nicht in
  eine Unique-Violation rennen. Eine leere Row liest identisch zu „noch kein Journal". Verifiziert:
  journalPersistence- + memoryTree-E2E grün (Append + Compact auf frischer Memory).
- [x] **B4 · `suiteId = "v1"` aus Guest-Read-Pfaden entfernt** *(Korrektheit, S)* — `getShareChunk` gibt jetzt
  `blob.suiteId` zurück (Blob lookup), `getPublicShare` den `share.suiteId` fürs passwordSalt-Blob (beide ohne
  Migration — Share trägt bereits `suiteId`, Blobs seit P4.2). Verifiziert: share-E2E grün (Chunk-Download +
  passwortgeschützter Share).

## Phase C — Medien-Tiering (Feature) + Galerie-Perf/Memory (Web → iOS → Android)
Zielbild: **Grid → Thumbnail · Lightbox/Vorschau → Preview · Original → nur per Download-Button.**
Entschieden: Preview ≈ **2048 px Langkante, JPEG q0.85**; Original-Zugriff über expliziten **Download/Speichern-Button**.
Bestandsmedien ohne Preview ⇒ Fallback Thumbnail→Original bleibt erhalten (greenfield, kein Backfill).
Diese Phase fixt zugleich H4/H5/M11 aus der Review, weil sie denselben Galerie-Code anfasst.

- [x] **C1 · Web — Upload: PREVIEW erzeugen** — `clientMedia.ts` generischer `downscale(maxDim,quality)`,
  erzeugt Thumbnail (400/0.8) + Preview (2048/0.85); `media.ts uploadMedia` + `share.ts uploadShareMedia`
  laden das Preview-Blob als `PREVIEW`-Derivat, `NewMedia` um `preview?` erweitert.
- [x] **C2 · Web — Render: Preview im Lightbox, Original nur per Download** — `gallery.ts` (`GalleryTile.preview`,
  neues `loadPreview`, `loadOriginal` nur noch für Download); `MemoryDetailView.vue` + `ShareView.vue` Lightbox →
  Preview, neuer „Download"-Button speichert das Original als Datei (Bulk-ZIP-Download nutzt ohnehin Originale).
- [x] **C3 · Web — Object-URL-Leak + Navigations-Race behoben** — `HomeView.openLevel` revoked alte Cover-URLs
  vor Reassign + in `onBeforeUnmount`; `loadSeq`-Generation-Guard verwirft überholte Level-/Cover-Loads (revoked
  deren späte URLs). (= Review H4 + M11.)
- [x] **C4 · Web — Tile-Downloads bounded-parallel** — neuer `shared/pool.ts mapPool` (~6 concurrent, ordnung
  erhaltend); `gallery.ts`/`share.ts` Tile-Loops darauf umgestellt.
  *Verifiziert: vue-tsc grün, 63 Unit-Tests, alle 28 Web-E2E + share-E2E grün; Media-E2E um den Preview-Tier
  erweitert (Thumbnail+Preview+Original = 3 distinkte Blobs, je byte-identisch zurück).* (C9-Web-Teil.)
- [x] **C5 · iOS — Upload PREVIEW + Render-Tiers** — `MemoryDetailView` `downscale(maxDim,quality)` (thumbnail
  400/0.8 + preview 2048/0.85), `Media.uploadMedia` lädt das Preview-Derivat; Tile trägt `preview`/`original`,
  Lightbox zeigt erst das Thumbnail und lädt dann die **Preview** nach; neuer „Download"-Button reicht das
  Original ans Share-Sheet (Save to Files/Fotos), wie der Bulk-ZIP.
- [x] **C6 · iOS — Galerie bounded-parallel + Cover-Cache** — Thumbnail-Bytes per `TaskGroup` (~6 in flight,
  Data ist Sendable → Decode danach auf dem MainActor); `HomeStore` behält bereits entschlüsselte Cover über
  Navigationen (prune auf sichtbare + nur Fehlende nachladen) statt sie pro Level zu verwerfen. iOS-App
  kompiliert (xcodebuild, 0 Fehler).
- [ ] **C7 · Android — Upload PREVIEW + Render-Tiers** *(Feature, M)* — `GalleryScreen.kt:191` (`preview`-Variante),
  `Media.kt:152` (`uploadMedia` lädt Preview-Derivat); Lightbox → **Preview** statt Thumbnail, Download/Save
  (MediaStore/Share) lädt Original on-demand.
- [ ] **C8 · Android — Galerie bounded-parallel + Downsampling + O(n²)-Fixes** *(Perf/Memory, M)* — `GalleryScreen.kt`:
  `inSampleSize`-Downsampling, `Semaphore`-begrenzt parallel; Download-Akkumulation in `ByteArrayOutputStream`
  (`Media.kt:127`), Cover-Map nicht pro Item neu bauen (`HomeScreen.kt:445`).
- [ ] **C9 · E2E je Plattform** *(Tests, S)* — Media-E2E erweitern: Preview-Derivat wird erzeugt, Grid nutzt
  Thumbnail, Lightbox Preview, Download zieht Original. Über `run_web_e2e.py` bzw. mobile E2E.

## Phase D — Backend-Performance
- [ ] **D1 · Blob-IO aus der DB-Transaktion** *(Perf, L)* — `BlobUploadHandler.kt:106,156`, `PublicShareMediaHandler.kt:62`
  (= verschobenes P3.2). Store-IO unter gehaltenem Row-Lock; kritisch sobald der Store remote wird. Frame
  vor der Tx stagen (Store ist idempotent) bzw. Metadaten-Tx committen, dann IO.
- [ ] **D2 · `MySpacesHandler` N+1** *(Perf, M)* — `MySpacesHandler.kt:58-90`. Pro Membership `findById`/`find`
  in Schleife (Tenant doppelt). Fix: Kandidaten-Scopes/Tenants + User-Grants je per IN-Query, dann im Speicher
  zusammensetzen.
- [ ] **D3 · Share-Chunk-ACL nicht pro Chunk neu scannen** *(Perf, M)* — `PublicShareMediaHandler.kt:57-73`.
  `blobBacksSubtreeMedia` materialisiert den ganzen Subtree je Chunk-Fetch. Fix: erlaubte Blob-ID-Menge pro
  Share cachen bzw. gezielter Lookup statt Subtree-Materialisierung.
- [ ] **D4 · JournalRelay Room-Index + Fan-out-Budget** *(Perf/Security, M)* — `JournalRelay.kt:102` scannt alle
  offenen Verbindungen je Frame; per-connection-Limit erlaubt Amplifikation. Fix: `room → Set<connId>`-Index,
  per-Room Aggregat-Byte/Msg-Budget, Connections-pro-User cappen.

## Phase E — Wartbarkeit / Aufräumen
- [ ] **E1 · `TenantAdminGuard` extrahieren** *(Maintainability, M)* — Owner-ACTIVE-Check in ~7 Admin-Handlern
  dupliziert (`RemoveMemberHandler:32`, `ChangeMemberRoleHandler:28`, `ScopeRotationHandler:48`, …). Analog zu
  `ScopeAccessGuard`, aber für den Admin-Gate. **Gemeinsam mit P4.3** (Enum-Migration) erledigen — der Guard
  wird die eine Stelle mit typisiertem Vergleich.
- [ ] **E2 · `toApiBatch` teilen** *(Maintainability, S)* — `MediaHandler.kt:51` ↔ `PublicShareMediaHandler.kt:76`
  byte-identisch. In gemeinsamen `MediaApiMapper` ziehen.
- [ ] **E3 · Zentralisierungen (P4.2-Reste + Clients)** *(Maintainability, M)* — Backend `unauthorized()`/`notFound()`/
  `requireSuite`/Base64 → `platform/error`/Helper; Web `b64url`/`fromB64url` (21×) → `shared/base64.ts`;
  Android ein `ApiClient` (ein `HttpClient`, zentrales Send/Fehler-Handling, 9× dedup — deckt A5 Teil 1 mit
  ab) **und A5 Teil 2**: die 56 Triple-Quote-JSON-Bodies (10 Dateien) auf `JSONObject`/`JSONArray` umstellen,
  per Android-E2E verifiziert.
- [ ] **E4 · P4.3 String → typisierte Enums** *(Maintainability, L)* — Roles/Status/Scope-Kind in Entities
  (`Tenancy.kt:21` u. a.). War bewusst zurückgestellt; jetzt mit **E1** koppeln (Guard + Enums zusammen).
- [ ] **E5 · iOS Live-Journal-Teardown auf Dismiss** *(Quality, S)* — `LiveJournalView.swift:83`: `.onDisappear`
  → `model.stop`; `stop()` idempotent machen (Android via `DisposableEffect` bereits korrekt).
- [ ] **E6 · Web/Mobile Galerie-Duplikate** *(Maintainability, M)* — `useGallery`-Composable (Web: ShareView ↔
  MemoryDetailView ~60 Zeilen identisch); Node-Graph pro Scope cachen (`home.ts`/`search.ts` re-listen sonst
  pro Aktion). Teils schon in Phase C berührt.

## Phase F — CI / Tests / Backlog
- [ ] **F1 · Apple/Android E2E in CI** *(CI, L)* — macOS-Xcode- / Android-SDK+Emulator-Runner gegen Live-Backend
  (analog `run_web_e2e.py`). Schließt P5.2 vollständig ab.
- [ ] **F2 · Web-E2E-Job in Actions real verifizieren** *(CI, S)* — der `web-e2e`-Job lief lokal grün, aber noch
  nie in GitHub Actions; bei nächstem Push/PR prüfen.

### Backlog (unverändert, nicht eingeplant)
- **Android Yrs-Binding als AAR publishen** (yniffi → `yrs-android`, committete Binaries droppen).
- **Mobile Share-Live-Read** (Live-Push an verbundene Share-Gäste; Gäste sind Browser, daher niedrige Prio).
- **Share-Read/Chunk-Download Byte-Quota** (D2-Deferral aus Runde 1: GETs der Public-Shares sind ungedrosselt).
- **MalformedInputMapper enger fassen** (eigene `MalformedInputException` statt globalem IAE-Catch) — optional.

---

### Empfohlener Einstieg
**Phase A** (A1 + A3 + A4 sind je S, A2/A5 je M) — kleine, hochwertige Sicherheits-Fixes, die die gerade
abgeschlossene Härtung absichern. Danach **B1/B2** (Crypto-Interop), dann der große Produktblock **Phase C**
(Medien-Tiering). Phase C kann auf Wunsch vorgezogen werden, wenn Produkt-Momentum wichtiger ist als die
A-Quick-Wins.
