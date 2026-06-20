# UC: Publish a read-only public share

- **ID:** uc.sharing.share-public-viewer
- **Primary actor:** Memory Curator
- **Secondary actor:** Public Viewer
- **Goal:** Publish a Memory subtree as a read-only link that anyone can open without an account, while
  the operator stays blind to the content.
- **Preconditions:** The curator owns the Memory and holds its content key; sharing is permitted in the
  space.
- **Postconditions (success):** A **VIEWER** share exists; the link's URL fragment carries the share
  key; a viewer can decrypt and browse the subtree client-side; the operator never sees the content key
  or plaintext, nor which media a viewer opens.

## Sealed-link mechanics
- The client wraps the subtree's **content key** under a random 32-byte **share key** and stores only
  the **wrapped** form server-side.
- The link is `/s/{shareId}#k={base64url(shareKey)}` — the share key lives in the **fragment** and is
  never sent to the server.
- A viewer fetches the public share, unwraps the content key with the fragment key, then decrypts nodes,
  media metadata and blob frames **locally**.

## Main flow
1. The curator creates a share with role **VIEWER** (`POST …/nodes/{nodeId}/shares`, sending the wrapped
   content key, optional label / notice / expiry).
2. The server stores the share and returns an **unguessable** `shareId`.
3. The client builds the link with the share key in the fragment; the curator distributes it.
4. A Public Viewer opens the link and calls `GET /api/public/shares/{shareId}` (no auth) → role +
   wrapped key + root node.
5. The viewer **unwraps** the content key from the fragment and reads the subtree (`…/nodes`, `…/media`),
   fetching blob frames **directly from the object store** via subtree-gated presigned GET URLs
   (`…/blob-downloads-batch`, ADR 0021) and decrypting everything client-side.

## Alternate / exception flows
- A1: The curator sets an **expiry** → after it passes, guest requests return `404`.
- A2: The curator **revokes** the share → the record is deleted; the next guest request returns `404`.
- A3: The curator edits label / notice / expiry (`PATCH`) → role and key stay **immutable**.
- E1: A **missing / garbled** fragment key → the client cannot unwrap; the share won't open.
- E2: A guest requests a node **outside the shared subtree** → `404` (subtree-gated server-side).

## Quality notes
- *Confidentiality (paramount):* the operator sees an opaque `shareId`, the wrapped key, the plaintext
  **role** and ciphertext payloads — never the content key, names, media or viewer identity.
- *Least exposure:* a viewer gains only the **shared subtree's** key (per-subtree keys), never the wider
  space.
- *Unguessability:* `shareId`s are 32 random bytes; access needs **both** the id and the fragment key.

## Related
- Feature: [Sharing](../_index.md) · Actors: [Memory Curator](../../../actors.md),
  [Public Viewer](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (share keys, token-in-fragment,
  per-subtree keys)
- Decisions: [AD-03](../../../decisions/README.md) (per-subtree keys); ADR 0011 (persisted shares)
- Related use-cases: `uc.sharing.collaborate`, `uc.sharing.password-protect`, `uc.sharing.collect-uploads`
