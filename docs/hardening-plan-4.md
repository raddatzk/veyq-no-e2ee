# Härtungsrunde 4 — Ablaufplan

Folge-Review nach Abschluss von `hardening-plan-3.md`. Sechs parallele Review-Subagenten (Backend-Security,
Backend-Qualität, Web, iOS, Android, Cross-Platform-Crypto) gegen die 6 Dimensionen. Bereits in Runde 1–3
gefixte Punkte ausgeschlossen.

**Kernergebnis: KEIN High/Critical-Finding auf irgendeiner Plattform.** Nach drei Runden ist der Angriffs-/
Fehler-Surface eng — die Findings sind Mediums (meist Folge-Lücken bereits gefixter Klassen) + Lows. Operator-
blind hält überall; Crypto byte-identisch (Mediums sind reine Vektor-Pinning-Lücken, keine aktive Divergenz).

**Reihenfolge:** Plaintext-at-rest + Secret-Schutz → Backend-Resource/DoS → Korrektheit → Crypto-Drift-Proofing
→ Wartbarkeit/Cleanup.

Effort: **S** < 1 h · **M** ein paar Stunden · **L** Tag+. Status: `[ ]` offen · `[~]` Arbeit · `[x]` erledigt.

---

## Phase A — Plaintext-at-rest + Secret-Schutz (Mobile)
- [x] **A1 · iOS: entschlüsselte Medien-Originale + ZIP bleiben in `temporaryDirectory`** *(Security, Medium, S)* —
  alle Medien-Exporte laufen jetzt unter `DownloadService.exportDir` (`temp/veyq-export/`): `saveOriginal` schreibt
  dorthin, `zipToTemp` legt Staging-Dir + ZIP dorthin und löscht das Staging-Dir mit den entschlüsselten Originalen
  sofort nach dem Zippen (`defer { removeItem }`). Der Launch-Sweep heißt jetzt `cleanupSharedTemp` und wischt
  zusätzlich `veyq-export/` (Kit-Prefix + Exporte). Stale-Kommentar gefixt. Verifiziert: iOS-App baut.
- [x] **A2 · Android: Device-Unlock-Biometrie ist Soft-UI-Gate** *(Security, Medium, M)* — `DeviceUnlock` komplett
  auf **krypto-gebunden** umgebaut: Creds werden AES-GCM-versiegelt, der AES-Key per **RSA-OAEP an einen
  AndroidKeyStore-Keypair gewrappt, dessen Private-Key `setUserAuthenticationRequired(true)` +
  `setInvalidatedByBiometricEnrollment(true)` ist**. `save` nutzt den Public-Key (kein Prompt — User hat gerade
  eingeloggt); `load` führt die RSA-Entschlüsselung in einer `BiometricPrompt.CryptoObject` aus → der gewrappte
  AES-Key (und damit die Creds) ist ohne frische Strong-Biometrie nicht entschlüsselbar; ein Prefs-Read allein
  reicht nicht mehr. OAEP-MGF1-SHA1-Quirk auf beiden Seiten gepinnt; `KeyPermanentlyInvalidatedException` → forget.
  Verifiziert: `:app` kompiliert. *(Runtime braucht ein Gerät mit Biometrie — Pattern ist Standard.)*
- [x] **A3 · Android: `allowBackup` default true** *(Security, Low-Med, S)* — `android:allowBackup="false"` im
  Manifest gesetzt → plain `"veyq"`-Prefs (Instanz-URL, Locale, Map-Consent) + Cache nicht mehr cloud/`adb backup`-
  fähig. Verifiziert: `:app` kompiliert.

## Phase B — Backend Resource / DoS
- [x] **B1 · Per-Share Guest-Upload ohne Aggregat-Byte/Session-Quota** *(Security/DoS, Medium, M)* — ein
  unauth CONTRIBUTOR/UPLOADER-Link kann bis `maxOpenSessions` (100) Uploads/Scope × `maxUploadBytes` (10 GiB) =
  ~1 TB sealed Junk schreiben; der Open-Session-Cap ist per-Scope (mit dem Owner geteilt), nicht per-Share. War als
  D2-Interim-Mitigation notiert, nur der Request-Count-Limiter landete. Fix: Bytes/Sessions je `createdBy=
  "share:<id>"` zählen + per-Share-Ceiling in `startBlobAs`/`putBlobChunkAs` durchsetzen; Guest-`maxOpenSessions`
  senken.
