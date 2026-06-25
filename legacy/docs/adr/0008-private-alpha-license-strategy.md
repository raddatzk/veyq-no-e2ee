# ADR 0008: Private Alpha License Strategy

## Status

Accepted

## Context

veyq is intended to be self-hostable and may later support optional managed hosting, commercial features or official hosted services. A first private alpha needs a clear release gate so the project is not accidentally published with ambiguous rights.

The product context already identifies AGPL, trademark protection, community/commercial split and optional commercial licensing as likely directions. Before V1, the project did not ship a root `LICENSE` file.

## Decision

The private alpha remained source-available only to trusted contributors until a root `LICENSE` file was added. No public release, public container image or public hosted-service launch may be made while the repository has no explicit license file.

The default public-release target is AGPL-3.0-only for the community edition, with the veyq name, logo and other brand assets reserved under a separate trademark policy. Optional managed hosting and commercial add-ons must remain separate layers and must not make self-hosting depend on central veyq infrastructure.

Before a public release, add the final license text, a short trademark policy and any commercial-feature boundary docs in the same release branch.

## Consequences

The first private alpha could move forward without pretending the licensing work was complete.

Public distribution is blocked unless licensing artifacts exist in the repository.

Future commercial work can be designed without weakening the self-hostable community baseline.

## Follow-up

Implemented for V1 by adding:

- `LICENSE` with AGPL-3.0-only text.
- `NOTICE` with project and brand notices.
- `docs/legal/trademark-policy.md`.
- `docs/legal/commercial-boundary.md`.
