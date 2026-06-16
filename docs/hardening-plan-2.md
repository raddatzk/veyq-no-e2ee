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
- [ ] **A2 · Session-Token raus aus der WebSocket-URL-Query** *(Security, plattformübergreifend, M)* —
  `frontend/.../LiveJournalEditor.vue:65`, `shareJournalLive.ts:32`, iOS `LiveJournalView.swift:20`,
  Android `GalleryScreen.kt:141`. Query-Strings landen in Proxy-/Access-Logs & History. Fix: kurzlebiges
  Single-Use-WS-Ticket (POST → Token) **oder** `Sec-WebSocket-Protocol`-Wert; Backend-Relay entsprechend.
  (Mobile dabei zugleich percent-encoden, falls Ticket-Weg nicht sofort kommt.)
- [ ] **A3 · Share-Contribute kann fremde Scope-Blobs anhängen (IDOR)** *(Security, S)* —
  `backend/.../PublicShareContributeHandler.kt:43` → `MediaHandler.createMediaAs`/`requireBlob:129`.
  Unauth Guest darf jeden `blobId` im Scope referenzieren. Fix: bei Share-Contributions auf Blobs mit
  `createdBy == "share:<shareId>"` einschränken.
- [ ] **A4 · Single-Server-Owner-Setup TOCTOU** *(Security, S)* — `SetupServerHandler.kt:32` + `SetupGate.kt:9`.
  Zwei parallele `POST /api/setup/owner` können beide durchkommen. Fix: Partial-Unique-Index/Lock-Row auf die
  ServerOwner-Rolle (Migration V27).
- [ ] **A5 · Android: rohe Response-Bodies in Exceptions + ungeescapte JSON-Interpolation** *(Security, Android, M)* —
  `Media.kt`, `InviteMembers.kt`, `MemoryTree.kt:31`, `ScopeContent.kt:78`, `Groups.kt` u. a.
  `throw …${resp.body()}` schleppt Payloads in Crash-Logs; mehrere Bodies via `"""{…}"""` statt `JSONObject`.
  Fix: typisierte `ApiException(status, label)` ohne Body; alle Bodies über `JSONObject`/`JSONArray`.
  (Teilweise gemeinsam mit E3 — zentraler `ApiClient` — erledigbar.)

## Phase B — Crypto-/Interop-Korrektheit
- [ ] **B1 · Invite-Passphrase Argon2-Parameter prüfen & angleichen** *(Security/Interop, M)* —
  Web `groups.ts:28,35` (m=32768) vs. iOS/Android `InviteMembers.*:~217` (m=65536). Zuerst **verifizieren**,
  ob Redeem immer die mitgelieferten `kdfParams` nutzt (dann nur latent) — danach die drei Mint-Konstanten
  auf einen Wert angleichen. Kein Conformance-Vektor deckt das ab.
- [ ] **B2 · Conformance-Lücken schließen** *(Tests/Crypto, S)* — `docs/api/crypto-vectors/v1.json`.
  (a) `journalSeal`→`journalOpen` Round-Trip pro Plattform asserten (heute nur `open` gepinnt);
  (b) den vorhandenen, aber ungenutzten `hkdf_sha256_rfc5869_case1`-KAT in allen drei Conformance-Tests
  asserten. Optional Invite-Passphrase-Vektor ergänzen (deckt B1 ab).
- [ ] **B3 · Journal erster Append serialisieren statt 500** *(Korrektheit, S)* — `JournalHandler.kt:122` (`lockedJournal`).
  Zwei parallele Erst-Appends ⇒ Unique-Violation. Fix: leere Journal-Row bei Node-Erstellung anlegen
  **oder** Constraint-Violation fangen + Lock-Pfad retry.
- [ ] **B4 · `suiteId = "v1"` aus Guest-Read-Pfaden entfernen** *(Korrektheit, S)* —
  `PublicShareMediaHandler.kt:63` (ChunkResponse), `PublicShareTreeHandler.kt:57` (passwordSalt). Echten
  `blob.suiteId` durchreichen (Member-Pfad ist seit P4.2 sauber).

## Phase C — Medien-Tiering (Feature) + Galerie-Perf/Memory (Web → iOS → Android)
Zielbild: **Grid → Thumbnail · Lightbox/Vorschau → Preview · Original → nur per Download-Button.**
Entschieden: Preview ≈ **2048 px Langkante, JPEG q0.85**; Original-Zugriff über expliziten **Download/Speichern-Button**.
Bestandsmedien ohne Preview ⇒ Fallback Thumbnail→Original bleibt erhalten (greenfield, kein Backfill).
Diese Phase fixt zugleich H4/H5/M11 aus der Review, weil sie denselben Galerie-Code anfasst.

- [ ] **C1 · Web — Upload: PREVIEW erzeugen** *(Feature, M)* — `frontend/.../uploads/clientMedia.ts` (`makePreview`,
  maxDim 2048, q0.85), `media/media.ts` (`uploadMedia` lädt Preview-Blob + `PREVIEW`-Derivat), `NewMedia`-Typ
  um `preview?` erweitern; gleicher Pfad in `share.ts` (`uploadShareMedia`/Contribute).
- [ ] **C2 · Web — Render: Preview im Lightbox, Original nur per Download** *(Feature, M)* — `features/gallery/gallery.ts`
  (`GalleryTile.preview`, Lightbox lädt Preview statt `loadOriginal`; `loadOriginal` bleibt nur für Download),
  `app/home/MemoryDetailView.vue` + `app/share/ShareView.vue` (Lightbox→Preview, Download-Button → Original als
  Datei-Download). Gleiches in `share.ts` (`loadShareTiles`/`loadShareOriginal`).
- [ ] **C3 · Web — Object-URL-Leak + Navigations-Race beheben** *(Quality/Memory, S)* — `HomeView.vue:221-226`:
  alte Cover-URLs vor Reassign + in `onBeforeUnmount` `revokeObjectURL`; Generation-Guard (`loadSeq`/AbortController)
  für überholte Cover-/Level-Loads (= Review H4 + M11).
- [ ] **C4 · Web — Tile-Downloads bounded-parallel** *(Perf, S)* — `gallery.ts`/`share.ts` Tile-Loops von seriell auf
  Pool (~6 concurrent) umstellen (Review-Perf-Punkt).
- [ ] **C5 · iOS — Upload PREVIEW + Render-Tiers** *(Feature, M)* — `MemoryDetailView.swift:145` (`preview`-Variante
  2048/q0.85), `Media.swift:147` (`uploadMedia` lädt Preview-Derivat); Lightbox lädt **Preview** statt Thumbnail
  (`MemoryDetailView.swift:292`), Download/Save-Button (Share-Sheet/Fotos) lädt Original on-demand.
- [ ] **C6 · iOS — Galerie bounded-parallel + Downsampling** *(Perf/Memory, M)* — Thumbnail-Loads per `TaskGroup`
  (~4–6 wide), `NSCache` statt Tiles dauerhaft halten; Cover-Cache nicht pro Level leeren (`HomeStore.swift`).
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
  Android ein `ApiClient` (ein `HttpClient`, zentrales Send/Fehler-Handling, 9× dedup — deckt A5 mit ab).
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
