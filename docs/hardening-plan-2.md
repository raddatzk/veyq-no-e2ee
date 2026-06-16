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
- [x] **C7 · Android — Upload PREVIEW + Render-Tiers** — `GalleryScreen` `downscale(maxDim,quality)` (thumb
  400/80 + preview 2048/85), `Media.uploadMedia` lädt das Preview-Derivat; Tile trägt `preview`/`original`,
  Lightbox lädt die **Preview** nach (Thumbnail als Platzhalter), neuer „Download"-Button speichert das Original
  (MediaStore auf API 29+, sonst app-eigener Picture-Ordner — permission-frei).
- [x] **C8 · Android — Galerie bounded-parallel + O(n²)-Fixes** — Thumbnail-Loads `Semaphore(6)`-parallel,
  Preview-Decode mit `inSampleSize=2`; Blob-Download akkumuliert in `ByteArrayOutputStream` statt `out += frame`
  (O(n²)); `HomeScreen`-Cover behalten (prune+nur-Fehlende, parallel, einmaliges Merge statt Map-Rebuild pro Cover).
- [x] **C9 · E2E** — Media-E2E (Web + Android) asserten den Preview-Tier (Thumbnail+Preview als getrennte
  Derivate). Web: 3 distinkte Blobs, je byte-identisch. Android: `MediaE2ETest` live grün gegen ein frisches
  Backend (THUMBNAIL+PREVIEW erzeugt). iOS: App + Package kompilieren (uploadMedia-Preview-Param default-nil).

## Phase D — Backend-Performance
- [~] **D1 · Blob-IO aus der DB-Transaktion** *(Perf, L)* — **Lese-Pfad (hot, Downloads) erledigt:**
  `getBlobChunk` + `getShareChunk` lösen die Metadaten (Auth/ACL/objectKey/suite) in einer kurzen
  `QuarkusTransaction.requiringNew()` auf und lesen die Bytes danach OHNE gehaltene DB-Connection aus dem
  Store — relevant sobald der Store remote ist. Verifiziert: media- + share-Download-E2E grün.
  **Verschoben:** Schreib-Pfad (`putBlobChunkAs` schreibt unter Row-Lock) + complete/GC-Deletes — write-outside-tx
  hat eine Orphan-Frame-Subtilität (über-Budget abgelehnte Frames ohne Chunk-Row würde der GC nicht aufräumen);
  braucht Prefix-Delete bzw. Reservierungs-Logik, separat anzugehen.
- [x] **D2 · `MySpacesHandler` N+1** *(Perf, M)* — Roster jetzt mit fixer Query-Zahl statt O(Tenants+Groups):
  ein Read aller gehaltenen Grants (`held`-Set), dann Memberships + relevante Tenant-Root-/Group-Scopes +
  Tenants je per IN-Query, in-memory zusammengesetzt (`grants.listByUserId`, neue `scopes.listByIds`/
  `listTenantRootsByTenantIds`, `tenants.listByIds`). Verifiziert: switchActiveSpace- + useGroup- +
  groupSpaces-E2E grün.
- [x] **D3 · Share-Chunk-ACL nicht pro Chunk neu scannen** *(Perf, M)* — die erlaubte Blob-ID-Menge wird pro
  Share 15s gecacht (`blobBacksSubtreeMedia`), sodass die vielen parallelen Chunk-Fetches einer Galerie nicht
  je den ganzen Scope-Subtree neu materialisieren. Cache-MISS für eine Blob-ID (stale Set / frisch beigesteuertes
  Medium) rechnet neu + prüft erneut → immer korrekt, nur disallowed-Blob-Probes zahlen den Recompute.
  Verifiziert: share-E2E grün (Chunk-Download + A3-Forge-Reject über den Recompute-Pfad).
- [x] **D4 · JournalRelay Room-Index** *(Perf, M)* — `room → Set<WebSocketConnection>`-Index in onOpen/onClose
  gepflegt; Fan-out (onMessage) und der Peer-Cap laufen jetzt O(Room) statt O(alle offenen Verbindungen
  server-weit). `OpenConnections`-Dependency entfernt. Verifiziert: journalLive- (2-Client-Konvergenz) +
  share-live-read-E2E grün. *(Kleiner Rest: per-Room Aggregat-Egress-Budget / Connections-pro-User-Cap als
  zusätzliche Anti-Amplifikation — durch Peer-Cap (32) + per-connection-Limit bereits gebounded, niedrige Prio.)*

## Phase E — Wartbarkeit / Aufräumen
- [x] **E1 · `TenantAdminGuard` extrahiert** *(Maintainability, M)* — der duplizierte Owner-ACTIVE-Check (8
  Handler: RemoveMember, ChangeMemberRole, MemberInvite, EmailInvite, ScopeRotation, MemberList, TenantTerms,
  TenantProvisioning) läuft jetzt durch `TenantAdminGuard.requireOwner` (+ `requireActiveMember` für die
  Member-List). Analog zu `ScopeAccessGuard`, kann nicht mehr pro Handler driften — und ist jetzt **die eine
  Stelle** für E4 (Enum-Migration, nur noch Guard + Entity). Verifiziert: alle 5 admin-E2E grün.
- [x] **E2 · `toApiBatch` geteilt** *(Maintainability, S)* — die byte-identische Batch-Mapper-Kopie aus
  `MediaHandler` + `PublicShareMediaHandler` in einen gemeinsamen `MediaApiMapper` (`shared/content`) gezogen;
  beide delegieren. Verifiziert: media- + share-E2E grün (identische Ausgabe).
- [ ] **E3 · Zentralisierungen (P4.2-Reste + Clients)** *(Maintainability, M)* — Backend `unauthorized()`/`notFound()`/
  `requireSuite`/Base64 → `platform/error`/Helper; Web `b64url`/`fromB64url` (21×) → `shared/base64.ts`;
  Android ein `ApiClient` (ein `HttpClient`, zentrales Send/Fehler-Handling, 9× dedup — deckt A5 Teil 1 mit
  ab) **und A5 Teil 2**: die 56 Triple-Quote-JSON-Bodies (10 Dateien) auf `JSONObject`/`JSONArray` umstellen,
  per Android-E2E verifiziert.
- [ ] **E4 · P4.3 String → typisierte Enums** *(Maintainability, L)* — Roles/Status/Scope-Kind in Entities
  (`Tenancy.kt:21` u. a.). War bewusst zurückgestellt; jetzt mit **E1** koppeln (Guard + Enums zusammen).
- [x] **E5 · iOS Live-Journal-Teardown auf Dismiss** *(Quality, S)* — `LiveJournalView` reißt jetzt bei JEDEM
  Verschwinden ab (`.onDisappear → model.stop`), nicht nur am Done-Button — sonst leakt der Websocket und Edits
  im Debounce-Fenster gehen verloren. `stop()` ist idempotent (`stopped`-Guard, in `start()` re-armed), sodass
  Done + onDisappear nicht doppelt laufen. iOS-App kompiliert. (Android via `DisposableEffect` bereits korrekt.)
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
