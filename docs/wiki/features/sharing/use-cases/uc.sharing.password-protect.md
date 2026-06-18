# UC: Password-protect a share

- **ID:** uc.sharing.password-protect
- **Primary actor:** Memory Curator
- **Secondary actor:** Public Viewer
- **Goal:** Add a passphrase gate to a share link so that holding the link alone is not enough — the
  recipient also needs an **out-of-band passphrase** to decrypt.
- **Preconditions:** The curator is creating (or holds) a share — viewer or contributor — and its
  content key.
- **Postconditions (success):** The share's content key is wrapped so that unwrapping needs **both** the
  fragment share key **and** the passphrase; the server stores only a salt; a guest with the wrong or
  empty passphrase cannot decrypt.

## Mechanics
- The wrap key is derived as `Argon2id(passphrase, salt)` **folded with** the random share key; the
  server stores only the 16-byte **salt**, never the passphrase.
- The fragment still carries the share key; the **passphrase travels out-of-band** (told to the
  recipient separately).

## Main flow
1. The curator **enables a passphrase** when creating the share; the client derives the wrap key from
   the share key + `Argon2id(passphrase, salt)` and wraps the content key under it.
2. The server stores the **wrapped key + salt**; the link carries only the share key in the fragment.
3. A guest opens the link → the client detects that a **passphrase is required** and prompts.
4. The guest enters the passphrase → the client re-derives the wrap key and **unwraps** the content key
   → the share opens.

## Alternate / exception flows
- A1: A guest opens a protected share with **no passphrase yet** → the client signals "password
  required" before any decryption.
- E1: A **wrong passphrase** → key derivation yields the wrong wrap key, the AEAD unwrap fails, and the
  client reports "wrong password" and lets the guest retry.
- E2: The curator **revokes / expires** the share → `404` regardless of passphrase.

## Quality notes
- *Confidentiality (paramount):* the operator never sees the passphrase or the content key — only an
  **Argon2id salt** and ciphertext; brute force is gated by the Argon2id cost.
- *Defense in depth:* protects against **link leakage** (forwarded URL, browser history) — the link
  without the passphrase is inert.
- *Usability:* the passphrase is shared on a **second channel**; the link alone reveals nothing.

## Related
- Feature: [Sharing](../_index.md) · Actors: [Memory Curator](../../../actors.md),
  [Public Viewer](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (share wrap key, Argon2id KEK)
- Decisions: [AD-03](../../../decisions/README.md) (per-subtree keys)
- Related use-cases: `uc.sharing.share-public-viewer`, `uc.sharing.collaborate`
