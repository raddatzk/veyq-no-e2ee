# UC: Co-edit the journal live

- **ID:** uc.journal.live-coedit
- **Primary actor:** Memory Curator
- **Secondary actor:** Public Viewer
- **Goal:** Let several members edit the same journal at once and converge automatically, through a relay
  that never sees the text.
- **Preconditions:** Two or more members hold the space content key; each can mint a relay ticket; the
  node is a Memory or chapter.
- **Postconditions (success):** Concurrent edits merge without conflict via a CRDT; the relay forwards
  only ciphertext; the converged state is persisted as a sealed snapshot + Markdown projection.

## Architecture
- **CRDT:** Yjs (web) / Yrs (iOS/Android) — content-addressed ops merge without a central authority.
- **Relay:** an **operator-blind** WebSocket relay that authenticates the connection and rebroadcasts
  sealed frames verbatim to the room; it cannot decrypt.
- **Auth:** a short-lived, **single-use relay ticket** exchanged from the session token
  (`POST /api/journal-live/ticket`); read-only share guests join via a share id and may **receive but
  not send**.

## Main flow
1. Each editor mints a ticket and opens `…/ws/journal/{scopeId}/{nodeId}?ticket=…`; the relay consumes
   the ticket, checks scope membership and joins the room.
2. Clients exchange **CRDT sync** (state vector → missing updates); every frame is **sealed** with a
   fresh per-message sub-key before sending.
3. An editor's keystroke produces a CRDT update → sealed → relayed → the other clients decrypt and apply
   it; the UIs **converge**.
4. **Concurrent** edits (e.g. one prepends while another appends) merge deterministically with no
   conflict.
5. Periodically a client **compacts**: it writes a sealed snapshot + Markdown projection and the server
   prunes folded-in updates; later/offline sessions reconverge from snapshot + remaining updates.

## Alternate / exception flows
- A1: **Reconnect** after a drop → the client mints a **fresh ticket** and re-syncs from its state
  vector.
- A2: **Non-overlapping** sessions → a later session loads snapshot + updates, reconstructs the full
  text, then appends its own.
- E1: An **invalid / expired / replayed** ticket → the relay closes the connection without joining the
  room.
- E2: A client exceeds the **per-connection rate limit** (msgs/sec or bytes/sec) → the relay drops it.
- E3: A **read-only share guest** attempts to send → rejected; guests are receive-only.

## Quality notes
- *Confidentiality (paramount):* the relay and operator see only **sealed frames**, room ids and timing
  — never the text or presence detail.
- *Liveness vs. canonical:* the Markdown projection stays **canonical** for readers and is updated only
  at compaction, so reads never diverge from the live state.
- *Forward safety:* per-message sub-keys prevent nonce reuse across a long-lived content key.

## Related
- Feature: [Journal](../_index.md) · Actors: [Memory Curator](../../../actors.md),
  [Public Viewer](../../../actors.md) (read-only guest)
- Concepts: [Live Collaboration](../../../crosscutting/live-collaboration.md) (blind relay, CRDT,
  tickets), [Security Model](../../../crosscutting/security-model.md)
- Decisions: ADR 0016 (collaborative journal live sync)
- Related use-cases: `uc.journal.write`, `uc.journal.embed`, `uc.spaces.use-group`
