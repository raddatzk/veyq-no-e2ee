# ADR 0004 — Session tokens: opaque, server-stored, rotating access + refresh in named families

- Status: Accepted
- Date: 2026-06-25

## Context

After challenge-response authentication ([ADR 0003](0003-operator-blind-password-auth.md)) the server
issues a **session**. The session form was deliberately left open by
[uc.account.login](../features/account/use-cases/uc.account.login.md). Forces on the choice:

- **Operator-blind.** A session gates *a session + server role over ciphertext* — it **never** unlocks
  content (auth ≠ unlock, see [Account & Key Model](../crosscutting/account-key-model.md)). So a stolen
  session yields metadata + the ability to operate over ciphertext, **never plaintext**. The session
  layer must not become a metadata leak either (no cleartext device fingerprints server-side).
- **Self-hostable, single-instance default.** Stateless horizontal scaling — the one real argument for
  JWTs — carries little weight here.
- **Multi-surface.** Web wants an httpOnly cookie (XSS can't read it); native and the `http/` surface
  want an `Authorization: Bearer` header.
- **Theft detection.** Instant revocation handles the *detected* compromise; we also want to catch the
  **undetected** one (a cloned token we were never told about).

## Decision

**Opaque, high-entropy tokens, server-stored as hashes**, in a **two-token rotating scheme** grouped
into **named session families**. No JWTs.

- **Two tokens.** A short-lived **access token** rides every request; a long-lived **refresh token** is
  sent only to the refresh endpoint. Both are opaque and validated against server state; the server
  stores only their **hashes** (a DB dump yields no usable session — consistent with `authPublicKey`
  being a non-credential).
- **Session family = one login/device.** A successful login creates a family. **Refresh rotates:** each
  refresh mints a new access + new refresh token and supersedes the prior refresh (numbered generations).
- **Reuse detection.** Presentation of a **superseded** refresh token (an older generation) is a clone
  signal ⇒ **revoke the entire family**. This catches the undetected-theft case that proactive revocation
  misses. To avoid a denial-of-service where a guessed `familyId` force-revokes a family, the superseded
  token's **secret is verified** before revoking — the server keeps enough state (current + previous
  generation hash) to verify the realistic reuse window; an older, unverifiable token is merely *invalid*,
  not a family-killer.
- **Refresh expiry ⇒ full re-login** (challenge-response). Sliding lifetime via refresh; idle-expiry on
  the refresh token.
- **Named families, E2EE, operator-blind.** A login creates a family **without a name** — the server
  assigns only an opaque **family id**. The naming is **client-built and end-to-end encrypted**: once the
  device is **unlocked**, it derives a best-effort display name from its own environment
  (`navigator.userAgent` → "Safari on macOS"; native → "Kevin's iPhone"), encrypts it **under the account
  key** and stores the ciphertext on the family (`PUT …/sessions/{id}/name`). A later "active sessions"
  view (any unlocked device of the account) lists families and **decrypts** each name; the locked view
  shows only the id + a coarse created-date. Constraints that fall out of this:
  - **Why post-unlock, not at login.** Auth produces only a signature, no symmetric key — the account key
    exists only after **unlock** (auth ≠ unlock). The encrypted name is therefore attached **after**
    unlock, not in the login call. (The plaintext name is known locally at login for immediate *local*
    display; the server copy — which exists so *other* devices can show this session — is written once
    unlocked.)
  - **Re-encrypt fresh per login**, never resend a cached ciphertext: an identical name blob reused across
    families would let the operator **correlate** a device's re-logins, re-linking otherwise-unlinkable
    families. A fresh nonce each time keeps families unlinkable.
  - **Best-effort, not a contract.** A browser cannot read the machine/hostname, so web names are coarser
    ("Safari on macOS") than native ("Kevin's iPhone").
  - The operator sees only **ciphertext name blobs**, opaque family ids, coarse timestamps and a device
    *count* — **never** a cleartext device/user-agent string. (A server-generated cleartext codename was
    considered and **rejected** — superseded by this E2EE scheme.)
- **Transport.** Same opaque value, two transports: httpOnly + Secure + SameSite cookie (web) /
  `Authorization: Bearer` (native, `http/`).

## Consequences

- **Instant revocation** is intrinsic (delete / mark the family revoked) — logout, "log out everywhere,"
  and theft response are all one operation.
- **Undetected theft is caught** by rotation + reuse detection — the gap a single revocable token leaves
  open.
- **Operator stays blind.** The server sees only ciphertext name blobs, opaque family ids, coarse
  timestamps, and a device *count* — never a cleartext device fingerprint. Revoking a session works at the
  **auth layer** (no unlock needed); **reading** the names needs unlock (they are E2EE), so the locked
  sessions view shows only id + coarse created-date.
- **Smaller blast radius.** The powerful long-lived credential travels only to the refresh endpoint, not
  on every request.
- **Cost:** more moving parts than a single token — a refresh endpoint, rotation/generation bookkeeping,
  and client retry-on-`401`→refresh logic. Accepted for the theft-detection + session-management value,
  which the user explicitly wanted in the foundation rather than as later hardening.
- **Rejected — stateless JWT access tokens:** non-revocable, so they need a short TTL **plus** a denylist
  anyway (re-introducing state), with `alg`/key-management pitfalls; the only upside (stateless scale)
  is irrelevant for a self-hosted instance.
- **Rejected — single opaque token (no refresh):** simplest and already revocable, but no
  undetected-theft detection, and the long-lived credential rides every request. The family/rotation
  machinery — and the named-session management it enables — justified building it now.
- **Rejected — cleartext device names server-side** (e.g. storing the user-agent as the session label):
  an operator-blind violation. Names are **E2EE** (client-built, encrypted under the account key).
- **Rejected — server-generated cleartext codename** (e.g. `violet-otter`): operator-meaningless and
  unlock-free, but superseded by the E2EE scheme, which gives **real, recognizable** names at no blindness
  cost. Not worth carrying a second naming mechanism.
