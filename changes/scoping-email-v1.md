# Scoping — Email (V1 theme)

> **Status: refine / scoping only. Not a CR, nothing built.** This is pre-work that will become
> `cr-email-*` (or several) once the open decisions below are made and a "go" is given. Deliberately
> **not** named `cr-*.md` so the change-request guard ignores it; delete or fold into the CR(s) when it
> graduates.
> All `path:line` facts are **measured** (code/contracts/migrations/docs as of 2026-07-23). Anything
> inferred is marked. **[DECISION]** marks a call the owner must make. `#NN` are `docs/inbox.md` row
> IDs, **not** GitHub issues.

## What we want to achieve (one paragraph)

veyq gains a real, self-hostable, operator-blind **mail-send capability** for the account lifecycle —
so invitations and address verification can be delivered by email instead of by copy-pasted links —
**without weakening the E2EE guarantee**: no escrow, no content or scope/group names in any mail, generic
SMTP (no vendor lock). The invite link stays a pure admission token, never a key (ADR 0020). On-premise
stays invite-only; open self-registration is a SaaS-mode feature behind a flag and is deferred. **Recovery
and device-linking are explicitly out of this theme** — each is its own topic (see §11).

---

## 0. Core correction — measured vs. the starting assumption

The trigger framed email as "a new data class not in the server-visible metadata list" and recovery as
"no-escrow / device-based, so email changes the model". Measured reality sharpens both:

- **Email is not a new data class — it is already load-bearing plaintext PII.** `veyq_user.email
  VARCHAR(254) NOT NULL UNIQUE` exists since `V2__add_user_email.sql`; it is the **login identifier**
  *and* the source of the deterministic auth salt (`authSalt = HKDF(instanceSalt, normalizedEmail)`,
  ADR 0003, Model B), tagged `x-veyq-visibility: pii` across the contracts.
- **The per-tenant `username` is a separate handle** (on the membership/profile), not the account
  identifier. The starting assumption conflated the two.
- **The real doc gap (valid finding):** `docs/crosscutting/security-model.md:62-79` lists `username`,
  identity-pubkey, consent-flag — but **not** the account email. An already-present plaintext PII field
  is missing from the metadata inventory. That is exactly what **#43 (at-rest PII)** targets; the
  immediate step is a *doc* fix, not a new field.
- **What email-as-a-feature genuinely adds is at *send time*, not at rest:** the operator **and** a mail
  provider learn *who receives which mail, when* (invite/recovery/verify traffic) — a **new metadata
  channel** and a **new third-party trust boundary** (same shape as the ADR 0016 geocoding boundary).
- **Recovery: the model does shift, but the design already exists and email does not recover keys.**
  `KeyMaterialHandler.kt:36-43` already withholds the `RECOVERY` wrap "released only by the future
  email-gated recovery flow" (cr-0052); the `recovery_master_key_tag` column is present. Email gates
  *release of a still-encrypted blob*, which the user opens with a recovery secret they hold — see §4.

**Net:** not "new data class + recovery rebuild", but *under-documented-but-existing plaintext PII* +
*a new send-time metadata channel* + *a recovery **authorization** gate (not escrow)*.

---

## 1. Current state of the four flows (measured)

| Flow | Today | Email involved? |
|---|---|---|
| **Registration** | Two closed paths, **no open sign-up**: Server-Owner bootstrap (`POST /api/setup/owner`) and invite redemption (`POST /api/tenants/accept/register`, email taken from the invite, not the body). | Address stored (plaintext); **no send**. |
| **Login** | Challenge-response over a password-derived Ed25519 keypair (ADR 0003): `POST /api/login/challenge {email}` → nonce; `POST /api/login/verify` signs the nonce. No password POST, **no magic link**, no PAKE. Non-enumerable (same response for existing/non-existing). | No. |
| **Verification** | **Does not exist.** No confirm endpoint, no verify column/token. Nearest relative: invite-secret possession as *de-facto* address-control proof (*inferred*, not labelled as such). | No. |
| **Recovery** | **Unbuilt** but scaffolded: `RECOVERY` wrap type + `recovery_master_key_tag` stored and deliberately withheld from key-material. Design = no-escrow, 3 paths (other device / Emergency Kit / recovery code). Docs: "planned". | Design says **email-gated**, unbuilt. |

