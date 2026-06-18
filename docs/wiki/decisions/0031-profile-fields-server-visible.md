# ADR 0031: Profile Fields Are Server-Visible Identity Metadata

## Status
Accepted

## Context
veyq's content model is operator-blind, but collaboration and administration still need low-sensitivity
identity metadata: member lists, invites, account settings and support flows need a stable account email and
human-readable display name. Treating every identity label as encrypted content would make membership rosters
and administrative actions less usable without materially improving the confidentiality of Memories.

Credential changes are different: passwords, passkeys and trusted devices open Master Key wraps. They cannot
be handled as simple profile fields because they must preserve the "one Master Key, many wraps" model and the
keep-one-unlock-method invariant.

## Decision
Account profile fields such as display name, email, avatar and locale are server-visible identity metadata.
The first implemented write path updates only the display name via an authenticated profile endpoint. It does
not touch content keys, identity keys, Master Key wraps, encrypted Memory data or session tokens.

Email, password, passkey and trusted-device changes remain separate flows with their own verification and
key-wrap rules.

## Consequences
Member rosters and settings can show current profile metadata without decrypting content. The operator can see
these identity fields, so they must stay limited to low-sensitivity account metadata and must not absorb Memory
names, Chapter names, captions, media metadata or other user content. Credential work remains more complex,
but it keeps the E2EE trust chain intact.
