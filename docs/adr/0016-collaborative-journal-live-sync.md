# ADR 0016: Collaborative Journal Live Sync

## Status

Accepted

## Context

Memory journals are plain Markdown documents with veyq-specific `::` embed tokens. The first implementation saved the full Markdown body through REST. The next Journal editor needs real-time collaboration without turning journals into a block CMS or making CRDT state the public API.

## Decision

Markdown remains the canonical persisted journal format in `veyq_memory_journal`. Live editing uses a Hocuspocus/Yjs sidecar as a synchronization transport. Opening a journal editor creates a short-lived, Memory-bound live-session token through veyq. The token binds the tenant or public share, Memory id, document id, display name, presence color, edit permission and expiry.

The Hocuspocus sidecar validates tokens through veyq, loads the current Markdown body, synchronizes Yjs text among connected clients, broadcasts presence and writes debounced Markdown snapshots back to veyq. The browser token is required to open a live document; the sidecar uses a separate internal shared secret for backend validation and persistence calls so an already authenticated document can continue saving during a long editing session without exposing a long-lived browser token. veyq remains the authorization and validation boundary: saved snapshots must pass the same `::` embed validation as REST updates.

Internal Memory editors and public Manager-share editors can use live sync. iOS remains read-only for this change and continues to consume the Markdown journal API.

## Consequences

- The deployment gains an optional `veyq-collaboration` sidecar and WebSocket route.
- Operators enabling the sidecar must configure both the browser token signing secret and the sidecar-to-backend secret.
- Existing REST journal read/update endpoints remain valid fallback APIs.
- Version history, durable offline conflict resolution and non-web writing clients are explicitly out of scope for this decision.
- Public APIs and search continue to expose Markdown, not Yjs document state.
