# ADR 0011: Persisted Shares and Person Resolution

## Status

Accepted

## Context

veyq tenants can represent separate domains and audiences on the same instance, for example `friends.example.com` and `family.example.com`. A Memory may naturally belong to one tenant while still being relevant to another tenant. A wedding Memory may live under Family, while many guests and later viewers are members of Friends.

Adding those guests as Family tenant members or forcing participant records across tenants would weaken tenant isolation. Copying the Memory into Friends would also split ownership, audit history, share revocation and media storage. The future federation model already expects users or instances to follow or bookmark remote shares without importing content.

Face and person recognition creates a related privacy issue. The backend may be able to match people across the instance, but search and profile surfaces must not leak that a person exists in another tenant or appears in memories the viewer cannot access.

## Decision

veyq will treat persisted shares as the bridge between tenants and, later, between instances.

- A Memory remains owned by its source tenant and source scope.
- A source tenant may create a normal Memory share for a Memory subtree.
- A target tenant, group or user may persist that share as a visible remote/shared Memory entry without copying media or changing source ownership.
- Persisted share visibility is target-scoped: the target decides which of its members can see the saved share entry.
- Effective access is still bounded by the source share role and state. If the source disables the share, changes the role or requires authentication, persisted entries must reflect that instead of preserving stale access.
- Persisted shares are the local-instance precursor to future federation and remote share bookmarking. V1 may keep the feature deferred, but the model should not require direct copying.

Person and face recognition may use instance-wide internal matching, but external behavior is always viewer-scoped.

- Internal face occurrences and person-match candidates may be stored across tenant boundaries.
- A viewer may resolve a person identity only through their own tenant memberships, explicit visible Memory participation or visible shared content.
- A viewer's active tenant does not by itself grant access to every person known by that tenant or by another tenant.
- Search is non-enumerable: unknown people, unauthorized people and known people with no visible matching media must produce the same empty-result shape.
- Search results are always filtered by visible Memories/media first. Person identity resolution can broaden the query only for people the current viewer is allowed to know.
- Public share pages must not expose cross-Memory person graphs or "where else this person appears" behavior.

## Consequences

Tenants stay useful as domain and audience boundaries without becoming hard walls against curated sharing. A Family Memory can appear in Friends as a persisted share, while Family keeps ownership and revocation control.

Person search can later feel natural for users who belong to multiple tenants. A user who is a member of Friends may search for a Friends person while browsing Family, but another Family-only user must not learn whether that Friends person exists.

Future implementation needs explicit models for persisted share entries, person identity visibility, face occurrences and person-match confirmation. API contracts must document the non-enumerable search behavior and must not expose internal person or match identifiers unless the caller is authorized to resolve them.
