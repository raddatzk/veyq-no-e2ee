# ADR 0002: Memory Asset Ownership

## Status

Accepted

## Context

The legacy implementation links media to albums through `AlbumMedia`, including per-album position and hidden flags. The target product model says an asset belongs to exactly one Memory.

## Decision

Assets belong directly to one Memory in the new Memory model. V1 does not support moving assets between Memories. Wrong assignment is handled by delete and re-upload until a later ADR changes this.

Manual sorting is not part of V1. Asset ordering is based on corrected `capturedAt`, extracted capture time or upload creation time.

## Consequences

New upload APIs require a target Memory. New media list APIs are Memory-scoped. The legacy `AlbumMedia` join table remains only for migration and compatibility while old library APIs exist.
