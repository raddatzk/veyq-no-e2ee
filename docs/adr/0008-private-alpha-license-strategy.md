# ADR 0008: Private Alpha License Strategy

## Status

Accepted

## Context

Veyq is intended to be self-hostable and may later support optional managed hosting, commercial features or official hosted services. A first private alpha needs a clear release gate so the project is not accidentally published with ambiguous rights.

The product context already identifies AGPL, trademark protection, community/commercial split and optional commercial licensing as likely directions. The project does not yet ship a root `LICENSE` file.

## Decision

The private alpha remains source-available only to trusted contributors until a root `LICENSE` file is added. No public release, public container image or public hosted-service launch is made while the repository has no explicit license file.

The default public-release target is AGPL-3.0-only for the community edition, with the Veyq name, logo and other brand assets reserved under a separate trademark policy. Optional managed hosting and commercial add-ons must remain separate layers and must not make self-hosting depend on central Veyq infrastructure.

Before a public release, add the final license text, a short trademark policy and any commercial-feature boundary docs in the same release branch.

## Consequences

The first private alpha can move forward without pretending the licensing work is complete.

Public distribution is intentionally blocked until licensing artifacts exist in the repository.

Future commercial work can be designed without weakening the self-hostable community baseline.
