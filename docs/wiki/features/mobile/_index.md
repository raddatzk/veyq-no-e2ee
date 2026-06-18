# Feature: Mobile

Native iOS **and Android** experience — deep links and resumable upload today. Acting as an on-device
processing **worker** is its own feature — see [Agent Mode](../agent/_index.md).

- **Touched contexts:** clients (native), Media, Processing — see [System Architecture](../../overview/architecture.md).
- **Cross-cutting:** [Agent Mode](../../crosscutting/agent-mode.md) (the device as a worker),
  [Platform Concepts → Capability discovery](../../crosscutting/platform-concepts.md),
  [Live Collaboration → Clients](../../crosscutting/live-collaboration.md) (native editor via `yrs`, **no WebView**).
- **Decisions:** [AD-11](../../decisions/README.md) (Agent Mode), [AD-13/14](../../decisions/README.md) (search/ML on-device).

## Use-cases
- ★ [Open a link in the native app](use-cases/uc.mobile.open-in-app.md)
- ★ [Resumable upload from mobile](use-cases/uc.mobile.resumable-upload.md)
