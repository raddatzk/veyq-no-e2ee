# Feature: Uploads

Getting media into a Memory — encrypted upload, resumable sessions, and deferred heavy processing.

- **Touched contexts:** Media, Processing — see [System Architecture](../../overview/architecture.md).
- **Cross-cutting:** [Agent Mode](../../crosscutting/agent-mode.md) (the processing pipeline),
  [Security Model](../../crosscutting/security-model.md) (encryption, dedup).
- **Decisions:** [AD-11](../../decisions/README.md) (Agent Mode), [AD-09](../../decisions/README.md) (dedup).

## Use-cases
- ☐ `uc.uploads.add-media` — upload media into a Memory (encrypt + light derivatives client-side)
- ☐ `uc.uploads.resumable-upload` — resume an interrupted upload
