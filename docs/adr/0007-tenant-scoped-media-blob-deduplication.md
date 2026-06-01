# ADR 0007: Tenant-Scoped Media Blob Deduplication

## Status

Accepted

## Context

Memory media are user-facing assets: they belong to one Memory, have a title, uploader context, share identity context and per-Memory captured-time corrections. The bytes behind those assets can be identical across uploads, and previews should not be regenerated or stored once per Memory asset.

veyq must remain self-hostable and privacy-preserving. Content hashes must not become public API identifiers, and identical files from different tenants must not create cross-tenant linkage.

## Decision

veyq stores uploaded media bytes in tenant-scoped `MediaBlob` records addressed internally by SHA-256. `Media` remains the public, Memory-specific asset and references exactly one blob.

`MediaBlob` owns original storage keys, preview storage keys, MIME type, byte size and extracted metadata such as captured time, camera, dimensions and GPS. `Media` owns title, original filename, uploader or share identity, Memory ownership, creation time and captured-time override state.

Previews are generated and cached per blob. Original and preview storage are deleted only after the final `Media` reference to a blob is removed. Explicit Memory cover uploads stay separate from MediaBlob storage; covers may still reference a gallery `Media` asset without owning the underlying blob.

The public API continues to expose only `Media.publicId`, media URLs and public metadata summaries. SHA-256 hashes, blob IDs and storage keys remain internal.

## Consequences

Duplicate uploads within one tenant reuse original bytes and previews while preserving separate Memory assets and permissions. Duplicate uploads across tenants do not share blob rows or storage paths.

Delete paths must remove `Media` rows first, then delete blob storage only when no other media references the blob. Archive downloads and streaming must resolve storage through `Media.blob`.

Legacy stored media is not backfilled by this decision; the baseline migration creates the current forward schema.