**Invite flow (the only one minting redeemable tokens):** `backend/.../admin/tenant/`. Token =
`Tokens.random()` (32 B `SecureRandom`, base64url); server stores **only** `Tokens.hash(token)`
(SHA-256). The **raw** token is (a) returned in the API response (by design, cr-0080: "works without a
mailer, copy the link") and (b) handed to `mailer.sendInvite(...)`. Today delivery = **copy-paste
link** in the web UI (`web/src/shared/inviteLink.ts` → `…/tenant/accept#<secret>`, token in the URL
fragment, never sent to the server). **No real mail send exists** (see §5).

---

## 2. The invite-token logging leak — assessed separately

**Measured location:** `backend/.../admin/tenant/TenantInviteMailer.kt:57`

```kotlin
val link = "https://$domain/tenant/accept#$token"
log.infof("Tenant invite for %s (expires %s): %s", ownerEmail, expiresAt, link)
```

The logged `token` **is a redeemable bearer secret** (the exact value `TenantAcceptHandler` looks up by
hash to redeem) — **not** an opaque id. Worst case (via `@PermitAll registerAndAccept`): an OWNER
bootstrap invite whose email has no account yet lets a token-reader **cold-birth and seize the pending
tenant as owner**; a MEMBER invite grants tenant admission (content follows via the *automatic*
first-online-wins grant delivery, ADR 0020).

**The decisive measurement — blast radius:** lines 48-55 **short-circuit in `LaunchMode.NORMAL`
(prod)** — they log a secret-free WARN and `return` **before** line 57 runs. The same gate protects the
dev-file (`{ownerEmail, token, expiresAt}` jsonl, cr-0046). **→ In production the raw token is not
logged.**

**Verdict, deliberately against the trigger's assumption:**

- **Not the production leak the trigger assumed** ("apparently lands in the log"). What is visible in a
  log is the **dev path** (local, non-NORMAL) — consistent with the prod gate holding. *Good news,
  measured.*
- **Immediately fixable & small (independent of the email rebuild):**
  1. Do **not** log the raw token even in dev — prefix/hash only. Same for the dev-file. (The dev-file
     is superseded by the test mailbox in Slice 1 anyway, §6.)
  2. **[DECISION 6]** Confirm no staging/preview/demo instance runs non-NORMAL — the single
     `LaunchMode` check is the only thing carrying the prod protection.
- **Part of the rebuild (not now):** that the link is the *primary* channel at all (raw token in the API
  response + clipboard). A real mail send makes the link a delivery detail rather than a copy-paste
  artefact.
- *Inferred, open:* standard HTTP access logs do not capture the token (fragment never sent, POST bodies
  not logged by default); access-log config was **not** audited — a separate check if in scope.

**→ Recommendation: pull Slice 0 ("token redaction + deployment-mode check") now, decoupled from the
email theme.** Not an urgent prod incident, but cheap hardening that directly answers the trigger.

---

## 3. Operator-blind design forks

### 3a. Where the email lives — plaintext / hashed / sealed

Strongly constrained by ADR 0003 (measured): the server needs the address in **plaintext** to (i) look
up the row on `login/challenge` and (ii) **necessarily hand it to the mail provider at send time**. The
auth salt is derived client-side, so a **blind-index hash** for the login lookup is *theoretically*
possible — but sending forces plaintext.

| Option | Assessment |
|---|---|
| **A — Plaintext (status quo)** | Simplest; matches today. **Moot for self-hosted** (operator = user). Weakest SaaS posture. |
| **B — Blind-hash for login lookup, plaintext only transiently for pending sends** | Reduces at-rest exposure but complex; any address that must receive *future* mail (repeated recovery) needs plaintext retention → benefit partly eroded. |
| **C — Sealed under a server-scope key, role-gated decryption** | This is **#43 territory** (multi-ServerOwner + server-scope key). No artefact exists. |

**[DECISION 1] At-rest posture for V1.** Recommendation: **Option A** — it *is* the status quo, ADR 0003
depends on it, self-hosted makes it moot; but **close the doc gap** (add email to the
`security-model.md` list) and **delegate hardening to #43** (SaaS era). State honestly: sealing is partly
foreclosed by ADR 0003.

### 3b. What goes in a mail — and what never

Follows the ADR 0016 consented-third-party pattern:

- **In:** the invite/recovery/verify **link** (admission token, unchanged), tenant *domain*, expiry,
  neutral copy.
- **Never:** group/scope names, memory titles/content, member lists, real names (sealed under
  `K_tenant`) — nothing the provider does not need to deliver.
- **Wiki convention:** mail templates bilingual **EN+DE** (like `wiki/`).

### 3c. The invite link must not become a key — already decided

ADR 0020 **explicitly rejects** a key-bearing invite link, precisely because "anyone who intercepts the
(emailed) invite gains scope content". The emailed invite stays an **admission token**, never a key.
This is a guardrail, not an open question — do not soften it in the mail design.

---

## 4. The recovery-model change — the hard question, answered honestly

**Can email recovery restore the data, or only account access? → Only account access, and it must stay
that way, or the one guarantee breaks.** Measured + design:

- There is **no escrow**. The server holds no key that opens content. An email that "resets the password
  and then you can see everything again" would be escrow and is **forbidden**.
- In the intended design, **email gates only the *release* of the withheld `RECOVERY` wrap blob**
  (`KeyMaterialHandler` withholds it today). That blob is still openable only with a **user-held recovery
  secret** (recovery code / Emergency Kit). Then: new password → new auth keypair → re-register
  `authPublicKey`; the Master Key is unchanged, so **content survives**. `recovery_master_key_tag` proves
  the *same* Master Key was recovered.
- **Honest consequence:** email = an **authorization gate** (+ rate-limit / anti-enumeration) on the blob
  release. Whoever controls the email **but holds no recovery secret gets nothing.** Losing every
  no-escrow path is the documented, accepted total loss — email does not change that.

**[DECISION 2] — the biggest scope question:** the *underlying* recovery flow (cr-0052) is **itself
unbuilt**. Is "Email" the theme that **finally builds recovery** (email slice + recovery flow coupled,
large), or does "Email" stop at verification + invites while recovery stays its **own** theme?
Recommendation: **decouple** — Email V1 ships the *gate* (address-control proof) as an enabler; the full
key-recovery flow is a separate theme that consumes it. Otherwise "Email" drags in all of key recovery.

---

## 5. Send infrastructure & self-hosting (measured)

- **No mailer present — confirmed green.** No `quarkus-mailer` / `ReactiveMailer` dependency, no SMTP
  config in any `application*.properties`, **no mail container** in `infra/docker-compose.yml` (only
  mariadb + opt-in s3/garage).
- **What exists:** only the **port** `TenantInviteMailer` (interface) + `LoggingTenantInviteMailer`
  (stub). Docs say plainly "real SMTP is an infra/runbook concern, deliberately out of scope" and "A real
  SMTP-backed mailer must be wired." The product design deliberately **routes around** a mailer (copy
  link) → onboarding stays self-hostable without SMTP.
