# Feature: Mobile

Native iOS experience — deep links, resumable upload, and acting as a strong on-device processing
worker. Android is planned.

- **Touched contexts:** clients (native), Media, Processing — see [System Architecture](../../overview/architecture.md).
- **Cross-cutting:** [Agent Mode](../../crosscutting/agent-mode.md) (the device as a worker),
  [Platform Concepts → Capability discovery](../../crosscutting/platform-concepts.md),
  [Live Collaboration → Clients](../../crosscutting/live-collaboration.md) (native editor via `yrs`, **no WebView**).
- **Decisions:** [AD-11](../../decisions/README.md) (Agent Mode), [AD-13/14](../../decisions/README.md) (search/ML on-device).

## Use-cases
- ☐ `uc.mobile.open-in-app` — open a link in the native app
- ☐ `uc.mobile.resumable-upload` — resumable upload from mobile
- ☐ `uc.mobile.run-agent` — run on-device processing as a worker
