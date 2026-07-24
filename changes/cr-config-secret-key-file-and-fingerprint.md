---
id: cr-config-secret-key-file-and-fingerprint
title: Config-secret KEK as a managed key file + a DB fingerprint tripwire (amends ADR 0050)
status: done
satisfies: ["uc.admin.tenant-mail-settings"]
adrs: ["0050"]
invariants:
  - "No usable secret in a DB dump — the DB gains only a FINGERPRINT of the KEK (an HKDF of a 256-bit full-entropy key), which is not the key and not brute-forceable; the KEK itself never enters the DB. The property is preserved, not surrendered."
  - "Operator-blind E2EE untouched — this is operator-side infra crypto (the KEK seals the operator's OWN config secret). The fingerprint is a hash of the server's own key, no user content; the server never gains a content key."
  - "Self-hostable by default — a fresh instance generates its own KEK on first start (zero-config); losing it is now a LOUD, detected state, not a silent failure."
---

## Goal

Turn the config-secret KEK (ADR 0050) from an **env-supplied value the operator must set** into a **key file the
server manages itself**, and add a **DB fingerprint tripwire** so a lost/changed key is a *detected*, honest state
instead of a silent one. On first start the server generates a 32-byte KEK, writes it to a `0600` file, and logs
loudly ("back this up — losing it makes stored secrets unrecoverable"). An operator who wants to supply their own
key mounts it and points a **path override** at it (`veyq.security.config-secret-file`) — the standard
K8s/Docker-secret shape, which is a *file* anyway; the old env-**value** channel is dropped (greenfield — see the
inventory below). Alongside, the server stores an **HKDF fingerprint** of the active KEK in a single DB row: on
every start it compares the loaded key's fingerprint against the stored one. First start (no row) adopts the
current key; a **mismatch** means the loaded key is not the one that sealed the stored secrets — the server then
**degrades** (mail falls back to copy-link, loud log) rather than bricking or silently emitting garbage, and the
owner recovers by re-entering the SMTP password (reseal under the current key → fingerprint updated). The mismatch
(and the residual "key absent" failure) surface to the owner **proactively** via a new read-only
`configSecretState` on the mail-settings read, so the Email panel can say "your stored password can't be decrypted
— re-enter it" instead of showing a false "ready to send". This closes the one residual case
[cr-email-tenant-mail-settings-web](cr-email-tenant-mail-settings-web.md) deferred as Kür (KEK removed/changed
after seal → silent misreport): the fingerprint makes it detectable, so surfacing it is now earned.

## Context & constraints

