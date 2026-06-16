# Legacy: Hocuspocus collaboration sidecar

**Status: retired, not deployed.** This was the original live-journal collaboration server
(Hocuspocus / y-websocket). It held journal documents **in plaintext** server-side, so it was *not*
operator-blind.

It has been replaced by the operator-blind relay built into the backend at `/ws/journal/{scopeId}/{nodeId}`
(`backend/.../features/memory/journallive/JournalRelay.kt`): a dumb, ciphertext-only message relay where
the CRDT lives peer-to-peer and every frame is AEAD-sealed under the scope content key. All clients
(web/iOS/Android) point at that relay; nothing references this sidecar anymore.

Parked here for reference. Its Compose service, Traefik routing, CI build/publish jobs, and the
`JOURNAL_LIVE_*` env vars were removed from the active stack. Do **not** redeploy without first
re-checking the operator-blind requirement.
