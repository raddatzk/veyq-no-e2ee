# Space Navigation

Unified navigation resolves where the user currently is and what can be listed there, independent of
whether the active scope is personal, tenant-owned or a group.

This is not a separate user-facing use-case. It is the shared contract used by the Spaces use-cases
for scope selection and by the Memory use-cases for folders, Memories and chapters.

## Scope context

Every navigation request runs in an explicit **scope context**:

- `PERSONAL` — the user's private personal space, encrypted with self-owned personal-space keys.
- `TENANT` — the active tenant root scope.
- `GROUP` — a collaborative group scope whose key is wrapped to its members.

The client may show a friendly switcher, but the server-side contract must stay privacy-preserving:
the active context identifies the scope and operational membership state, not decrypted names, paths
or content metadata.

Holding an authenticated session is not sufficient for navigation. The client must also hold or unlock
the relevant scope keys before it can decrypt node names, titles, covers or private metadata.

## Navigable nodes

The navigation tree uses the Memory feature's node model:

- **Scope root** — virtual root of the active personal, tenant or group scope.
- **Folder** — organizational node; may contain folders and Memories.
- **Memory** — content unit; may live at scope root or inside a folder.
- **Chapter** — nested Memory inside a Memory; may contain further chapters.

Folders never live inside a Memory. Memories and chapters share the same content-unit semantics, but
their containment role differs.

## Unified listing contract

The API shape should be scope-agnostic. Clients should not need separate "personal tree", "tenant tree"
and "group tree" APIs to render the same navigation surface.

Example responsibilities:

- Resolve the active navigation context from the selected personal, tenant or group scope.
- List root children for that context.
- List children for a folder.
- List chapters for a Memory using the same node response shape.
- Return enough key-envelope references for the client to decide whether the node can be decrypted.

Illustrative endpoints, not a final OpenAPI contract:

```text
GET /api/navigation/context?scopeId=...
GET /api/navigation/children?scopeId=...&parentId=...
GET /api/navigation/children?scopeId=...&memoryId=...
```

The important point is the invariant: one navigation model, one response shape, multiple scope kinds.

## Server-visible vs. client-decrypted data

The server may see low-sensitivity operational metadata required to maintain the tree:

- scope id and scope kind;
- node id, parent id and node kind;
- ordering indices and timestamps;
- blinded slug tokens;
- ciphertext/envelope references;
- membership or grant existence needed to decide whether the scope is visible.

The client decrypts user-facing metadata:

- space display names where confidential;
- folder names;
- Memory and chapter titles;
- cover metadata;
- captions, descriptions and private content metadata;
- canonical paths assembled from decrypted names.

The operator must not learn personal or private group/tenant names and paths from navigation.

## Invariants

- No cross-scope move. Moving across scopes is a copy plus re-encrypt operation, not a tree move.
- No cycles.
- No folder inside a Memory.
- Slug uniqueness is enforced per parent across folders, Memories and chapters with blinded slug
  tokens.
- A personal scope has no other key-holder unless the owner explicitly shares content.
- The same navigation rules apply to personal, tenant and group scopes.

## Implementation path

A useful vertical slice is:

1. Personal, tenant and group scopes resolve to the same navigation context model.
2. The client can list root children for the active scope.
3. The client can create and list folders and Memories in that scope.
4. Chapters use the same listing response shape under a Memory.
5. Sharing or copying across scopes adds explicit re-encryption flows rather than weakening the
   navigation contract.

## Related

- Features: [Spaces](../features/spaces/_index.md), [Memory](../features/memory/_index.md)
- Concepts: [Security Model](security-model.md) (scope keys, shared vs. unshared, blinded slug tokens)
- Use-cases: [Switch the active space](../features/spaces/use-cases/uc.spaces.switch-active-space.md),
  [Use the personal space](../features/spaces/use-cases/uc.spaces.use-personal.md),
  [Use a group space](../features/spaces/use-cases/uc.spaces.use-group.md),
  [Create a Memory tree](../features/memory/use-cases/uc.memory.create-tree.md),
  [Browse a Memory](../features/memory/use-cases/uc.memory.browse.md)
