# Härtungsrunde 3 — Ablaufplan

Folge-Review nach Abschluss von `hardening-plan-2.md` (A–F inkl. E4/E6). Sechs parallele Review-Subagenten
(Backend-Security, Backend-Qualität, Web, iOS, Android, Cross-Platform-Crypto) gegen die 6 Dimensionen
(sicher? gut geschrieben? wartbar? verbesserbar? performant? menschlich verständlich?). Bereits in Runde 1+2
gefixte Punkte sind ausgeschlossen. Findings dedupliziert (Live-Journal-Race trifft iOS **und** Android;
Map-Tile-Leak trifft Web+Mobile).

**Reihenfolge-Prinzip** (wie Runde 2): erst Sicherheits-/AuthZ-Lücken (billig, hoher Wert) → dann
Korrektheit/Concurrency → dann Operator-blind-Privacy-Vervollständigung → dann Crypto-Conformance-Härtung →
dann Performance → dann Wartbarkeit → zuletzt Backlog.

Effort: **S** < 1 h · **M** ein paar Stunden · **L** Tag+ / mehrere Plattformen.
Status: `[ ]` offen · `[~]` in Arbeit · `[x]` erledigt.

---

## Phase A — Sicherheit / AuthZ (zuerst)
- [x] **A1 · Entfernter Tenant-Member behält Gruppen-Admin-Rechte** *(Security, High, M)* — zwei Schichten:
  (1) `RemoveMemberHandler` löscht jetzt auch **alle Gruppen-Scope-Grants + GroupMemberships** des Users im
  Tenant (`scopes.listGroupsByTenantId` → neue `deleteByUserIdAndScopeIds` auf ScopeGrant + GroupMembership).
  (2) Defense-in-depth: `requireGroupOwner` + `listGroupMembers` erzwingen jetzt `requireActiveMembership`
  (ACTIVE-Tenant-Member), so dass selbst eine übersehene/stale GroupOwner-Zeile inert ist — gespiegelt aus
  `ScopeAccessGuard.isActiveMember`, geteilter Helper. Verifiziert: neuer GroupSliceTest
  (`removingTenantMemberStripsGroupAccessAndAdminPower`: Cleanup + stale-row-Guard nach Re-Auth) + 56 Backend-
  Tests grün + removeMember/groups/groupSpaces/useGroup-E2E grün.
- [x] **A2 · Cross-Share Upload-Session-Hijack (unauth IDOR auf In-Flight-Uploads)** *(Security, Medium, S)* —
  `requireUploadInScope` nimmt jetzt einen optionalen `expectedOwner`; `putBlobChunkAs`/`getBlobUploadAs`/
  `completeBlobUploadAs` reichen ihn durch. Der Guest-Pfad (`PublicShareContributeHandler`) übergibt
  `"share:<id>"`, sodass ein Gast eine fremde `uploadId` (zweiter Share / Co-Gast) NICHT lesen/überschreiben/
  finalisieren kann (404, nicht 403 → kein Existenz-Probe). Member übergeben `null` (sind ohnehin Scope-Key-
  Holder). Verifiziert: neuer ShareSliceTest `guestCannotTouchAnotherSharesInFlightUpload`.
- [x] **A3 · E-Mail-Invite als Open-Relay/Phishing via schwachem `startsWith`** *(Security, Medium, S)* —
  `EmailInviteHandler` vergleicht jetzt die **Origin** (Schema+Host+Effektiv-Port) von `req.link` exakt gegen
  `appBaseUrl` (`sameOrigin`, `java.net.URI`), statt `startsWith`. Blockt `…@evil.com`, `…example.com.evil.com`,
  abweichende Ports. (Server-Reconstruct unnötig, da das Fragment Client-Crypto trägt.) Verifiziert:
  EmailInviteSliceTest um drei Bypass-Vektoren erweitert (alle → 400 BAD_LINK, gültiger Link weiter 204).
