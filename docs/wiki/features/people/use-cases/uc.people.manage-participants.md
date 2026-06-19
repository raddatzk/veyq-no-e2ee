# UC: Manage Participants

- **ID:** uc.people.manage-participants
- **Primary actor:** Memory Curator
- **Secondary actor:** Depicted Person
- **Goal:** Add, view and remove named participants on a Memory without exposing the person graph to the server.
- **Preconditions:** Authenticated; is a member of the Memory's scope; holds the Memory content key.
- **Postconditions (success):** The Memory has an updated encrypted participant list; linked participants are
  interactive where the viewer can resolve them, anonymous participants remain name-only, and participant names
  become local search keys.

## Main flow
1. The curator opens a Memory's people/participants control.
2. The client loads that Memory's encrypted participant records and decrypts the sealed payloads locally.
3. The curator adds either:
   - a linked participant by choosing a registered user from the accessible member/user roster; or
   - an anonymous participant by entering a display name only.
4. The client seals the participant payload under the Memory content key and writes the opaque record.
5. The Memory UI shows participant chips. A linked participant chip is clickable; an anonymous participant chip
   is not interactive.
6. The local search index associates the decrypted participant display name with the Memory.

## Alternate / exception flows
- A1: **Remove participant** -> the curator removes the encrypted participant record from the Memory.
- A2: **Rename anonymous participant** -> the client reseals the payload with the new display name.
- A3: **Linked participant profile** -> selecting a linked participant opens a local person view listing only
  other accessible Memories whose decrypted participant payload links to the same user.
- A4: **Public share viewer** -> a public viewer may see participants included in the shared encrypted subtree
  but cannot manage them.
- E1: **No content key** -> the client cannot decrypt or modify participants and shows no participant controls.
- E2: **Non-member write** -> the server rejects the write before storing the opaque record.

## Quality notes
- *Confidentiality:* Participant names and Memory-person relationships are sensitive metadata. The server stores
  only opaque encrypted payloads and must not learn which user or anonymous person is attached to which Memory.
- *Search:* Participant search is local over decrypted payloads. There is no server-side participant search
  endpoint or plaintext person index.
- *Non-enumerability:* Person-centric views are computed from the viewer's authorized decrypted Memory set; they
  cannot enumerate Memories outside that set.
- *Separation from uploads:* Upload attribution (`createdBy`) and contribution buckets remain media provenance,
  not participation. A person can participate without uploading, and an uploader is not automatically a participant.
- *Roadmap:* Face clusters may later link to participants via on-device ML, but that does not change the encrypted
  participant payload boundary.

## Related
- Feature: [People](../_index.md) · Actors: [Memory Curator](../../../actors.md), [Depicted Person](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (sensitive metadata),
  [Search](../../../crosscutting/search.md) (client-side index), [Agent Mode](../../../crosscutting/agent-mode.md)
- Decisions: [AD-02](../../../decisions/README.md) (full E2EE), [AD-13](../../../decisions/README.md)
  (client-side search), [AD-22](../../../decisions/README.md) (People context), [AD-32](../../../decisions/README.md)
  (encrypted participants)
- Related use-cases: `uc.memory.browse`, `uc.search.filter`, `uc.uploads.unified-media`