- [x] **B2 · `PublicShareMediaHandler.allowedCache` wächst unbegrenzt** *(Perf/Memory, Medium, S)* — die
  `ConcurrentHashMap<shareId, AllowedBlobs>` wird nur geschrieben/gelesen, nie evictet (TTL steuert nur Frische).
  Jeder Share, der je einen Chunk liefert, lässt einen permanenten `Set<blobId>` zurück → monotones Leck. Fix:
  `@Scheduled`-Sweep (wie `RateLimiter.sweep`) der abgelaufenen Einträge bzw. size-bounded Cache.
- [x] **B3 · Unauth Invite-Metadaten-GET ungedrosselt** *(Security, Low, S)* — `GET /api/tenant-invites/{id}`
  (+ Public-Share-Metadaten-GETs) sind nicht im RateLimitFilter (nur POST + Share-Egress-Bytes). UUIDs nicht
  enumerierbar, nur Public-Metadaten — aber ein unbegrenzter unauth DB-Read/Tenant-Existenz-Oracle. Fix: großzügiger
  per-IP-Request-Bucket für unauth Metadaten-GETs (der „Folgepunkt" aus Runde 3 A4).

## Phase C — Korrektheit
- [x] **C1 · Web: stale Galerie/Journal/Tracks beim Memory-Wechsel via Suche** *(Correctness, Medium, S)* — FIX:
  `<MemoryDetailView v-if="selectedMemory…">` hat KEIN `:key`. `onSearchSelect` (MEMORY) setzt `selectedMemory`
  direkt (nicht über `openLevel`, das es erst nullt) → Vue reused die Instanz, `onMounted` lief nur einmal, kein
  `watch(props.memory)` → Titel/Count aktualisieren, aber Tiles/Journal/Tracks bleiben vom vorherigen Memory.
  Fix: `:key="selectedMemory.node.id"` (oder `watch(() => props.memory.id)` mit Reload + Reset der Tab-Flags).
- [x] **C2 · Web: kleinere Race-/Konsistenz-Nits** *(Correctness, Low, S)* — FIX: `onReorderDrop` snapshotted `active` + `loadSeq`-Guard + schreibt nur geänderte Indizes; `LiveJournalEditor` onBeforeUnmount synchron (view/provider sofort destroy), Persist+compact detached auf dem doc, dann `doc.destroy()`. (`onSearchSelect`-crumbs/ancestorChain = kosmetischer Folgepunkt.) Verifiziert: vue-tsc + journalLive/persistence/browse-E2E. ORIG: `onReorderDrop` schreibt seriell ohne
  `loadSeq`-Guard gegen `active.value` (kann nach Space-Wechsel mitten reinschreiben) + re-PUTet jeden Index;
  `onSearchSelect`-MEMORY setzt `crumbs` nicht auf die echte Parent-Kette (`ancestorChain`); `LiveJournalEditor`
  `async onBeforeUnmount` awaitet 3 Round-Trips vor den `destroy()`s (Vue awaitet Hooks nicht) → Teardown erst nach
  Compaction. Fix: synchroner Teardown zuerst, Compaction detached; Reorder-Guard + Snapshot.

## Phase D — Crypto-Drift-Proofing (Vektoren)
- [x] **D1 · `encodeKdfParams`/`decodeKdfParams`-Wire-Format ungepinnt** *(Crypto/Tests, Medium, S)* — die EINZIGE
  Fixed-Layout-Cross-Platform-Serialisierung (web *encodet* bei Setup/Reset, Mobile *decodet* bei Login) ohne KAT.
  Ein Endianness-/Offset-/Feldreihenfolge-Edit bräche web-mint→mobile-login lautlos. Fix: `kdf_params`-Vektor
  (`{t:3,m:32768,p:1,dkLen:32,salt}` → fixe Hex) + encode/round-trip-Assertion in allen drei Suiten.
- [x] **D2 · NFC-Normalisierung wird vakuös getestet** *(Crypto/Tests, Medium, S)* — alle byte-sensitiven
  Vektor-Inputs sind bereits NFC/ASCII (`slug_token`-Slug `italien-2026-café` ist schon NFC), also würde eine
  Plattform, die Normalisierung WEGLÄSST, trotzdem alle Vektoren bestehen. Fix: `slug_token`-Slug auf **NFD**
  (`café`) ändern, erwarteter Token über die NFC-Form — macht den Normalisierungs-Schritt load-bearing.

## Phase E — Wartbarkeit / Cleanup
- [x] **E1 · `Node.type` String → `NodeType`-Enum** *(Maintainability, Low, S)* — E4-Leftover: sechs Tenancy/Scope-
  Felder wurden zu `@Enumerated`-Enums, `Node.type` blieb String → verstreute `NodeType.valueOf(node.type)` in 7+
  Handlern. Genau 1 Schreibstelle (`type = req.type.value`) → winzige Migration, kein Wire-Change.
- [x] **E2 · `MyGroupsHandler.listMyGroups` N+1** *(Perf, Low, S)* — von D2 (nur `MySpaces`) übersehen: 3 Queries je
  Tenant. Auf `listActiveByUserId` + `listTenantRootsByTenantIds` + `tenants.listByIds` + held-Set umbauen (wie
  `MySpacesHandler`).
- [x] **E3 · 3× byte-identischer `MemoryNode`-Mapper** *(Maintainability, Low, S)* — `MemoryTreeHandler.toApi` +
  `CurateHandler.toApi` + `ShareHandler.nodeToApi` (letzterer ist schon geteilt). Auf einen `NodeApiMapper`
  (`shared/content`, neben `MediaApiMapper`) konsolidieren.
- [x] **E4 · Resurrected `"v1"`-Literal + ContentPurge-Ordering** *(Clarity, Low, S)* — `getJournalLiveAs`
  `snapshotSuiteId ?: "v1"` (B4-Klasse, unerreichbar → werfen statt defaulten); `ContentPurge.purgeNodes` clear-cover
  -nach-delete mit pre-captured ids → Kommentar/Reorder.
- [x] **E5 · Android JournalLiveProvider-Reste** *(Maintainability, Low, S)* — `HttpClient.newHttpClient()` pro
  (Re)Connect statt `ApiClient.shared` (E3-Muster); `peers`/`peerClocks` wachsen unbegrenzt (Ghost-Peers ohne
  Timeout-Eviction); `seedIfEmpty` tot (Call-Site inlined). Fix: shared Client, Peer-Eviction (y-protocols
  outdatedTime), Dead-Code raus.
- [ ] **E6 · Web: rohe Server-Response-Bodies in ~70 Throws** *(DEFERRED — 70 Sites, Low-Wert/hohe Churn; eigener Pass)*  ORIG: *(Security/Clarity, Low, S)* — `throw new
  Error(\`…${await res.text()}\`)` überall (Web-Pendant zu Android A5). Zentraler `apiError(res)`-Helfer
  (Status + stabiler Code; Body nur in dev). Defense-in-depth.
- [x] **E7 · Doku-Drift** *(Docs, Low, S)* — `security-model.md` nennt Login-Argon `m=65536`, Code nutzt `m=32768`
  (bewusste P2.2-Wahl) → Doc auf 32768 angleichen. iOS-Cursor-UTF-16-Vereinheitlichung bleibt Backlog (kosmetisch).

---

### Empfohlener Einstieg
**Phase A** (Plaintext-at-rest auf iOS = klare C2-Folge; Android-Biometrie-Härtung). Dann **B** (DoS/Leak),
**C1** (sichtbarer Wrong-Content-Bug), **D** (Crypto-Drift-Proofing, billig & wertvoll), **E** (Cleanup).