- **This amends [ADR 0050](../docs/decisions/0050-operator-config-secret-encryption.md) (Accepted 2026-07-24).**
  The amendment changes the **Decision** (KEK source: env-value → a managed key file + an optional path override;
  add the fingerprint tripwire + the degrade-on-mismatch posture) and **Consequences** (the threat-model shift +
  the loss-is-now-detected note), and adds an `## Amendment (<date>)` block recording the change. The core envelope
  (AES-256-GCM via JCA, ciphertext+nonce in the DB, operator-side, not #43) is unchanged. **Honest threat-model
  shift to state in the ADR:** a key *file* + fingerprint defend the **leaked DB backup** — the most common real
  leak — but **not** a full-host compromise (file + DB on one box). That is a deliberate move from "env-only"; the
  env-value posture (key never on the app's disk) is *also* not strictly stronger — an env value has its own leak
  surface (`/proc/<pid>/environ`, `docker inspect`, crash dumps, log/child-process inheritance).
- **The env-VALUE channel is greenfield — safe to drop.** Locator: `grep -rniI "config-secret-key\|CONFIG_SECRET_KEY" . | grep -v /build/`
  finds it only in `application-test.properties` (a dummy test key), `infra/README.md` + ADR 0050 (docs), and
  `ConfigSecretSealTest`. **No** deployment wires it (`.run/`, `infra/docker-compose.yml`, prod properties are all
  clear). So the env-value is removed as the operator path; the **tests** move to the file mechanism (write a temp
  key file + point `config-secret-file` at it) rather than injecting the value — testing the real path.
- **Resolution order (one code path, in [ConfigSecretSeal.kt](../backend/src/main/kotlin/com/veyq/app/shared/crypto/ConfigSecretSeal.kt) / a new resolver):**
  (1) `veyq.security.config-secret-file` path if set → read it; (2) else a default managed path under a data dir →
  read if present, else **generate 32 random bytes, base64, write `0600`, log loudly**; (3) generation failure
  (read-only FS, no perms) → no key → `configured=false` (the existing seal-unavailable posture). The file holds
  the **same base64-32-byte string** the env value did, so `ConfigSecretSeal`'s parse/`require(size==32)` is
  unchanged — only *where the string comes from* changes.
- **Fingerprint (safe by construction):** `fingerprint = HKDF-SHA256(ikm=KEK, salt="", info="veyq/v1/config-secret-key-fingerprint", 32)`,
  base64 — domain-separated (veyq's HKDF idiom; the info string is distinct from any wrap/derive context so the
  stored value can never be confused with key material). A 256-bit full-entropy KEK is **not** brute-forceable from
  its hash, so — unlike a password (which needs salt + a slow KDF) — a fast HKDF fingerprint in the DB leaks
  nothing usable. **Storage:** a single server-global row. Locator for the home:
  `sed -n '1,40p' backend/src/main/resources/db/migration/V4__instance.sql` — if a `veyq_instance` singleton
  exists, add a `config_secret_fingerprint TEXT` column via `V43__…`; else a dedicated one-row table. Implementation
  picks after reading V4.
- **Startup compare → three states** (computed once, exposed via `ConfigSecretSeal`/a small service):
  - `OK` — a KEK is present and its fingerprint matches the stored row (or no row yet ⇒ **adopt**: write the
    fingerprint; this also cleanly migrates any current env-key deployment on its first start post-upgrade).
  - `MISMATCH` — a KEK is present but its fingerprint ≠ the stored row: the loaded key is not the one that sealed
    the stored secrets. **Degrade, do not brick** — the operator-blind rest of veyq needs no KEK, so only the
    config-secret subsystem degrades: `seal`/`unseal` behave as *unavailable* for the send path (→ copy-link
    fallback, exactly like unconfigured), and a **loud startup log** names the recovery ("restore the original key
    file, or re-enter the affected secrets"). The mismatch row is **not** auto-overwritten (that would defeat
    detection) — it clears only when the owner deliberately re-saves a secret under the current key.
  - `ABSENT` — no KEK at all (generation failed): the existing seal-unavailable behavior.
- **Reseal-on-re-enter (recovery):** when the owner saves a new SMTP password while in `MISMATCH`, the handler
  seals it under the *current* key and **updates the stored fingerprint** to the current key's → back to `OK`. With
  one config secret today this fully recovers; note in the ADR that it scales with #43 (more secrets ⇒ a key change
  needs re-entering all of them).
- **Contract delta (the earned read field):** add `configSecretState: OK | ABSENT | MISMATCH` to the
  `TenantMailSettings` **read** schema (`x-veyq-visibility: enum` — a closed set the contract declares, the server's
  own self-description; no user data). It is **read-only** (absent from `TenantMailSettingsUpdate`). This is the
  server fact the web CR could not see; the fingerprint is what makes it a real, honest signal now.
- **Web (small):** the Email panel already handles `ABSENT` reactively (the `503 mail_secret_unavailable` →
  `mail.kekUnavailable`). Now consume `configSecretState` from the read to make it **proactive**, and add a
  distinct **`MISMATCH`** message ("the server's config-secret key changed — your stored SMTP password can't be
  decrypted; re-enter it to reseal, or restore the original key"). Fold the state into send-readiness:
  `sendReady = enabled ∧ host ∧ passwordSet ∧ configSecretState === 'OK'` — which retroactively closes the residual
  case the web CR deferred. Reuse the existing `mail.*` i18n block (EN+DE).
- **Infra/docs:** `infra/README.md` — replace the `VEYQ_SECURITY_CONFIG_SECRET_KEY` note with the managed-file
  story + the **Docker caveat**: the key file MUST live on a **persisted volume**, else every container recreate
  trips the fingerprint (now *loudly*, not silently — the tripwire working, but document the volume). Update the
  `security-model.md` metadata inventory (the KEK is now a file + a DB fingerprint, still no plaintext secret).

## Phases

- [ ] **1 docs + contracts — amend ADR 0050, add the read field.**
  - ADR 0050: the `## Amendment` block (file-managed KEK + path override, env-value dropped; the fingerprint
    tripwire; degrade-on-mismatch; the threat-model shift; loss-now-detected; reseal-on-re-enter scales with #43).
  - `tenants.openapi.yaml`: `configSecretState` (enum `OK|ABSENT|MISMATCH`, `x-veyq-visibility: enum`) on
    `TenantMailSettings` read only; describe it. Not on the update schema.
  - **Done when:** contracts generate; `validate_contracts_common.py`, `validate_field_visibility.py`,
    `validate_adr_citations.py`, `validate_feature_*` green.

- [ ] **2 backend — resolver, file-gen, fingerprint store, degrade, expose state.**
  - A key resolver: path-override → default managed file → generate+write `0600`+loud log → else absent. Drop the
    env-value read (and its `application-test.properties` line); tests supply a temp key file.
  - `V43__…`: the fingerprint row (column on `veyq_instance` or a dedicated one-row table, per V4).
  - Fingerprint compute (HKDF as above) + startup compare → `OK|ABSENT|MISMATCH`, surfaced on `ConfigSecretSeal`
    (or a thin `ConfigSecretKeyState` service). `MISMATCH`/`ABSENT` make the send path fall back (copy-link), never
    emit garbage; loud startup log on `MISMATCH`. `TenantMailSettingsHandler.read` fills `configSecretState`; a
    successful reseal in `MISMATCH` updates the stored fingerprint → `OK`.
  - **Tests (promoted, bite-proofed):** (a) first start with no file **generates** one (`0600`) + writes a
    fingerprint + `configured`; (b) an existing key **adopts** on first start (fingerprint written, state `OK`);
    (c) a **changed** key ⇒ `MISMATCH` detected, send **degrades** to fallback, no exception/garbage; (d) the
    stored fingerprint is **not** the KEK (≠ the key bytes; a hash) — the DB-dump property holds; (e) a path
    override is honored; (f) reseal in `MISMATCH` updates the fingerprint → `OK`.
  - **Bite-proof** the mismatch-detection + the fingerprint-is-not-the-key guards.
  - **Done when:** `./gradlew -p backend check` green; guards bite; `seed:dev` still works (it generates a key).

- [ ] **3 web — proactive state in the Email panel.**
  - Consume `configSecretState`; make `ABSENT` proactive (reuse `mail.kekUnavailable`), add a distinct `MISMATCH`
    message + i18n keys (EN+DE); gate `sendReady` on `configSecretState === 'OK'`.
  - **Tests:** the panel shows the `MISMATCH` message + is not send-ready when the read says `MISMATCH`; `ABSENT`
    shows the KEK message proactively.
  - **Done when:** `npm run generate:api && npm run check` green.

- [ ] **4 coverage.**
  - Add ACs to `uc.admin.tenant-mail-settings`: the KEK is a managed key file generated on first start (backend);
    a lost/changed key is **detected** via a DB fingerprint and **degrades** rather than failing silently (backend);
    the owner sees the changed-key state proactively and can recover by re-entering (web). Refs on both surfaces.
  - **Done when:** `python3 scripts/validate_coverage.py` green.

## Definition of done

`./gradlew check` + `npm run generate:api && npm run check` green; ADR 0050 amended (env-value dropped, file +
fingerprint added); `docs/coverage/` updated for `uc.admin.tenant-mail-settings` with the new ACs and
`validate_coverage.py` green; **every** guard green (`for f in scripts/validate_*.py; do python3 "$f"; done`). The
two structural invariants each rest on a promoted, bite-tested guard: **no usable secret in a DB dump** (the stored
value is a fingerprint, provably not the key) and **mismatch is detected, never silently wrong** (a changed key
degrades loudly). `seed:dev` + a fresh instance both work zero-config. The residual case the web CR deferred is
closed. Docker volume + backup guidance documented.

## Retrospective

- **Planning — a genuine, principled reversal, and the meta-lesson behind it.** Two CRs ago I proposed a
  `sealAvailable` read field, the user pushed back ("wofür?"), and I retracted it because `passwordSet` already
  implied the KEK. This CR *reinstated* a read field (`configSecretState`) — not a flip-flop but the correct move:
  the fingerprint is a **new capability** (the server can now *detect* a changed key), and a capability can turn a
  "not worth surfacing" into "worth surfacing". The lesson: whether to expose a server fact depends on whether the
  server can *know* it reliably — which the fingerprint changed. I should name that dependency explicitly when
  first weighing such a field, rather than only discovering it a CR later.
- **Execution — a state nearly went dead, kept honest.** Auto-generating the KEK means `configured` is almost
  always true, so `ABSENT` risked becoming unreachable (and the contract enum a lie). I kept it a real state
  (generation *failure* on an unwritable path) and bite-tested it via a path whose parent is a file. Without that
  check the ABSENT branch + its UI message would have been untested theatre.
- **Execution — a test simulation I'm honest about.** The MISMATCH tests seal under the current key, then write a
  *bogus stored fingerprint* to force `state() == MISMATCH`. That exercises the exact gate/degrade path, but it does
  **not** reproduce a real key-file swap end-to-end (which needs two boots). Named as a limit in the tests; the
  fingerprint-derivation itself (different key ⇒ different fingerprint) is separately proven pure-JVM.
- **Design — kept `ConfigSecretSeal` DB-free on purpose.** Folding the fingerprint compare into the seal would
  have broken its pure-JVM testability (its tests construct it with only config). Splitting the DB-touching
  compare into `ConfigSecretKeyGuard` kept the seal unit-testable and the guard integration-tested — the split was
  worth the extra type.
- **Process — honored the agreed rules.** German conversation / English code; grounded the backend surface via an
  Explore subagent (the delegated-reading rule) before editing; waited for the explicit "los" between plan and
  build; bite-tested the two load-bearing guards (fingerprint-is-not-the-key, mismatch-is-detected). One fragility
  handled rather than ignored: the JVM-shared H2 suite meant the mismatch tests had to `finally`-restore the
  fingerprint, or they'd cascade failures into later mail tests. *Next time:* for any test that mutates a shared
  singleton, write the restore in the same edit as the mutation, not after seeing a flake.