- **Self-hosting promise → provider-neutral SMTP.** Mirrors the S3-SDK-not-MinIO-client and
  geocoding-provider-neutrality precedent: transport must be **generic SMTP** (config-driven,
  self-hostable), **not** a proprietary provider SDK. The port already exists → an SMTP impl is purely
  additive.
- **#38 (SaaS-Ops)** and **#40 (Legal)** are untouched inbox pointers. **Deliverability ops** (SPF/DKIM/
  DMARC, bounce handling, reputation) → **#38**. **Legal/privacy of holding addresses + sending** →
  **#40**. Both **explicitly later**, not V1.

---

## 6. The test mailbox (#100 relation) & the #102 e2e debt

**What fits veyq:** a **Mailpit** catch-all container (HTTP API for programmatic retrieval) in
`infra/docker-compose.yml` under a `mail` profile. *Per the CLAUDE.md "no EOL deps" rule: **MailHog is
archived → use Mailpit** (the maintained successor), not MailHog.* Flow: send → poll Mailpit API →
extract link → follow. This also retires the on-disk-token class of today's dev-file (§2).

**Relation to the e2e debt — measured and honestly separated:**

- **#102** (wire assertion "ciphertext is actually ciphertext") is **blocked on the e2e debt**: the
  orchestrator `scripts/run_web_e2e.py` **does not exist** (CI job `web-e2e` is `if: false`, "lost in
  the greenfield reset — must be REWRITTEN"). The `*.e2e.test.ts` are Vitest, `skipIf(!VEYQ_E2E)`, run
  only manually against a live backend.
- **The mailbox does NOT resolve #102.** The orchestrator rewrite (boots backend+store+web, drives
  flows) is still required. **But:** the mailbox is a **natural component** of that rewrite, and email
  flows (verify → follow link → land in app) are testable **only** with the full stack + a mailbox — so
  they **add weight** to paying the #102 debt rather than sidestepping it.