- [x] **A4 · Public-Share-Reads/Downloads ungedrosselt (unauth DoS/Egress)** *(Security, Medium, M)* —
  `RateLimitFilter` verarbeitet jetzt auch GET: für `/api/public/shares/` wird ein **per-IP-Byte-Quota**
  (Default 500 MiB/min, konfigurierbar) geprüft; der neue `ShareEgressAccountingFilter` (Response-Filter)
  bucht die ausgelieferten Bytes nach. Byte-Quota statt Request-Count, damit ein legitimer Großgalerie-Viewer
  (viele kleine Chunk-GETs) nicht ge-429-t wird; ein Scraper wird nach Überschreiten gekappt. `RateLimiter` um
  `isOverByteBudget`/`recordBytes` + eigenes ByteWindow-Sweep erweitert. Verifiziert: 4 neue RateLimiter-Unit-
  Tests; App bootet (HealthTest); Share-E2E grün. *(Request-Flood-Throttle für Metadaten-GETs = kleiner Folgepunkt.)*

## Phase B — Korrektheit / Concurrency
- [x] **B1 · `JournalLiveProvider` Cross-Thread-Datarace (iOS + Android)** *(Correctness, High, M)* — beide
  Provider routen jetzt **jeden** Zugriff auf geteilten Mutable-State (`lastSentSV`, Awareness-Maps,
  local*-Presence, Persist-SVs) durch das eine Monitor (Android: bestehendes `lock`; iOS: neuer
  `NSRecursiveLock` + `sync{}`). Muster überall: Bytes **unter** dem Lock berechnen, `send()`/Callbacks
  **außerhalb** ausführen (kein Netzwerk/UI unter dem Monitor). `applyLocalText` hält den Lock über
  read-diff-apply (sonst korrumpiert ein paralleler Remote-Apply die Offsets). Socket-Ref abgesichert
  (Android `@Volatile ws`; iOS task-Identitäts-Check). **B1b:** Generations-Zähler — ein überholter
  Connect/Listener installiert weder seinen Socket noch plant er einen Reconnect (Android `generation` +
  superseded-`sendClose`; iOS `webSocketTask === self.task`-Guard in didOpen/didClose) → keine Doppel-Sockets.
  Verifiziert: Android `:core` + `:app` kompilieren, iOS `VeyqCrypto`-Package baut (swift build). (Web
  unverändert — single-threaded Eventloop. Multi-Client-Runtime weiter via Plan-2-F1/CI.)
- [x] **B2 · Journal-Compaction prune-vs-snapshot unter Concurrency** *(Correctness, Low-Med, S)* — die echte
  Invariante ist „Snapshot deckt JEDES Update <= coveredSeq" — server-seitig operator-blind **nicht** prüfbar
  (`==nextSeq` wäre weder hinreichend gegen Lücken *unter* coveredSeq noch nicht-brechend, da
  `saveJournalMarkdown` nur via Compact persistiert). Fix client-seitig: **Reload-before-Compact** auf dem
  Live-Pfad (Web `LiveJournalEditor` onBeforeUnmount, iOS/Android `compactPersisted` rufen `loadJournalDoc`/
  `loadPersisted` vor dem Compact) → der frisch geladene Log füllt jede Lücke, also ist der Snapshot
  garantiert lückenlos. `saveJournalMarkdown` lädt ohnehin schon vorher (unverändert). Backend: Invariante als
  Kommentar dokumentiert. Verifiziert: vue-tsc + journal/journalLive/journalPersistence-E2E grün; iOS/Android
  kompilieren.
- [x] **B3 · iOS `journalSeal` schluckt AEAD-Seal-Fehler** *(Correctness, Low, S)* — `(try? aeadSeal) ?? Data()`
  → `try! aeadSeal(...)`. Subkey (32B) + Nonce (12B) sind hier immer gültig, GCM-Seal kann nicht fehlschlagen;
  ein leeres Ciphertext (un-öffenbarer Frame, stiller Edit-Verlust) wird so durch Fail-Fast ersetzt — deckt
  sich mit Web/Android (die werfen). iOS `VeyqCrypto` baut.

