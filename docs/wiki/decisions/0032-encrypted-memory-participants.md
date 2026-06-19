# ADR 0032: Memory Participants Are Encrypted Person Metadata

## Status
Accepted

## Context
Legacy Memories treated people as first-class participants: a participant could be a registered user or
an anonymous named person, could be shown in the UI, could drive person-centric browsing, and was a search
key. Bringing that model into veyq is useful, but the relationship "this person participates in this Memory"
is sensitive person metadata. A plaintext join table between users and Memories would let the operator infer
social graphs, attendance and depicted-person relationships even when the Memory itself stays encrypted.

Profile display names are intentionally server-visible identity metadata (ADR 0031), but participation is not
identity administration. It belongs to the People context and must follow the same confidentiality boundary as
titles, captions, places, tags and faces.

## Decision
Memory participation is stored as encrypted People metadata. The server may store opaque participant records
scoped to a Memory, but the payload that names the participant and optionally links it to a user is sealed under
the Memory's content key.

The sealed participant payload contains at least:

- `displayName` for the person label shown in the Memory and indexed locally;
- optional `linkedUserId` when the participant represents a registered user;
- optional presentation fields such as ordering or local avatar/color hints.

An anonymous participant is a sealed payload with a `displayName` and no `linkedUserId`. A linked participant
uses the normal member/user roster only as the picker source; the resulting Memory-to-user association remains
inside the sealed payload. The server must not expose a plaintext "all Memories for user X" or "all users in
Memory Y" person graph.

Client search and person-centric profile views are computed from the viewer's decrypted, authorized Memory set.
A linked participant may be clickable in the UI, but the target view lists only Memories the viewer can already
access and decrypt. Media upload attribution remains separate: `createdBy` and contribution buckets describe who
uploaded a medium, not who participated in the Memory.

## Consequences
Participants can restore the Legacy UX without weakening full E2EE. Participant names and Memory-person
relationships stay unavailable to the operator, so search remains client-side and cannot use a server index.
User-linked participant profiles may be incomplete until a client has synced/decrypted all accessible Memories,
which is the accepted trade-off for preserving the blind-server model.

Backend APIs and storage are slightly less convenient than a relational join table: they manage opaque encrypted
records and authorization, while clients own decryption, indexing, clickability and profile aggregation. This
keeps the People context non-enumerable and aligned with the Security Model.
