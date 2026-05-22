# ADR 0005: Visible Memory Management Permissions

## Status

Accepted

## Context

Memory UI v1 uses Memory, Folder and Media scopes to decide which content a user can see. A separate edit-permission model would add role and ownership rules before the sharing model is fully designed.

For the current product baseline, collaborative Memories should be easy to maintain by the people who can access them. Tenant and Group scopes are therefore intentionally shared workspaces, not read-only publication channels.

## Decision

For Memory UI v1, visibility grants management access for Memory, Folder and Media objects in that visible scope.

Any user who can see a Tenant-scoped object may create, update, move, upload to, set covers for and delete objects in that Tenant scope. Any user who can see a Group-scoped object has the same management access in that Group scope. User-scoped objects remain visible and manageable only by that user.

Tenant owner and group owner roles remain management roles for tenant and group membership/settings. They are not required for Memory, Folder or Media management in v1.

## Consequences

The backend should keep enforcing tenant membership and scope visibility before any Memory, Folder or Media operation, but it should not add separate owner-only write checks for those objects in v1.

This keeps collaboration simple, but it means a member can modify or delete visible shared content. Future public sharing, read-only sharing, uploader buckets or fine-grained permissions must supersede or refine this ADR before implementation.
