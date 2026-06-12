# Glossary

> Shared, unambiguous terms. Product language uses Memory/Chapter; *Album* and *Explorer* are retired.

| Term | Definition |
|---|---|
| **Memory** | The core product object: a curated container of media, journal, maps, tracks, playlists and chapters. |
| **Chapter** (de: *Kapitel*) | A nested child Memory. API contracts may use `childMemory` for the technical tree relation. |
| **Bucket** | A per-uploader container within a Memory where that uploader's media land. Every uploader has a **default bucket**; in an uploader/contributor share the uploader first enters a **name** for theirs. Buckets may hold **sub-buckets** for finer organization. An attribution/organization axis — distinct from **chapters** (curation sub-Memories). |
| **Folder** (curation folder) | An organizational container in the curation tree that nests folders and Memories for navigation — **not** a content object (no media, dates or cover). Folders may not live *inside* a Memory; Memories may live anywhere. A separate plain file-storage model ("Drive") is planned — see [design notes](../design-notes.md). |
| **Slug / permalink token** | A node's URL slug is `slugify(name)`; per-parent uniqueness is enforced via a **blinded token** `HMAC(scopeKey, parentId ‖ slug)`, so the server never learns the name. See [Security Model](../crosscutting/security-model.md). |
| **Scope** | The visibility domain of content: **Tenant**, **Group** or **Personal**. |
| **Tenant** | An isolated workspace on a veyq server. Users are global and may belong to several tenants. |
| **Group** | A collaborative space within a tenant; all members share the same content rights; no nesting. |
| **Personal space** | A user's private scope; **E2E by default**, confidential even from the operator. |
| **Share** | A grant of access to a Memory subtree: a **public view share** or an **uploader link**. Roles: Viewer, Contributor, Manager, Uploader. |
| **Shared vs. unshared** | The central axis for risk and dedup: content is *shared* once its keys are wrapped to any other party, regardless of scope folder. |
| **Media / blob** | A user-facing asset (Media) referencing exactly one stored byte object (blob). Under E2EE the blob is ciphertext. |
| **Derivative** | A generated artifact from a blob: thumbnail, preview, transcode, or ML embeddings (face/semantic index). Encrypted; produced by a worker. *(The text search index is client-local, not a derivative.)* |
| **Content key** | A per-subtree symmetric key that encrypts content; wrapped to member public keys and to share keys. |
| **Recovery code** | A high-entropy, system-generated secret that **independently unwraps** a user's private key (the forgot-password path). Single-use — redeeming it invalidates it and generates a new one. |
| **Secret Key (2SKD)** | A high-entropy client-held **second factor** combined with the password (two-secret key derivation) to defeat post-breach brute-force — the "something you have". **Not** a recovery code. |
| **Key Transparency** | An append-only, verifiable public-key log preventing undetectable key-substitution by the server. |
| **Convergent encryption** | Encryption with a content-derived key so identical plaintext yields identical ciphertext (enables dedup); used with a per-scope secret. |
| **Agent / Worker** | An authorized, full-trust device that processes encrypted content: a phone, browser, or a deployed/installed agent. |
| **Agent Mode** | The distributed, async, blind-server job queue that is veyq's only processing path under E2EE. |
| **Operator** | Whoever runs the veyq instance — a self-hoster (possibly hosting for others) or the SaaS provider. |
| **Depicted person** | A person appearing in content who is not necessarily a user; a first-class privacy subject. |
| **Capability discovery** | The server advertising available features so clients/workers adapt. |