## Phase C — Operator-blind-Privacy vervollständigen
- [ ] **C1 · Map-Tiles: externe Tiles als akzeptierter, Consent-gegateter Tradeoff** *(Privacy/Consent, Medium, M)* —
  **Entscheidung (User):** der Tile-Fetch an OSM ist OK, MUSS aber vom User über die **Datenschutz-Einstellungen
  aktiv akzeptiert** werden, bevor die Karte lädt (informiertes Opt-in). `MemoryMap.vue` lädt sonst
  `tile.openstreetmap.org/{z}/{x}/{y}` direkt → `{z}/{x}/{y}` = Funktion der entschlüsselten Koordinaten, also
  lernt OSM den Aufnahmeort. Fix: Map-Tab/`MemoryMap` hinter ein persistiertes Datenschutz-Consent-Flag stellen
  (vor Akzeptanz Platzhalter + Hinweis statt Tiles); `referrerPolicy: 'no-referrer'` am Tile-Layer; Tradeoff in
  den Einstellungen erklären. **Kein** Proxy/Self-Host nötig. Mobile (native Map-SDKs) analog hinter dasselbe
  Consent stellen.
- [x] **C2 · Emergency-Kit-PDF (Secret Key) bleibt im Cache (Android + iOS)** *(Security, Medium, S)* — Android
  schrieb den Plaintext-Secret-Key nach `cacheDir/shared/veyq-emergency-kit-<email>.pdf`, iOS analog nach
  `temporaryDirectory/...<email>.pdf`; beide nie aufgeräumt, E-Mail im Dateinamen. Fix: (1) generischer Dateiname
  (`veyq-emergency-kit.pdf`, kein E-Mail, überschreibt statt zu akkumulieren); (2) Launch-Sweep — Android wischt
  `cacheDir/shared` in `MainActivity.onCreate` (nur transiente FileProvider-Share-Artefakte: Kit + Media-Downloads,
  keins beim Cold-Start in-flight), iOS löscht `veyq-emergency-kit*`-Dateien aus `temporaryDirectory` im
  `VeyqApp.init`. Web ist ok (jsPDF `doc.save()` → Browser-Download in den vom User gewählten Ordner, kein
  App-Cache-Artefakt). Verifiziert: Android `:app` + iOS-App (xcodebuild) bauen.

