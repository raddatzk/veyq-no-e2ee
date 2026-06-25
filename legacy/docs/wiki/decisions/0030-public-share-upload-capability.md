# ADR 0030: Public Share Upload Capability

## Status
Accepted

## Context
Public `CONTRIBUTOR` and `UPLOADER` shares intentionally allow unauthenticated guests to add encrypted
media to a shared Memory. Before this decision, the write path was authorized by `shareId` and role alone.
That did not break confidentiality because uploads are sealed client-side, but it made `shareId` itself a
write capability: anyone who learned only the path token could append encrypted junk until rate limits or
quotas stopped them.

The server must stay blind: it cannot learn the share key, content key or plaintext metadata just to verify
guest writes.

## Decision
Every upload-capable public share stores a blind upload-capability hash. The client derives a raw
upload-capability token from the share unwrap key:

- normal share: derived from the fragment share key;
- password-protected share: derived after folding in the password, so the link alone is not enough.

The owner sends only `SHA-256(token)` when creating the share. Guest upload, upload-progress, complete and
media-attach endpoints require `X-Veyq-Share-Upload-Token`; the backend hashes the supplied token and compares
it to the stored hash. The raw token is never persisted.

## Consequences
`shareId` alone no longer authorizes writes. A leaked full link still grants exactly the access the curator
chose, and a captured upload-capability header remains a bearer upload capability until the share is revoked.
That is acceptable because it is scoped to the share, stores no content key server-side, and leaves existing
rate limits and byte quotas in place as abuse controls.
