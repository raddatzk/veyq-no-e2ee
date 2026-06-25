# ADR 0020: Memory and Folder Mutation Semantics

## Status

Accepted

## Context

Memories are the primary rich content objects in veyq. They can contain media, journal text, context, shares and child Memories. Folders are organizational containers used for navigation and grouping, but they are not rich content objects.

Delete and move operations therefore carry different product risks:

- Deleting a Memory can remove curated content, child Memories, media and share-visible material.
- Deleting a folder can affect a whole organizational subtree even though the folder itself is not the product object.
- Moving a Memory or folder changes canonical paths and can affect navigation, links, sibling name collisions and scope boundaries.
- Cross-scope moves can affect privacy and share expectations and must not happen implicitly.

The current backend behavior deletes non-empty folders recursively. That is the intended data behavior for folder deletion, but clients must make the destructive effect explicit before invoking it.

## Decision

Memory and folder mutations must preserve the distinction between rich Memory content and organizational folders.

Folder deletion:

- Empty folders may be deleted after a normal confirmation.
- Non-empty folders are deleted recursively after explicit user confirmation.
- A confirmed folder deletion removes descendant folders, top-level Memories inside those folders, descendant Memories below those Memories and media owned by the deleted Memories.
- Clients must present folder deletion as a destructive recursive action before calling the API.
- Moving contents to the parent folder or another destination is a separate operation, not an implicit side effect of deletion.

Folder movement:

- Folders may be moved only within the same scope.
- Moving a folder into itself or into one of its descendants is invalid.
- The target location must reject sibling name collisions with both folders and top-level Memories.
- The move response includes the new canonical folder path.
- No server-side redirect, path-history or mandatory client redirect behavior is part of this decision. Clients may refresh the current view or navigate explicitly according to the local workflow.

Memory deletion:

- A Memory without child Memories may be deleted after confirmation.
- A Memory with child Memories is deleted recursively after explicit user confirmation.
- A confirmed Memory deletion removes descendant Memories and media owned by the deleted Memories.
- Child Memories must not be automatically lifted to the deleted Memory's parent, because that changes curated structure and can obscure share and permission impact.

Memory movement:

- Memory moves should use an explicit move API instead of treating metadata update as the full movement contract.
- Supported targets should be:
  - another Memory in the same scope,
  - a folder in the same scope,
  - the root of the same tenant, group or personal scope.
- Exactly one target kind may be supplied per move request.
- Cross-scope Memory moves are deferred. They require a separate decision because they can affect privacy, public shares, participants and inherited access expectations.
- Moves must reject cycles and sibling name collisions.
- Moving a Memory under another Memory clears folder placement; moving a Memory to a folder or scope root clears parent-Memory placement.

## Consequences

The existing recursive folder delete implementation matches the intended backend behavior, but frontend clients must add confirmation before exposing it as a standard UI action.

The Memory API should grow an explicit move endpoint or equivalent explicit contract. `UpdateMemoryRequest.parentId` can remain for metadata-compatible parent changes during transition, but it should not be the only documented movement model.

Clients should refresh local navigation state after successful moves. Redirects are a UI choice, not an API contract.

Feature documentation and acceptance coverage for delete and move flows must include positive and negative cases for recursive deletion, cycle prevention, scope mismatch and name collisions.