- **#100 (sandbox):** a catch-all means the agent loop **never sends real mail to real addresses** — mild
  alignment with #100's containment goal, but #100 (egress sandbox) is a **separate** concern, not solved
  here.

**[DECISION 3] — test mailbox as a wedge for #102?** Build the mailbox now as the first brick of the
future #102 orchestrator, **or** test email flows with a thinner mailbox-only harness and keep #102
separate? Recommendation: build the mailbox so it **snaps into** the later orchestrator, but do not make
#102 a prerequisite for Email V1.

---

## 7. Account-provisioning fork (deployment-mode-conditional)

The question is **"what may mint an account?"**, and it splits cleanly by deployment mode.

| Model | Today? | Fits |
|---|---|---|
| **A — Invite-only (account born *from* the invite)** | **Status quo, measured.** `registerAndAccept` mints the account; no open sign-up endpoint. | **On-prem** |
| **B — Open self-registration (account exists *before* any invite)** | **Does not exist** (net-new). | **SaaS** |

On-prem = invite-only is already the default and needs **zero** new surface. The net-new is *only* SaaS
self-registration. Three consequences:

1. **This answers [DECISION 4] (verification mandatory vs optional) — it is mode-conditional.**
   Invite-only gets address-control proof *for free* by redeeming the emailed invite (verification
   quasi-optional). Open registration makes verification **mandatory and load-bearing** — else someone
   registers `alice@x.com` without owning it, **squatting the globally-unique `veyq_user.email`** and
   poisoning the auth-salt binding.
2. **It does not force the ADR-0020-rejected global directory — as long as invites stay email-addressed.**
   Even in SaaS you can invite by **raw email** (existing account → authenticated accept path; none →
   register-and-accept) with no "does an account exist for X?" lookup. The enumeration surface appears
   only if a *"search a user to invite"* UX is built — which ADR 0020 rejected and which stays rejected.
   **Guardrail: invites stay blind-addressed, no directory search.**
3. **"Register-then-invite" is already half-present for *existing* accounts (measured):** the
   authenticated accept path `POST /api/tenants/accept` (`caller.email == invite.ownerEmail`) attaches an
   existing account to another tenant with no minting; `registerAndAccept` fails `EmailTaken` if the
   account exists. The seam for "one global account, many tenant memberships" exists. *(Measured from the
   endpoint shapes; the full multi-tenant membership data model was not separately audited.)*

