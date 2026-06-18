# Feature: Agent Mode

On-device, end-to-end-encrypted **processing**. Because the server can **never** read content, all heavy
media work (video transcode, ML face / semantic embeddings) runs on **clients and authorized agents** —
the only processing path there is, core infrastructure rather than an optimization.

- **Touched contexts:** Processing, Media, People (participation), native/web clients — see
  [System Architecture](../../overview/architecture.md).
- **Cross-cutting:** [Agent Mode — Processing Pipeline](../../crosscutting/agent-mode.md) (fast/slow
  path, blind queue, claim-lease lifecycle, scheduling by participation + capability, signed
  derivatives), [Security Model](../../crosscutting/security-model.md) (a worker is a full-trust,
  revocable device), [Platform Concepts](../../crosscutting/platform-concepts.md) (capability discovery).
- **Decisions:** [AD-11](../../decisions/README.md) (Agent Mode), [AD-12](../../decisions/README.md)
  (tiered liveness / eventual heavy derivatives), [AD-28](../../decisions/README.md) (segmented
  per-segment-encrypted transcode), [AD-14](../../decisions/README.md) (on-device ML, deferred).
- **Status:** **planned** — none of the use-cases below are built yet. The **fast-path** light
  derivatives (thumbnail / poster) produced synchronously on the uploading client live in
  [Uploads](../uploads/_index.md); the **queued worker pipeline** is not yet implemented.

## Use-cases
- `uc.agent.run-worker` — act as an authorized worker: advertise capabilities + status, **claim** a job
  (lease with timeout), decrypt, process, upload a **signed** derivative, ack; re-queue on lease expiry
- `uc.agent.transcode` — produce **segmented, per-segment-encrypted** video renditions (HLS/DASH-style)
  so clients stream and seek without the server seeing plaintext
- `uc.agent.recognize-faces` — on-device ML **face detection / clustering**, feeding
  [`uc.people.tag-faces`](../people/_index.md) (deferred)
- `uc.agent.deploy-agent` — install & authorize a **standalone deployed agent** (e.g. a Mac / home box)
  as a revocable, scopable device
