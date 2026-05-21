# ADR 0002: Memory Asset Ownership

## Status

Accepted

## Context

The legacy implementation linked media to albums through an album-media join model, including per-album position and hidden flags. The active Memory model says an asset belongs to exactly one Memory.

## Decision

Assets belong directly to one Memory in the new Memory model. V1 does not support moving assets between Memories. Wrong assignment is handled by delete and re-upload until a later ADR changes this.

Manual sorting is not part of V1. Asset ordering is based on corrected `capturedAt`, extracted capture time or upload creation time.

## Consequences

Upload APIs require a target Memory. Media list APIs are Memory-scoped. The legacy album-media join table has been removed with the Album product model.
