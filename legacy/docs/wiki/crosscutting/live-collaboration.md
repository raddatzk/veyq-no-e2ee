# Live Collaboration (Journal)

> How real-time, multi-user journal editing works under a blind server.

The journal is Markdown; its **content is the cheapest E2EE case** (just text). Real-time co-editing
uses **Yjs (a CRDT)** over WebSockets, with the CRDT living **entirely on the client** and the server
acting as a **thin, blind relay**.

## A thin, blind relay (not a content-aware Yjs server)

A content-aware Yjs server (e.g. **Hocuspocus**) holds and **merges the document server-side** — it
must *read* the updates, hold the plaintext `Y.Doc` in memory and persist it. That is fundamentally
incompatible with a blind server, so veyq does **not** use one for E2E. Instead the server is a
**thin, blind relay**:

- Clients **encrypt their Yjs updates**; the relay only **stores and broadcasts ciphertext**.
- The **CRDT merge happens client-side** (that is what CRDTs are built for — they don't need a smart
  server).
- **Markdown validation / sanitization moves to the trusted client** (the server cannot validate text
  it cannot read).
- **Presence / cursor metadata** is either encrypted or accepted as a small, documented leak
  ("who is editing when").
- No server-side document compaction / GC.

Session keying uses the scope/subtree content key (see [Security Model](security-model.md)).

## Persistence & snapshotting

Durability and compaction are **separate** concerns:

- **Update log — the source of durability (server, blind).** Every edit streams to the relay as an
  **encrypted Yjs update** and is durable the moment the server acks it: append-only, no client
  coordination. The unacked tail (last keystrokes) is protected by a small debounce, flush-on-unload
  (`sendBeacon` / `visibilitychange`) and local Yjs persistence that re-syncs on reconnect. **Closing
  the app loses nothing already relayed.**
- **Snapshot — compaction + fast read (client/agent).** The materialized, re-encrypted document state
  at a log offset `N`. Only a key-holding participant can produce it (the server is blind). It is
  **not** required for durability — it keeps reads cheap (a fresh client or share viewer would
  otherwise have to replay the whole log).

**Who snapshots — round-robin by connect time, gated by update count.** Each connected client
snapshots on its own clock (every `X` seconds, phased by its connect timestamp, so clients take turns)
**but only if at least `Y` updates have accumulated since the last snapshot**. Most ticks are no-ops;
a snapshot happens only when compaction is worthwhile, and the staggering avoids everyone snapshotting
at once.

**Acceptance & pruning (server, on metadata only):**
- A snapshot is tagged with the offset `N` it covers; the server accepts it only if `N ≥` the current
  snapshot's offset — **monotonic**, so simultaneous snapshots are harmless (the higher offset wins).
- After `snapshot(N)` is durably committed, the server prunes updates `≤ N`. The **previous snapshot is
  kept until the new one is committed** (atomic swap — never leave a connecting client without a
  snapshot).

**Connecting:** a client receives the **latest snapshot + all updates after it**, merges → current
state → subscribes to live updates → becomes a snapshot candidate. The cycle repeats.

## Transport & storage

The CRDT stays in **Yjs** (client-side): we keep Yjs for merge, awareness and the CodeMirror binding —
**not** a home-grown CRDT. We build only the **thin relay + a small Yjs provider** (the transport
adapter).

**The relay lives in the Quarkus backend** (a WebSocket endpoint), not a separate sidecar — this
removes the legacy Hocuspocus Node sidecar (see [Risks D5](../overview/risks.md)) and reuses the
existing scope/share authorization. The relay enforces **access** (who may join/push a room), never content.

**Real-time path ≠ durability path.** Broadcast to subscribers is **in-memory** WebSocket fan-out and
is *not* on the database path: the relay broadcasts first and **persists asynchronously**.

**Storage:**
- **Op-log → MariaDB**, table `(room, offset, ciphertext, author, ts)`. To avoid a write per keystroke
  the relay **batches** incoming deltas (~1–2 s or N updates, merged via `Y.mergeUpdates`) into **one
  segment row** — two-level batching on top of the per-client debounce, so only ~1 write/s per active
  room. Durability may be **relaxed** (group-commit / no per-delta fsync): the op-log is multiply backed
  by client-local persistence and the snapshot, so a lost ~1–2 s batch self-heals on reconnect. The log
  stays **short** because snapshots prune it.
- **Snapshot → encrypted blob store** (a snapshot is just another encrypted blob), with a pointer row in
  MariaDB (current snapshot blob + offset; previous kept until the swap commits).

**Awareness (cursors/presence):** broadcast as an **encrypted** payload (position, name, color) so the
relay never learns *where* a cursor is. Stale presence is cleaned up two ways: Yjs awareness **heartbeat
+ timeout** (~30 s) drops ghosts even on a hard crash, and the relay's **WS ping/pong** liveness
broadcasts a `leave` the moment a socket dies (a clean close sends `leave` via `beforeunload`).

**Scaling (SaaS, deferred):** at many simultaneously-hot rooms, move the hot op-log + cross-instance
fan-out to **Redis Streams + pub/sub**; MariaDB stays the durable store. Extract only when a quality
scenario forces it.

## Concurrent edits & undo

Merges are **convergent** but follow CRDT semantics, which can surprise:

- **Edit vs. delete the same region:** if A types inside a paragraph while B deletes the whole
  paragraph, B removes the characters it knew; A's freshly-typed characters have new IDs B never saw, so
  they **survive as an orphan fragment**. All clients converge to the same result and nothing A typed is
  silently lost, but the leftover is semantically odd. This is inherent to collaborative text (CRDT and
  OT alike) — not a relay/E2E issue. Mitigate with **visible cursors** (social avoidance) and undo.
- **Undo is local and per-user:** Yjs `UndoManager` (with `trackedOrigins`) undoes **only your own**
  changes, even if others edited in between. Undo produces a **new compensating delta** that flows
  through the relay like any edit — the blind relay needs no special support, and it works regardless of
  pruning/snapshots (the inverse ops live in client memory). Undo steps are grouped (~500 ms) and the
  stack is session-local.

## Clients (web & native)

The relay protocol and the Yjs wire format are **client-agnostic**, so all clients share the same room
and interoperate — only the editor host differs:

- **Web:** CodeMirror + **Yjs** + `y-codemirror.next` (with awareness/cursors).
- **Native (iOS; later Android):** a **native editor bound to `yrs`** — the Rust port of Yjs, which is
  **wire-compatible** with Yjs (identical update bytes) — via a Swift FFI wrapper, with native crypto
  (CryptoKit) and a native WebSocket to the relay.

**No WebView crutch.** We do **not** ship a stopgap WKWebView that hosts the web editor; the native
client is built **properly from the start** (the CRDT is `yrs`, never re-implemented by hand). Only the
journal editor needs `yrs`; the rest of the app stays native. Keys remain app-controlled (Keychain).

## Solo and fallback

A single-user journal needs no live session: it works today via the **REST save fallback** (encrypted
blob, save). Live collaboration is only relevant in **shared** scopes — where E2E is a conscious
trade-off anyway.

> Decisions: AD-17 (live collaboration), AD-18 (persistence/snapshotting), AD-19 (transport & storage),
> AD-20 (native clients via `yrs`) — see [decisions](../decisions/README.md). **Open:** trigger
> parameters (`X` interval, `Y` threshold) and round-robin vs. a presence-based deterministic leader.
