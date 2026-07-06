# ADR 0026 — Client trust tiers & the operator-blindness boundary

- Status: Accepted
- Date: 2026-07-06

## Context

Operator-blind E2EE is the invariant, but confidentiality is enforced by the **client**, and the client's code
has to come from somewhere. A client whose code the operator can silently alter **cannot** hold the guarantee
against an *actively* malicious operator — it could be served tampered code that leaks keys or plaintext. This is
the well-known browser-crypto delivery problem (Matasano, 2011), mitigated in practice by native-only stances
(Signal has no web app), third-party code attestation (WhatsApp + Cloudflare "Code Verify"), store-signed
extensions (MEGA, password managers), and open-source + reproducible builds (Proton, Bitwarden, Tuta) — but never
fully *solved* for operator-served web.

veyq is **self-hostable and multi-operator**, which sharpens it: a hosting operator serves the web client to
*other* users who must be able to **distrust that operator**. We need to state precisely what is guaranteed, per
client, so features (consent-add, sharing, key exchange) place cleanly and we never over-claim.

## Decision

**Two enforcement layers** (the general form of the layers [security-model](../crosscutting/security-model.md)
§ Revocation already uses):

- **Layer 1 — access control (honest-operator, instant, no keys).** The server serves or withholds opaque
  ciphertext by ACL — membership, share tokens, consent, revocation. It reads no content. **It is not a defence
  against the operator** (the operator runs the server); L1 protects users *from each other* and gives instant
  access changes, valid only under an honest operator.
- **Layer 2 — confidentiality (cryptographic).** Content stays encrypted; the server never holds content keys.
  **The only guarantee that holds against an *actively* malicious operator — and only on a client whose code the
  operator cannot alter** (client integrity).

**Client trust tiers** — confidentiality against an *active* operator:

| Tier | Holds? |
|---|---|
| **Native (store-signed)** | **Yes.** The operator can't alter the client; residual = *detectable* key-substitution on **new** exchanges (§ key-directory trust). |
| **Operator-served web** | **Passive only.** An active operator can serve tampered code that captures keys/plaintext. |
| **Trusted central / attested web** (code origin the operator does *not* control; reproducibly built — SaaS-era) | **Yes,** at the trust of the central origin. |
| **Self-hosted, single user** | Moot (operator = user). |

Constant on every tier: the server can **never** read already-stored content (no keys); it can **always** see
operational metadata, deny/alter access, and *attempt* key-substitution on new key exchanges (detectable via the
fingerprint/safety-number seam). The honest boundary in one line: **passive operator → protected everywhere;
active operator → protected on native / trusted-web, passive-only on operator-served web.**

**Positioning:** **native is the high-assurance primary** (committed target: native on every platform);
**operator-served web is the convenience / passive-safe tier, honestly labelled** and surfaced in-client. Web's
eventual minimal scope (public shares + initial setup — the no-install flows) is a later end-state call, not fixed
here.

## Consequences

- **veyq never claims operator-served web is operator-proof.** The client **surfaces its tier** to the user and
  the wiki/security-model state the boundary plainly — an honesty that is a **trust asset**, not a weakness (the
  Signal/Proton posture).
- **Native-everywhere is a committed platform obligation** (incl. Windows/Linux desktop) — the price of the
  high-assurance primary.
- **Web-delivery hardening is deferred (SaaS-era), and now smaller:** a central/GitHub-hosted, reproducibly-built
  **trusted web client** is needed only for the **no-install share/upload viewer**; **branded + trusted** domains
  require veyq to control the DNS → SaaS. An **operator-owned** domain is *always* operator-served/honest-operator
  (it owns its DNS → controls code delivery; no CNAME/attestation trick durably removes that). Tracked in the inbox.
- **The key-substitution residual persists** on every tier until fingerprint/TOFU/KT (the existing seam,
  [ADR 0020](0020-grant-distribution-invite-wrap.md) / security-model § key-directory trust).
- **Rejected:** claiming browser-delivered E2EE is operator-proof (dishonest); mandating native-only *now* (reach
  cost + contradicts the current web-first build order — native-everywhere is a future state, web stays the
  transitional primary).
