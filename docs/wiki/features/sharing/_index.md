# Feature: Sharing

Public sharing of Memory subtrees without becoming a social network — **view** shares and
**uploader** links, with granular roles (Viewer, Contributor, Manager, Uploader).

- **Touched contexts:** Sharing, Media, Processing — see [System Architecture](../../overview/architecture.md).
- **Cross-cutting:** [Security Model](../../crosscutting/security-model.md) (share keys, token-in-fragment,
  share-boundary Trust & Safety), [Agent Mode](../../crosscutting/agent-mode.md) (processing shared media).
- **Decisions:** [AD-03](../../decisions/README.md) (per-subtree keys), [AD-05](../../decisions/README.md) (share-boundary T&S).

## Use-cases
- ★ [Collect uploads via an uploader link](use-cases/uc.sharing.collect-uploads.md)
- ☐ `uc.sharing.share-public-viewer` — publish a read-only public share
- ☐ `uc.sharing.collaborate` — contributor / manager collaboration
- ☐ `uc.sharing.password-protect` — password-protect a share
