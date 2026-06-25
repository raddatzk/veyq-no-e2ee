# Feature: Sharing

Public sharing of Memory subtrees without becoming a social network — **view** shares and
**uploader** links, with granular roles (Viewer, Contributor, Manager, Uploader).

- **Touched contexts:** Sharing, Media, Processing — see [System Architecture](../../overview/architecture.md).
- **Cross-cutting:** [Security Model](../../crosscutting/security-model.md) (share keys, token-in-fragment,
  share-boundary Trust & Safety), [Agent Mode](../../crosscutting/agent-mode.md) (processing shared media).
- **Decisions:** [AD-03](../../decisions/README.md) (per-subtree keys), [AD-05](../../decisions/README.md) (share-boundary T&S).

## Use-cases
- ★ [Collect uploads via an uploader link](use-cases/uc.sharing.collect-uploads.md)
- ★ [Publish a read-only public share](use-cases/uc.sharing.share-public-viewer.md)
- ★ [Contribute to a shared Memory](use-cases/uc.sharing.collaborate.md) — contributor collaboration
- ★ [Password-protect a share](use-cases/uc.sharing.password-protect.md)
