# Crypto Wire-Format Specification (suite `v1`)

> The **second contract** of veyq, alongside the [OpenAPI](../../api) specs. OpenAPI fixes the *shape*
> of requests; this page fixes the **byte-exact** format of every key, wrap and blob — so a wrap produced
> on Android opens in the browser and on a Mac. Three independent client crypto stacks
> (`@noble/*` on web, CryptoKit on Apple, BouncyCastle on Android) **must** agree bit-for-bit; the shared
> [test vectors](#conformance--test-vectors) are the source of truth, and any divergence is a **spec
> bug**, not a client bug. The friendly model behind this is [Account keys, devices & recovery](account-key-model.md);
> the threat model is the [Security Model](security-model.md).

This is the **v1** suite — exactly what Milestone-1 slices (`setup-server`, `login`) need: the identity
keypair, the master key, and the per-method master-key wraps. Content-key, scope-key and share-key
formats are added here as the slices that need them land.

## 1. Conventions & encodings

- **Byte strings** are raw octets; on the wire (JSON) they are **base64url without padding** (RFC 4648 §5).
- **Integers** in binary structures are **big-endian, fixed-width** as noted (`u8`, `u16`, `u32`).
- **Concatenation** is written `a ‖ b`. To prevent ambiguity, every multi-input KDF concatenation is
  **length-prefixed**: each part is encoded as `u32 len ‖ bytes` (call this `lp(x)`).
- **Labels** are ASCII, no terminator, used verbatim as HKDF `info` / domain separation.
- **Strings** (passwords) are **NFC-normalized UTF-8** before use.
- All randomness is from a CSPRNG. Nonces are never reused under a given key.

## 2. Suite identifier

Every stored blob carries a `suiteId` string. v1 is the single token **`v1`**, which fixes:

| Role | Algorithm |
|---|---|
| **AEAD** | AES-256-GCM (96-bit nonce, 128-bit tag) — RFC 5116 |
| **KDF** | HKDF-SHA-256 — RFC 5869 |
| **Hybrid KEM** | veyq `KEM-v1` = **X25519 + ML-KEM-768** with an HKDF-SHA-256 combiner (§4) |
| **Password KDF** | Argon2id — RFC 9106 |
| **Signatures** | Ed25519 — RFC 8032 |
| **Hash** | SHA-256 |

The server stores `suiteId` + opaque bytes and **never interprets** them; it only validates the id is
known (the `CryptoSuiteRegistry`) and the byte lengths are in range. Crypto-agility = future blobs write
`v2`; old `v1` blobs keep working unchanged (§9).

> **Why an HKDF combiner and not X-Wing?** X-Wing (draft-connolly-cfrg-xwing-kem) is the obvious
> standardized X25519+ML-KEM-768 KEM, but its combiner uses **SHA3-256**, which Apple **CryptoKit does
> not provide** — forcing a third-party SHA3 on one of three stacks (a conformance risk). v1 therefore
> uses an **HKDF-SHA-256** generic KEM combiner (the TLS-hybrid / Signal-PQXDH family), whose primitives
> are native in all three stacks. **Decided for Milestone 1** on our quality priorities — HKDF wins on
> reliability/3-stack-conformance, maintainability and portability while security is a wash (X-Wing's
> standardized provenance is offset by a new unaudited SHA3 dependency on Apple). v2 may adopt X-Wing
> once SHA3 is uniform.

## 3. Primitive parameters (fixed in v1)

- **AES-256-GCM:** 32-byte key, **12-byte random nonce**, 16-byte tag appended to ciphertext. AAD as
  specified per use below.
- **HKDF-SHA-256:** standard Extract-then-Expand; `salt` and `info` as specified per use.
- **ML-KEM-768:** FIPS 203. Public key 1184 B, ciphertext 1088 B, shared secret 32 B.
- **X25519:** RFC 7748. Public key 32 B, shared secret 32 B.
- **Ed25519:** RFC 8032. Public key 32 B, signature 64 B.
- **Argon2id — used in two independent roles:** the **client-side unlock KEK** (password **+ secret
  key**, §6) and the **server-side auth verifier** (password-only, §7). Same algorithm, **different salts
  and inputs, never mixed**. Client unlock params: **m = 64 MiB, t = 3, p = 1**, 32-byte output, 16-byte
  random salt — the **secret key supplies the real entropy**, so moderate memory keeps mobile/WASM
  snappy; the recovery-code KEK uses the same. The server auth verifier is tuned **independently**
  server-side. Argon2 params travel inside each blob, so the reader takes them from there.

## 4. Hybrid KEM (`KEM-v1`)

An asymmetric KEM over the recipient's hybrid public key `pk = (pk_x25519, pk_mlkem)`.

**Keygen** → `sk = (sk_x25519, sk_mlkem)`, `pk = (pk_x25519, pk_mlkem)`.

**Encap(pk)** →
```
(epk_x25519, esk_x25519)         = X25519.keygen()
ss_x25519                        = X25519(esk_x25519, pk_x25519)
(ct_mlkem, ss_mlkem)             = MLKEM768.encaps(pk_mlkem)
ikm   = lp(ss_mlkem) ‖ lp(ss_x25519)
salt  = SHA-256( lp(ct_mlkem) ‖ lp(epk_x25519) ‖ lp(pk_mlkem) ‖ lp(pk_x25519) )   # transcript binding
ss    = HKDF-SHA-256(ikm, salt, info="veyq/v1/kem")            # 32-byte shared secret
ct    = epk_x25519 ‖ ct_mlkem                                  # 32 + 1088 = 1120 B, fixed
return (ct, ss)
```

**Decap(sk, ct)** → split `ct` into `epk_x25519` (first 32 B) ‖ `ct_mlkem` (remaining 1088 B), recompute
`ss_x25519 = X25519(sk_x25519, epk_x25519)`, `ss_mlkem = MLKEM768.decaps(sk_mlkem, ct_mlkem)`, then the
same `ikm/salt/info` → `ss`.

Binding both ciphertext and ephemeral key into the `salt` follows the generic-combiner pattern; ML-KEM's
shared secret is already ciphertext-bound by its FO transform, and X25519's contribution is bound here.
Security holds if **either** component is unbroken (harvest-now-decrypt-later hedge, [AD-25](../decisions/README.md)).

## 5. Key wrapping (`wrap`)

Two flavours; both produce a self-describing blob.

### 5a. Asymmetric wrap — to a hybrid public key (HPKE-style, single-recipient)
Used to wrap a scope key to a member's identity public key, and the temp-wrap on onboarding.
```
(ct, ss) = KEM-v1.Encap(pk_recipient)
kek      = HKDF-SHA-256(ikm=ss, salt="", info = "veyq/v1/wrap" ‖ context)
nonce    = random(12)
aead     = AES-256-GCM.Seal(kek, nonce, plaintext = targetKey, aad = "v1" ‖ context)
blob     = struct{ u8 type=0x01; ct[1120]; nonce[12]; aead }       # aead = ct||tag
```
`context` is an ASCII label binding the wrap's purpose (e.g. `"scope-grant"`), included in both `info`
and `aad`. Multi-recipient = **N independent single-recipient wraps** of the same `targetKey`.

### 5b. Symmetric wrap — under a KEK derived from an unlock method
Used to wrap the **master key** per unlock method, and to wrap the identity private key under the master
key.
```
nonce = random(12)
aead  = AES-256-GCM.Seal(kek, nonce, plaintext = targetKey, aad = "v1" ‖ context)
blob  = struct{ u8 type=0x02; nonce[12]; aead }
```

## 6. Identity, master key & the unlock wraps

### Identity key pair
`identityPublic = (pk_x25519, pk_mlkem, pk_ed25519)`. `identityPrivate = (sk_x25519, sk_mlkem, sk_ed25519)`.
The KEM half (`pk_x25519, pk_mlkem`) is what others **wrap to** (the "mailbox slot"); the Ed25519 half
**signs** (key-change, device, derivative signatures). The **public** bundle is published to the key
directory; the **private** bundle is wrapped under the master key (§5b, `context="identity-priv"`).

### Master key
A random **32-byte** symmetric key. It wraps the identity private bundle, and is itself wrapped once **per
unlock method** (the `KeyBlob` rows). One master key, N wrapped copies — adding/removing a method changes
one blob, never the content ([model](account-key-model.md)).

### Per-method KEK derivation (each feeds §5b to wrap the master key)

| Method | KEK |
|---|---|
| **password + secret key** | `Argon2id( lp(secretKey) ‖ lp(NFC(password)), salt, m=64MiB,t=3,p=1 )` → 32 B |
| **passkey (PRF)** | `HKDF-SHA-256( ikm = prfOutput, salt="", info="veyq/v1/passkey-kek" )` → 32 B |
| **recovery code** | `Argon2id( recoveryCode, salt, m=64MiB,t=3,p=1 )` → 32 B |
| **device** | a random 32-byte **device key** held non-extractably in the OS keystore (WebCrypto non-extractable AES-GCM key / Secure Enclave-gated Keychain item / Android Keystore-StrongBox); used directly as the KEK |

Notes: the **2SKD** input length-prefixes the secret key and password to avoid concatenation ambiguity
(and stays portable — no Argon2 `secret` parameter needed). The **PRF output** is the 32 bytes returned
by the WebAuthn/Credential-Manager/ASAuthorization PRF extension over a per-account PRF salt stored in the
blob. The **recovery code** is a **~128-bit** value rendered as grouped base32 for the Emergency Kit — kept
short enough to type, so it is **Argon2id-hardened** rather than fed to HKDF.

### Stored blob fields per method (`KeyBlob`)
`{ suiteId, unlockMethod, wrappedKey = §5b blob, kdfParams, credentialRef }` where `kdfParams` carries the
Argon2 `salt`+params (password/recovery) or the PRF salt (passkey), and `credentialRef` is the WebAuthn
credential id (passkey) or device id (device). All opaque to the server.

## 7. Authentication vs. unlock (boundary)

This spec governs **unlock** (client-side key crypto). **Auth** (proving identity to the server for a
session) is separate ([Security Model → key recovery / Secret Key](security-model.md)):
- **password auth:** the server stores `Argon2id(NFC(password), authSalt)` (a *different* salt than the
  unlock KEK, and **without** the secret key — the server must verify from the password alone) and checks
  it on login. The stored verifier is a PHC string
  (`$argon2id$v=19$m=32768,t=3,p=1$<salt>$<proof>`) on the user row, not an encrypted blob. The secret key
  never reaches the server, so this verifier does not yield the master key.
- **passkey auth:** the stored WebAuthn credential public key verifies assertions.

The auth verifier and the unlock KEK are **independent derivations** from the password; holding one never
yields the other.

## 8. What the server stores (all opaque)

Per user, in the blind key store: the **identity public bundle** (directory), the **wrapped identity
private bundle**, **N `KeyBlob`s** (master-key wraps), and the **auth verifier**. Never the master key,
a KEK, the secret key, the recovery code, or any private key. Store-bytes === posted-bytes is asserted by
the backend echo test.

## 9. Crypto-agility

`suiteId` is per blob. Rolling forward = clients write `v2` blobs (e.g. swapping the combiner for X-Wing,
or AES-GCM for XChaCha20-Poly1305); `v1` blobs remain readable via their stored id. The server's only
suite-awareness: advertise supported suites (in `GET /api/setup` and capability discovery) and reject
**writes** with an unknown id (`400 UNKNOWN_SUITE`). It never re-wraps — re-wrapping is a client operation
that posts fresh blobs. A user may simultaneously hold a `v1` password wrap and a `v2` passkey wrap.

## 10. Conformance & test vectors

The shared vectors live in `docs/api/crypto-vectors/v1.json`, generated by the Node reference
implementation in `tools/crypto-ref/` (`npm run generate`) — not hand-authored:
- **Borrowed KATs** — ML-KEM-768 (FIPS 203), X25519 (RFC 7748), Ed25519 (RFC 8032), Argon2id (RFC 9106),
  HKDF (RFC 5869), AES-GCM — assert each primitive matches the standard in each stack.
- **veyq vectors** — fixed-randomness `KEM-v1` encap/decap, each per-method KEK, a master-key §5b wrap,
  and an asymmetric §5a wrap. Each stack must reproduce the bytes and round-trip (wrap-here / open-there).

The conformance harness runs these in TS, Swift and Kotlin, plus a backend **store-and-echo** check, and
**gates** all client crypto work — it runs before any UI is built.

## Related
- [Account keys, devices & recovery](account-key-model.md) (the friendly model) · [Security Model](security-model.md) (threat model, key hierarchy)
- Decisions: [AD-02](../decisions/README.md) (E2EE), [AD-25](../decisions/README.md) (crypto primitives & PQC), [AD-23](../decisions/README.md) (capability/agility)