**[DECISION 5] Account-provisioning policy.** Recommendation, matching web-first/self-hosted-first:
**Email V1 targets invite-only (status quo, no new registration surface).** Open self-registration + its
mandatory verification gate are a **SaaS-mode feature behind a config flag, deferred out of V1** — this
keeps "Email" on its core (delivering invites/recovery/verify) instead of opening registration and
pulling in the abuse/legal surface (spam sign-ups, email-bombing, #40). ADR-worthy; related to **#43**
(multi-ServerOwner) and **#38** (SaaS-Ops).

---

## 8. ADRs needed

1. **Email delivery as a send-time metadata & third-party boundary** — what the operator/provider learn;
   what may / may not go in a mail (§3b). Pattern: ADR 0016. *New, load-bearing.*
2. **Email-gated recovery authorization (no escrow)** — pins: email gates *release* of the `RECOVERY`
   wrap, never recovers keys, still needs a user secret (§4). *New, the "we did not break the guarantee"
   record.*
3. **Provider-neutral SMTP transport** — generic SMTP, self-hostable, no vendor SDK (§5). Pattern:
   S3-SDK / geocoding neutrality. *New, small.*
4. **Account-provisioning policy — invite-only vs open self-registration, deployment-mode-conditional**
   (§7). *New.*
5. **Email verification semantics** — what address-control proof gates; whether unverified accounts may
   exist. *Possibly folded into #1 or #4.*
6. **At-rest email posture** — **no new ADR now**; close the `security-model.md` doc gap + delegate
   hardening to **#43** (§3a).
7. *(If magic-link login is pursued — §9 — its own ADR; recommended: not now, record only as a rejected
   alternative.)*

---

## 9. Rough V1 scope (slices; order contracts → backend → web)

| Slice | Content | Crypto risk | Depends on |
|---|---|---|---|
| **0 — Token redaction** *(now, independent)* | Raw token no longer in dev log/dev-file (prefix/hash); deployment-mode check | none | — |
| **1 — SMTP transport enabler** | Real `Mailer` behind the port (generic SMTP, config-driven); template seam EN+DE; **Mailpit** dev container | low | ADR 1, 3 |
| **2 — Invite over email** | Actually send the invite mail (link = admission token, crypto **unchanged** per ADR 0020); copy-link stays a fallback for SMTP-less self-hosters | low (transport) | Slice 1 |
| **3 — Email verification** | Address-control proof; quasi-optional invite-only V1, becomes mandatory only with open registration (§7) | low | Slice 1, ADR 4/5 |

Email V1 stops at Slice 3. Recovery is **not** a slice here (decided — §11). ADR 2 (recovery-gate) moves to
the Recovery topic; the ADRs Email V1 actually needs are **1, 3, 4** (§8).

**Deliberately out of Email (decided 2026-07-24):**

- **Recovery (incl. any email-gate)** → its own topic, started once Email is through (§11). The Recovery
  topic *consumes* the transport this theme ships; it needs no email work inside Email V1.
- **Device-linking** (authorize a new device from a trusted one) → its own topic, **not email** at all
  (§11).
- **Login mechanics unchanged.** Magic-link login is **out** (decided): in an E2EE product it authenticates
  but cannot unlock (unlock needs password + Secret Key), so it only adds an email round-trip on top of a
  still-required password. The challenge-response login (ADR 0003) stays as-is.
- **Open self-registration** (§7) → SaaS-mode, behind a flag.
- **At-rest sealing/hashing of the email** → **#43**.
- **Multi-ServerOwner / server-scope key** → **#43**.
- **Deliverability ops** (DKIM/DMARC/bounce) → **#38**.
- **Legal/privacy of holding addresses & sending** → **#40**.

---

## 10. Decisions — resolved (2026-07-24)

1. **At-rest posture V1 → plaintext.** Keep the status quo; close the `security-model.md` doc gap
   (add email to the metadata inventory); hardening (hash/seal) delegated to **#43**. Sealing is partly
   foreclosed by ADR 0003 anyway, and self-hosted makes at-rest moot.
2. **Recovery → out of Email entirely, its own topic after Email** (§11). Not even a gate ships in Email V1.
3. **Test mailbox → build it so it snaps into the future #102 orchestrator, but #102 is not a prerequisite
   for Email V1.**
4. **Verification → resolved by (5):** quasi-optional under invite-only V1; mandatory only once open
   registration ships.
5. **Account-provisioning → invite-only V1**, open self-registration deferred behind a SaaS-mode flag.
6. **Slice 0 → shipped** (`fix(backend): never log the raw tenant-invite bootstrap token`, commit
   `e3fdcfd4`). *Open operational check for the owner:* confirm no staging/preview/demo instance runs a
   non-`NORMAL` launch mode (the log leak is now closed unconditionally, but the dev-file still writes the
   token outside `NORMAL`).
7. **Magic-link login → out.** Authenticates but cannot unlock in an E2EE product; recorded as a rejected
   alternative. What the owner actually wanted here is **device-linking** — a different capability, §11.

---

## 11. Spun-off topics (out of Email; recorded here so they aren't lost)

- **Recovery** — the full key-recovery flow (recovery code / Emergency Kit / re-wrap / new password →
  re-register `authPublicKey`, plus the email-gated release of the withheld `RECOVERY` wrap). A large,
  crypto-sensitive theme; scaffolding exists (`recovery_master_key_tag`, the `RECOVERY` wrap method,
  `KeyMaterialHandler` withholding it) but the flow is unbuilt. **Own topic, started once Email is
  through**; ADR 2 (email-gated recovery authorization — no escrow) belongs to it, not Email. Ground the
  true current state (esp. whether a `RECOVERY` wrap blob is produced at registration today, or only the
  tag) before slicing.
- **Device-linking** — authorize a *new* device from an *already-trusted* one so the new device unlocks
  **without** the Secret Key / Emergency Kit (Signal-style "link a device"). **Not an email feature** (no
  mail). E2EE-native: unlike magic-link it solves auth **and** unlock, because the trusted device wraps the
  Master Key to the new device's key — a natural extension of the existing `MasterKeyWrap` model (ADR 0005).
  The QR scan is the operator-blind anchor (out-of-band pubkey defeats server key-substitution — reuses
  ADR 0028's safety-number/QR machinery). Two UX directions of differing cost: *new device shows QR →
  trusted device scans* needs **no push**; *new device asks → push wakes the other devices → they show/scan*
  needs a **push channel that does not exist today** (greenfield). Scoped separately (its own scoping doc).

---

## Measured vs. inferred

- **Measured:** every `path:line` fact above — the two registration endpoints, the challenge-response
  login, the absent verification, the withheld `RECOVERY` wrap + `recovery_master_key_tag`, all email
  columns/DTOs, the `TenantInviteMailer.kt:57` log + the `LaunchMode.NORMAL` gate, the token shape, the
  absent mailer (deps/config/container), the dev-invite-file mechanism, the e2e test list + missing
  `run_web_e2e.py` + CI `if: false`, the `security-model.md` metadata list, ADRs 0003/0016/0020/0026/
  0034/0035, inbox rows #38/#40/#43/#100/#102.
- **Inferred:** that invite-secret possession is *de-facto* address-control proof (no code labels it
  "verification"); that email-send is greenfield-additive on the existing port; that today's e2e wiring
  is fully manual (seed:dev + hand-run vitest); that the recovery design's operator-blind properties come
  from the ADR/enabler docs (intent), the flow being unbuilt; that the multi-tenant membership seam
  follows from the accept endpoint shapes (full data model not separately audited).
