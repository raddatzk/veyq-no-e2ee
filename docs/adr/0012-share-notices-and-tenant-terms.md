# ADR 0012: Share Notices and Tenant Terms

## Status

Accepted

## Context

Public Memory shares can be opened by people who are not tenant members. Some operators need a lightweight way to show usage terms, privacy notes, deletion contact information and share-specific context before a visitor opens or contributes to a shared Memory.

The feature should fit self-hosted private sharing. It should not turn veyq into a centrally moderated social platform, but template-generated deletion/contact text must give visitors a real contact path.

## Decision

veyq supports two optional notice layers for public shares:

- Tenant-wide terms for operator-level rules, privacy/deletion contact information, illegal-content rules, availability disclaimers and general upload expectations.
- Share-specific notices for Memory context, such as event rules or private-family sharing expectations.

Both layers are stored as Markdown maps keyed by locale. Locale keys are normalized by trimming and lowercasing; blank locale keys or blank Markdown values are discarded. Empty maps mean no active notice.

Tenant terms and share notices are versioned separately. A version increments only when normalized Markdown content changes. Public share state includes active notices before authentication, ordered tenant first and share second, without exposing protected Memory metadata.

Browser acceptance is local only:

- Tenant terms acceptance is scoped to browser origin, tenant domain/name, rendered locale and tenant terms version. It is intentionally not scoped to an individual share.
- Share notice acceptance is scoped to share id, rendered locale and share notice version.
- If Markdown changes and the version increments, visitors must accept again.

The tenant terms editor may generate Markdown through a template import flow. The user can select individual clauses and choose whether the deletion/privacy contact should use the active tenant owner email address(es) or a custom public contact.

## Consequences

Operators can publish basic privacy and usage expectations for all public shares while share creators can add context where needed. If the template contact clause is imported with tenant-owner contact selected, active tenant owner email addresses become part of the generated public Markdown.

Acceptance is privacy-preserving and simple because no server-side acceptance records are stored. This also means veyq does not provide legal audit proof that a specific visitor accepted a specific version. Server-side audit logging, stronger legal wording, dedicated public contact fields and richer template management remain future work.