## Phase D — Crypto-Conformance-Härtung (drift-proofing)
- [x] **D1 · Passwort-abgeleitete KEKs durch Vektoren pinnen** *(Crypto/Tests, Medium, S)* — drei neue KAT-Vektoren
  in `v1.json`: `kek.recovery_argon2id_m32768` (kekRecovery @ Produktions-m=32768 — exakt der B1-Param),
  `wrap.sym_invite_temp_key` (symWrap-Context `invite-temp-key`), `kek.share_pw` (`shareWrapKey`/`veyq/v1/share-pw`).
  Werte aus der Web-Impl generiert. Cross-Platform asserted in allen drei Conformance-Suiten (Web/iOS/Android) für
  die ersten beiden; `share_pw` nur in der Web-Suite (iOS/Android implementieren keine passwortgeschützten Share-
  Creates → „web-only by design", im Vektor dokumentiert). Verifiziert: Web (17 Tests) + iOS + Android
  Conformance grün.
- [x] **D2 · iOS Argon2id ignoriert `p` still** *(Crypto, Low, S)* — iOS `argon2id` wirft jetzt bei
  `params.p != 1` (libsodium fixiert Parallelität auf 1) statt lautlos divergierende Bytes zu erzeugen. Alle
  aktuellen Uses sind p=1 → unverändert; ein künftiger p>1-Caller scheitert laut statt still. Verifiziert:
  iOS-Conformance-Tests grün (p=1-Vektoren).

## Phase E — Performance
- [ ] **E1 · Backend-N+1-Restkehrwisch** *(Perf, Medium, M)* — `MemberListHandler` (`users`/`identityKeys` pro
  Zeile), `GroupProvisioningHandler.listGroupMembers` (`users.findById` pro Zeile), `MyGrantsHandler`
  (`scopes.findById` + `isActiveMember` pro Grant). Auf IN-Query-Batching umstellen (analog D2/E2/`MediaApiMapper`).
- [ ] **E2 · Web Bulk-ZIP-Download puffert alles im RAM** *(Perf, Medium, M)* — `download.ts` entschlüsselt jedes
  Original komplett in `files[]`, baut dann ein In-Memory-ZIP + Blob → Peak ~2–3× Gesamtgröße, OOM bei großen
  Memories. Fix: zur Platte streamen (File System Access API + Streaming-ZIP) oder zumindest cappen/warnen.
- [ ] **E3 · Sequenzielle Chunk-/Cover-Loads parallelisieren** *(Perf, Low-Med, S)* — `downloadBlob`/
  `downloadShareBlob` (Web) holen Chunks strikt seriell (Zeit ~ chunks×RTT); iOS `HomeStore.openLevel` lädt
  Cover seriell; Android-Rotation seriell. Frames sind AAD-Index-gebunden → `mapPool`/`TaskGroup`/`Semaphore`
  (~4–6) + in Index-Reihenfolge zusammensetzen.

## Phase F — Wartbarkeit / Klarheit
- [ ] **F1 · E4-Rest + Struktur-Duplikate** *(Maintainability, Low, S)* — `MediaHandler.requireMemoryNode` nutzt
  noch `node.type != "MEMORY"`-Strings statt `NodeType.valueOf` (E4-Leftover); `requireContainment` ist in
  `MemoryTreeHandler` + `CurateHandler` doppelt; `isInSubtree` macht O(Tiefe) Einzel-Lookups statt den schon
  geladenen Subtree zu nutzen. In geteilte `NodeStructure`-Helfer ziehen (wie Guards-Konsolidierung).
- [ ] **F2 · Web ShareView Race-Härtung** *(Correctness/Maintainability, Low, S)* — `load()`/`loadContent()` ohne
  `loadSeq`-Guard (anders als `HomeView` nach C3); zweites `startLiveJournal` verwaist den alten
  Provider/Doc. `restoreFromUrl` lässt bei `resolveRoute`-Fehler einen halb-applizierten View stehen → Fallback.
- [ ] **F3 · Android Gallery silent-empty + Socket-Dedup** *(Quality, Low, S)* — `GalleryScreen.load()` fängt alle
  Exceptions in „leeres Grid" (kein Retry/Signal); Error-State + Retry analog `HomeController`. (Socket-Dedup
  siehe B1b.)

### Backlog (nicht eingeplant)
- **Per-Account-Login-Backoff** (L1) — zusätzlich zum per-IP-Bucket ein per-E-Mail-Fehlzähler/Exponential-Backoff.
- **`tileById` als `Map`** (Web, Mikro-Opt im Render-Hot-Path).
- **iOS Live-Cursor auf UTF-16-Offsets vereinheitlichen** (lange bekannt, kosmetisch; Emoji/Astral-Drift).
- **Developer-Styleguide** (aus Runde 2, noch zu refinen).
- **Offen aus Runde 2:** F1 Apple/Android-E2E in CI (macOS/Android-Runner), F2 web-e2e-Job in Actions verifizieren
  (braucht Push) · Android Yrs-AAR · Mobile Share-Live-Read · D1-Write-Path (blob-IO-out-of-tx).

---

### Empfohlener Einstieg
**Phase A** (A1 ist das einzige High mit echter Privilege-Persistenz; A2/A3 je S). Dann **B1** (Cross-Platform-
Datarace) als zweitwichtigster Punkt. C/D/E/F danach nach Wert.
